#!/usr/bin/env python3
"""Create an isolated Kit/A/B/C Git fixture; refuses to overwrite any target."""

from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path


def git(root: Path, *arguments: str) -> None:
    subprocess.run(["git", "-C", str(root), *arguments], check=True, env={
        "GIT_AUTHOR_NAME": "Agent Workbench smoke",
        "GIT_AUTHOR_EMAIL": "smoke@example.invalid",
        "GIT_COMMITTER_NAME": "Agent Workbench smoke",
        "GIT_COMMITTER_EMAIL": "smoke@example.invalid",
    })


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--kit-root", required=True, type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    kit_source = args.kit_root.resolve()
    if root.is_relative_to(kit_source) or kit_source.is_relative_to(root):
        raise SystemExit("fixture root and Kit input must not contain one another")
    if root.exists():
        raise SystemExit(f"refusing to overwrite existing fixture: {root}")
    root.mkdir(parents=True)
    (root / ".agent-workbench-smoke-roots").write_text("kit\na\nb\nc\nremote.git\n", encoding="utf-8")
    def ignored(directory: str, names: list[str]) -> set[str]:
        relative = Path(directory).resolve().relative_to(kit_source)
        blocked = {name for name in names if name in {".git", ".idea", ".workspace", "__pycache__"}}
        if relative == Path("docs") and "development" in names:
            blocked.add("development")
        return blocked

    kit = root / "kit"
    shutil.copytree(kit_source, kit, ignore=ignored)
    subprocess.run(["git", "init", "-b", "main", str(kit)], check=True)
    git(kit, "add", ".")
    git(kit, "commit", "-m", "smoke kit")
    subprocess.run(["git", "init", "--bare", str(root / "remote.git")], check=True)
    for name in ("a", "b", "c"):
        repo = root / name
        subprocess.run(["git", "init", "-b", "main", str(repo)], check=True)
        (repo / "README.md").write_text(f"{name}\n", encoding="utf-8")
        git(repo, "add", ".")
        git(repo, "commit", "-m", "initial")
        git(repo, "remote", "add", "smoke", str(root / "remote.git"))


if __name__ == "__main__":
    main()
