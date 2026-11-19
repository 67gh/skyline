// SPDX-License-Identifier: MPL-2.0
// Copyright © 2020 Skyline Team and Contributors (https://github.com/skyline-emu/)
// Copyright © 2022 yuzu Emulator Project (https://github.com/yuzu-emu/)

#include <audio.h>
#include <kernel/types/KProcess.h>
#include "IAudioOut.h"
#include "audio_stats.h"

namespace skyline::service::audio {
    namespace {
        RateStats appendStats; //!< Buffers the game appends to the audio output
        RateStats releaseStats; //!< Buffers the game collects back once they were played
    }

    IAudioOut::IAudioOut(const DeviceState &state, ServiceManager &manager, size_t sessionId,
                         std::string_view deviceName, AudioCore::AudioOut::AudioOutParameter parameters,
                         KHandle handle, u32 appletResourceUserId)
        :  BaseService{state, manager},
           releaseEvent{std::make_shared<type::KEvent>(state, false)},
           releaseEventWrapper{[releaseEvent = this->releaseEvent]() { releaseEvent->Signal(); },
                               [releaseEvent = this->releaseEvent]() { releaseEvent->ResetSignal(); }},
           impl{std::make_shared<AudioCore::AudioOut::Out>(state.audio->audioSystem, *state.audio->audioOutManager, &releaseEventWrapper, sessionId)} {

        if (impl->GetSystem().Initialize(std::string{deviceName}, parameters, handle, appletResourceUserId).IsError())
            Logger::Warn("Failed to initialise Audio Out");
    }

    IAudioOut::~IAudioOut() {
        impl->Free();
    }

    Result IAudioOut::GetAudioOutState(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        response.Push(static_cast<u32>(impl->GetState()));
        return {};
    }

    Result IAudioOut::StartAudioOut(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        return Result{impl->StartSystem()};
    }

    Result IAudioOut::StopAudioOut(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        return Result{impl->StopSystem()};
    }

    Result IAudioOut::AppendAudioOutBuffer(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        const auto &buffer{request.inputBuf.at(0).as<AudioCore::AudioOut::AudioOutBuffer>()};
        auto tag{request.Pop<u64>()};

        auto result{impl->AppendBuffer(buffer, tag)};
        if (const auto summary{appendStats.Record(1, 0)})
            Logger::Info("AudioOut: {} buffers appended in {:.1f}s ({:.0f}/s), {} queued, {} samples played so far", summary->calls, summary->seconds, static_cast<double>(summary->calls) / summary->seconds, impl->GetBufferCount(), impl->GetPlayedSampleCount());
        return Result{result};
    }

    Result IAudioOut::RegisterBufferEvent(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        auto handle{state.process->InsertItem(releaseEvent)};
        Logger::Debug("Buffer Release Event Handle: 0x{:X}", handle);
        response.copyHandles.push_back(handle);
        return {};
    }

    Result IAudioOut::GetReleasedAudioOutBuffer(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        auto maxCount{request.outputBuf.at(0).size() >> 3};

        std::vector<u64> releasedBuffers(maxCount);
        auto count{impl->GetReleasedBuffers(releasedBuffers)};

        request.outputBuf.at(0).copy_from(releasedBuffers);
        if (const auto summary{releaseStats.Record(count, 0)})
            Logger::Info("AudioOut: {} buffers released in {:.1f}s over {} polls", summary->amount, summary->seconds, summary->calls);
        response.Push<u32>(count);
        return {};
    }

    Result IAudioOut::ContainsAudioOutBuffer(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        auto tag{request.Pop<u64>()};
        response.Push(static_cast<u32>(impl->ContainsAudioBuffer(tag)));
        return {};
    }

    Result IAudioOut::GetAudioOutBufferCount(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        response.Push(impl->GetBufferCount());
        return {};
    }

    Result IAudioOut::GetAudioOutPlayedSampleCount(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        response.Push(impl->GetPlayedSampleCount());
        return {};
    }

    Result IAudioOut::FlushAudioOutBuffers(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        response.Push(static_cast<u32>(impl->FlushAudioOutBuffers()));
        return {};
    }

    Result IAudioOut::SetAudioOutVolume(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        auto volume{request.Pop<float>()};
        impl->SetVolume(volume);
        return {};
    }

    Result IAudioOut::GetAudioOutVolume(type::KSession &session, ipc::IpcRequest &request, ipc::IpcResponse &response) {
        response.Push(impl->GetVolume());
        return {};
    }
}
