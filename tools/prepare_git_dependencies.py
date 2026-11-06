#!/usr/bin/env python3
"""Prepare Skyline dependencies whether uploaded from a Git repository or ZIP.

A ZIP export may contain stale submodule .git *files* but not the associated
.git/modules directories. Git then fails with "gitfile does not point to a
valid repository". Remove only those stale files and populate dependencies
using real Git submodule entries or the .gitmodules URLs as a fallback.
"""
from __future__ import annotations

import configparser
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def run(*args: str, cwd: Path = ROOT, check: bool = True) -> subprocess.CompletedProcess:
    print('+', ' '.join(args), flush=True)
    return subprocess.run(args, cwd=cwd, text=True, check=check)


def broken_git_file(path: Path) -> bool:
    if not path.is_file() or path.is_symlink():
        return False
    try:
        value = path.read_text(encoding='utf-8').strip()
    except UnicodeDecodeError:
        return False
    if not value.startswith('gitdir: '):
        return False
    target = (path.parent / value[8:]).resolve()
    return not (target.is_dir() and (target / 'HEAD').exists())


def remove_broken_git_files() -> None:
    for gitfile in ROOT.rglob('.git'):
        if broken_git_file(gitfile):
            print(f'Removing stale ZIP gitfile: {gitfile.relative_to(ROOT)}')
            gitfile.unlink()


def sections(modules: Path):
    config = configparser.RawConfigParser()
    config.read(modules, encoding='utf-8')
    for name in config.sections():
        if name.startswith('submodule ') and config.has_option(name, 'path') and config.has_option(name, 'url'):
            yield (config.get(name, 'path'), config.get(name, 'url'), config.get(name, 'branch', fallback=None))


def populated(directory: Path) -> bool:
    return directory.is_dir() and any(x.name != '.git' for x in directory.iterdir())


def clone_missing(modules: Path) -> None:
    base = modules.parent
    for relative, url, branch in sections(modules):
        target = (base / relative).resolve()
        if not target.is_relative_to(ROOT.resolve()):
            raise RuntimeError(f'Unsafe submodule path: {relative}')
        if populated(target):
            continue
        if target.exists():
            if target.is_dir():
                target.rmdir()  # Only empty directories are removed.
            else:
                raise RuntimeError(f'Dependency path is not a directory: {target}')
        cmd = ['git', 'clone', '--depth', '1', '--recurse-submodules', '--shallow-submodules']
        if branch:
            cmd.extend(['--branch', branch])
        cmd.extend(['--', url, str(target)])
        run(*cmd)


def main() -> None:
    os.chdir(ROOT)
    remove_broken_git_files()
    # Real gitlinks (mode 160000) are initialized at their pinned revisions.
    staged = subprocess.run(['git', 'ls-files', '--stage'], cwd=ROOT, capture_output=True, text=True, check=True)
    has_gitlinks = any(line.startswith('160000 ') for line in staged.stdout.splitlines())
    if has_gitlinks:
        run('git', 'submodule', 'sync', '--recursive')
        run('git', 'submodule', 'update', '--init', '--recursive', '--force')
        remove_broken_git_files()
    # ZIPs uploaded as ordinary files may not retain gitlink metadata.
    # Populate only entirely missing dependencies from their declared URLs.
    clone_missing(ROOT / '.gitmodules')
    for nested in (ROOT / 'app/libraries').rglob('.gitmodules'):
        clone_missing(nested)
    print('Dependencies ready. Note: ZIP-imported libraries are not pinned to the original Git submodule SHAs.')


if __name__ == '__main__':
    try:
        main()
    except (subprocess.CalledProcessError, OSError, RuntimeError) as error:
        print(f'Dependency preparation failed: {error}', file=sys.stderr)
        sys.exit(1)
