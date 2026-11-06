# Skyline GitHub ARM64 build repair

- Removed obsolete LLVM gitlink with no `.gitmodules` entry; top-level CMake does not reference `libraries/llvm`.
- Removed dangling Dynarmic submodule mapping (no gitlink). Optional JIT fallback is disabled for now.
- Upgraded Gradle wrapper to 8.2 for AGP 8.2.1 and Java 17.
- Added debug ARM64 CI APK workflow with NDK 26.1.10909125 and CMake 3.22.1 and no release keystore requirement.
- Disabled outdated CI workflows.
- Build is NOT yet verified with successful Android compilation.
- On Termux, clone afresh rather than copying `.git` files from an extracted ZIP.
