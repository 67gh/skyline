SKYLINE - Git submodule repair for ZIP-imported sources

Cause:
  app/libraries/shader-compiler/.git is an archived pointer to
  ../../../.git/modules/Shader Compiler, a folder not present in the ZIP.
  vkhpp/.git and vkhpp/Vulkan-Headers/.git have the same issue.

Apply:
  Copy the contents of skyline/ into the root of your GitHub repository.
  Keep the original .gitmodules and the library source code.
  Commit tools/prepare_git_dependencies.py and .github/workflows/build.yml.
  Re-run Actions > Build Android ARM64 APK.

The updated workflow checks out without recursive submodules, cleans orphaned
.git pointer files, initializes genuine pinned gitlinks when present, and
clones missing source dependencies declared in .gitmodules when the repository
came from a ZIP. Do NOT run `git submodule update --recursive` before this
repair step on a ZIP-imported repository.

If your GitHub repository itself tracks the invalid .git files, remove them
from git and commit that deletion too, ideally:
  git rm --cached -f app/libraries/shader-compiler/.git
  git rm --cached -f app/libraries/vkhpp/.git
  git rm --cached -f app/libraries/vkhpp/Vulkan-Headers/.git
  rm -f app/libraries/shader-compiler/.git \
        app/libraries/vkhpp/.git \
        app/libraries/vkhpp/Vulkan-Headers/.git
  git add -A && git commit -m "Remove broken archived git pointers"

Note: This fixes the specified Git metadata error, not yet any subsequent
C++ or Gradle build failures. Original submodule commit IDs are not recoverable
from ZIP-only uploads; clone fallback may use newer library commits.
