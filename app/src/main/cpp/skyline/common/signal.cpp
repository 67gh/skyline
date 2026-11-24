// SPDX-License-Identifier: MPL-2.0
// Copyright © 2020 Skyline Team and Contributors (https://github.com/skyline-emu/)

#include <unistd.h>
#include <dlfcn.h>
#include <unwind.h>
#include <fcntl.h>
#include <cstdio>
#include <cstring>
#include <sys/prctl.h>
#include "signal.h"

namespace skyline::signal {
    thread_local std::exception_ptr SignalExceptionPtr;

    void ExceptionThrow() {
        std::rethrow_exception(SignalExceptionPtr);
    }

    void SleepTillExit() {
        // We don't want to actually exit the process ourselves as it'll automatically be restarted gracefully due to a timeout after being unable to exit within a fixed duration
        Logger::EmulationContext.TryFlush(); // We want to attempt to flush logs before exiting
        while (true)
            sleep(std::numeric_limits<int>::max()); // We just trigger the timeout wait by sleeping forever
    }

    inline StackFrame *SafeFrameRecurse(size_t depth, StackFrame *frame) {
        if (frame) {
            for (size_t it{}; it < depth; it++) {
                if (frame->lr && frame->next)
                    frame = frame->next;
                else
                    SleepTillExit();
            }
        } else {
            SleepTillExit();
        }
        return frame;
    }

    void TerminateHandler() {
        auto exception{std::current_exception()};
        if (exception && exception == SignalExceptionPtr) {
            StackFrame *frame;
            asm("MOV %0, FP" : "=r"(frame));
            frame = SafeFrameRecurse(2, frame); // We unroll past 'std::terminate'

            static void *exceptionThrowEnd{};
            if (!exceptionThrowEnd) {
                // We need to find the function bounds for ExceptionThrow, if we haven't already
                u32 *it{reinterpret_cast<u32 *>(&ExceptionThrow) + 1};
                while (_Unwind_FindEnclosingFunction(it) == &ExceptionThrow)
                    it++;
                exceptionThrowEnd = it - 1;
            }

            auto lookupFrame{frame};
            bool hasAdvanced{};
            while (lookupFrame && lookupFrame->lr) {
                if (lookupFrame->lr >= reinterpret_cast<void *>(&ExceptionThrow) && lookupFrame->lr < exceptionThrowEnd) {
                    // We need to check if the current stack frame is from ExceptionThrow
                    // As we need to skip past it (2 frames) and be able to recognize when we're in an infinite loop
                    if (!hasAdvanced) {
                        frame = SafeFrameRecurse(2, lookupFrame);
                        hasAdvanced = true;
                    } else {
                        SleepTillExit(); // We presumably have no exception handlers left on the stack to consume the exception, it's time to quit
                    }
                }
                lookupFrame = lookupFrame->next;
            }

            if (!frame->next)
                SleepTillExit(); // We don't know the frame's stack boundaries, the only option is to quit

            asm("MOV SP, %x0\n\t" // Stack frame is the first item on a function's stack, it's used to calculate calling function's stack pointer
                "MOV LR, %x1\n\t"
                "MOV FP, %x2\n\t" // The stack frame of the calling function should be set
                "BR %x3"
            : : "r"(frame + 1), "r"(frame->lr), "r"(frame->next), "r"(&ExceptionThrow));

            __builtin_unreachable();
        } else {
            SleepTillExit(); // We don't want to delegate to the older terminate handler as it might cause an exit
        }
    }

    void ExceptionalSignalHandler(int signal, siginfo *info, ucontext *context) {
        SignalException signalException;
        signalException.signal = signal;
        signalException.pc = reinterpret_cast<void *>(context->uc_mcontext.pc);
        if (signal == SIGSEGV)
            signalException.fault = info->si_addr;

        signalException.frames.push_back(reinterpret_cast<void *>(context->uc_mcontext.pc));
        StackFrame *frame{reinterpret_cast<StackFrame *>(context->uc_mcontext.regs[29])};
        while (frame && frame->lr) {
            signalException.frames.push_back(frame->lr);
            frame = frame->next;
        }

        SignalExceptionPtr = std::make_exception_ptr(signalException);
        context->uc_mcontext.pc = reinterpret_cast<u64>(&ExceptionThrow);

        std::set_terminate(TerminateHandler);

        Logger::EmulationContext.TryFlush(); // We want to attempt to flush all logs in case exception handling fails and infloops
    }

    template<typename Signature>
    Signature GetLibcFunction(const char *symbol) {
        void *libc{dlopen("libc.so", RTLD_LOCAL | RTLD_LAZY)};
        if (!libc)
            throw exception("dlopen-ing libc has failed with: {}", dlerror());
        auto function{reinterpret_cast<Signature>(dlsym(libc, symbol))};
        if (!function)
            throw exception("Cannot find '{}' in libc: {}", symbol, dlerror());
        return function;
    }

    void Sigaction(int signal, const struct sigaction *action, struct sigaction *oldAction) {
        static decltype(&sigaction) real{};
        if (!real)
            real = GetLibcFunction<decltype(&sigaction)>("sigaction");
        if (real(signal, action, oldAction) < 0)
            throw exception("sigaction has failed with {}", strerror(errno));
    }

    static void *(*TlsRestorer)(){};

    void SetTlsRestorer(void *(*function)()) {
        TlsRestorer = function;
    }

    struct DefaultSignalHandler {
        void (*function)(int, struct siginfo *, void *){};

        ~DefaultSignalHandler();
    };

    std::array<DefaultSignalHandler, NSIG> DefaultSignalHandlers;

    DefaultSignalHandler::~DefaultSignalHandler() {
        if (function) {
            int signal{static_cast<int>(this - DefaultSignalHandlers.data())};

            struct sigaction oldAction;
            Sigaction(signal, nullptr, &oldAction);

            struct sigaction action{
                .sa_sigaction = function,
                .sa_flags = oldAction.sa_flags,
            };
            Sigaction(signal, &action);
        }
    }

    thread_local std::array<SignalHandler, NSIG> ThreadSignalHandlers{};

    static int CrashReportFd{-1}; //!< Descriptor of native_crash.log, opened in advance as nothing can be allocated or opened while handling a crash

    void InitialiseCrashReport(const std::string &path) {
        CrashReportFd = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    }

    struct CrashBacktrace {
        std::array<uintptr_t, 24> frames;
        size_t count;
    };

    static _Unwind_Reason_Code CrashBacktraceCallback(struct _Unwind_Context *context, void *argument) {
        auto &backtrace{*static_cast<CrashBacktrace *>(argument)};
        if (backtrace.count == backtrace.frames.size())
            return _URC_END_OF_STACK;
        auto pc{_Unwind_GetIP(context)};
        if (!pc)
            return _URC_END_OF_STACK;
        backtrace.frames[backtrace.count++] = pc;
        return _URC_NO_REASON;
    }

    /**
     * @brief Writes where a crash happened on a thread that isn't running guest code (driver/worker threads) into native_crash.log
     * @note This only runs right before the default (system) crash handler, which kills the process anyway, so it favours being informative over being strictly async-signal-safe
     */
    __attribute__((no_stack_protector))
    static void WriteCrashReport(int signal, siginfo *info, ucontext *context) {
        if (CrashReportFd < 0 || (signal != SIGSEGV && signal != SIGBUS && signal != SIGILL && signal != SIGFPE && signal != SIGABRT))
            return;

        char name[17]{};
        prctl(PR_GET_NAME, name);

        auto &machineContext{context->uc_mcontext};
        auto describe{[](uint64_t address, char *out, size_t size) {
            Dl_info symbol{};
            if (dladdr(reinterpret_cast<void *>(address), &symbol) && symbol.dli_fname)
                snprintf(out, size, "%s+0x%llx", symbol.dli_fname, static_cast<unsigned long long>(address - reinterpret_cast<uint64_t>(symbol.dli_fbase)));
            else
                snprintf(out, size, "unknown");
        }};

        char pcModule[256], lrModule[256], line[768];
        describe(machineContext.pc, pcModule, sizeof(pcModule));
        describe(machineContext.regs[30], lrModule, sizeof(lrModule));

        // Faults in code that isn't part of any library are the Android runtime's own (implicit null checks in compiled Java code) which it handles itself, not crashes
        if (strcmp(pcModule, "unknown") == 0)
            return;
        int length{snprintf(line, sizeof(line), "NATIVE CRASH signal %d code %d fault 0x%llx thread '%s' tid %d\n  pc 0x%llx %s\n  lr 0x%llx %s\n  sp 0x%llx\n", signal, info->si_code, static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(info->si_addr)), name, gettid(), static_cast<unsigned long long>(machineContext.pc), pcModule, static_cast<unsigned long long>(machineContext.regs[30]), lrModule, static_cast<unsigned long long>(machineContext.sp))};
        if (length > 0)
            write(CrashReportFd, line, std::min(static_cast<size_t>(length), sizeof(line) - 1));

        CrashBacktrace backtrace{};
        _Unwind_Backtrace(CrashBacktraceCallback, &backtrace);
        for (size_t i{}; i < backtrace.count; i++) {
            char frameModule[256];
            describe(backtrace.frames[i], frameModule, sizeof(frameModule));
            length = snprintf(line, sizeof(line), "  #%02zu 0x%llx %s\n", i, static_cast<unsigned long long>(backtrace.frames[i]), frameModule);
            if (length > 0)
                write(CrashReportFd, line, std::min(static_cast<size_t>(length), sizeof(line) - 1));
        }
        fsync(CrashReportFd);
    }

    __attribute__((no_stack_protector)) // Stack protector stores data in TLS at the function epilogue and verifies it at the prolog, we cannot allow writes to guest TLS and may switch to an alternative TLS during the signal handler and have disabled the stack protector as a result
    void ThreadSignalHandler(int signal, siginfo *info, ucontext *context) {
        void *tls{}; // The TLS value prior to being restored if it is
        if (TlsRestorer)
            tls = TlsRestorer();

        auto handler{ThreadSignalHandlers.at(static_cast<size_t>(signal))};
        if (handler) {
            handler(signal, info, context, &tls);
        } else {
            WriteCrashReport(signal, info, context);

            auto defaultHandler{DefaultSignalHandlers.at(static_cast<size_t>(signal)).function};
            if (defaultHandler)
                defaultHandler(signal, info, context);
        }

        if (tls)
            asm volatile("MSR TPIDR_EL0, %x0"::"r"(tls));
    }

    void SetSignalHandler(std::initializer_list<int> signals, SignalHandler function, bool syscallRestart) {
        static std::array<std::once_flag, NSIG> signalHandlerOnce{};

        struct sigaction action{
            .sa_sigaction = reinterpret_cast<void (*)(int, siginfo *, void *)>(ThreadSignalHandler),
            .sa_flags = SA_SIGINFO | SA_EXPOSE_TAGBITS | (syscallRestart ? SA_RESTART : 0) | SA_ONSTACK,
        };

        for (int signal : signals) {
            std::call_once(signalHandlerOnce[static_cast<size_t>(signal)], [signal, &action]() {
                struct sigaction oldAction;
                Sigaction(signal, &action, &oldAction);
                if (oldAction.sa_flags) {
                    oldAction.sa_flags &= ~SA_UNSUPPORTED; // Mask out kernel not supporting old sigaction() bits
                    oldAction.sa_flags |= SA_SIGINFO | SA_EXPOSE_TAGBITS | SA_RESTART | SA_ONSTACK; // Intentionally ignore these flags for the comparison
                    if (oldAction.sa_flags != (action.sa_flags | SA_RESTART))
                        throw exception("Old sigaction flags aren't equivalent to the replaced signal: {:#b} | {:#b}", oldAction.sa_flags, action.sa_flags);
                }

                DefaultSignalHandlers.at(static_cast<size_t>(signal)).function = (oldAction.sa_flags & SA_SIGINFO) ? oldAction.sa_sigaction : reinterpret_cast<void (*)(int, struct siginfo *, void *)>(oldAction.sa_handler);
            });
            ThreadSignalHandlers.at(static_cast<size_t>(signal)) = function;
        }
    }

    void Sigprocmask(int how, const sigset_t &set, sigset_t *oldSet) {
        static decltype(&pthread_sigmask) real{};
        if (!real)
            real = GetLibcFunction<decltype(&sigprocmask)>("sigprocmask");
        if (real(how, &set, oldSet) < 0)
            throw exception("sigprocmask has failed with {}", strerror(errno));
    }
}
