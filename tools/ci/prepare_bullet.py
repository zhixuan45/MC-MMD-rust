"""检出根构建脚本固定的 Bullet 源码版本。"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BUILD_FILE = ROOT / "build.gradle"


def run(*args: str, cwd: Path | None = None) -> str:
    result = subprocess.run(
        args, cwd=cwd, check=True, text=True, stdout=subprocess.PIPE
    )
    return result.stdout.strip()


def main() -> None:
    text = BUILD_FILE.read_text(encoding="utf-8")
    commit_match = re.search(r'bullet3Commit\s*=\s*"([0-9a-f]{40})"', text)
    repo_match = re.search(r'bullet3Repository\s*=\s*"([^"]+)"', text)
    dir_match = re.search(r'bullet3Dir\s*=\s*file\("([^"]+)"\)', text)
    if not (commit_match and repo_match and dir_match):
        raise SystemExit("Could not read the pinned Bullet source from build.gradle")

    commit, repository, relative_dir = (
        commit_match.group(1),
        repo_match.group(1),
        dir_match.group(1),
    )
    checkout = (ROOT / relative_dir).resolve()
    if ROOT not in checkout.parents:
        raise SystemExit("Pinned Bullet directory resolves outside the workspace")

    if checkout.exists():
        if not (checkout / ".git").exists():
            raise SystemExit(f"Bullet directory exists but is not a Git checkout: {checkout}")
        actual = run("git", "-C", str(checkout), "rev-parse", "HEAD")
        if actual != commit:
            raise SystemExit(f"Bullet checkout is {actual}; expected pinned commit {commit}")
    else:
        checkout.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--no-checkout", repository, str(checkout))
        run("git", "checkout", "--detach", commit, cwd=checkout)

    actual = run("git", "-C", str(checkout), "rev-parse", "HEAD")
    if actual != commit or not (checkout / "src" / "LinearMath").is_dir():
        raise SystemExit(f"Bullet source verification failed for pinned commit {commit}")
    print(f"Bullet source ready at {commit}")


if __name__ == "__main__":
    main()
