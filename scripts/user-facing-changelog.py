#!/usr/bin/env python3
"""Filter commit subjects down to user-facing changes, for the Play Store
"What's new" changelog.

Reads commit subjects on stdin (one per line, as produced by
`git log --pretty=format:%s`) and writes "- <subject>" lines for the
user-facing ones. Non-user-facing commits are dropped so the handful of
salient changes surface within Google Play's 500-byte limit, instead of the
list being truncated mid-way through CI/dependency noise.

Dropped:
  - conventional-commit types that never reach users: chore, build, ci,
    test(s), style, docs, refactor, deps, release, lint
  - any commit scoped to infrastructure (ci, build, test, deps, gradle,
    fastlane, signing, workflow, actions, check-jnilibs), regardless of type
  - research-edition-only work (subjects mentioning "research")
  - merge commits

Kept: feat/fix/perf/... unless one of the rules above applies, and any subject
that is not a conventional commit (usually a human-written one, where
guessing wrong is worse than showing one extra line).

Usage:
  git log v0.13.2..HEAD --pretty=format:%s | scripts/user-facing-changelog.py
  scripts/user-facing-changelog.py --self-test
"""

from __future__ import annotations

import io
import re
import sys
from collections.abc import Iterable, Iterator

NON_USER_TYPES = frozenset(
    {
        "chore",
        "build",
        "ci",
        "test",
        "tests",
        "style",
        "docs",
        "refactor",
        "deps",
        "release",
        "lint",
    }
)

INFRA_SCOPES = frozenset(
    {
        "check-jnilibs",
        "ci",
        "build",
        "test",
        "tests",
        "deps",
        "gradle",
        "fastlane",
        "signing",
        "release",
        "workflow",
        "actions",
        "lint",
    }
)

# "type(scope): subject" / "type: subject" / "type(scope)!: subject"
_CONVENTIONAL = re.compile(r"^([a-z]+)(?:\(([^)]+)\))?!?:\s")
_RESEARCH = re.compile(r"[Rr]esearch")


def is_user_facing(subject: str) -> bool:
    """Return True if the commit subject belongs in the Play Store notes."""
    # Research-edition-only work is not part of the public Play listing.
    if _RESEARCH.search(subject):
        return False
    # Merge commits are noise in a "what's new" list.
    if subject.startswith("Merge "):
        return False
    match = _CONVENTIONAL.match(subject)
    if match is None:
        # Not a conventional commit (usually human-written) -- keep it.
        return True
    commit_type, scope = match.group(1), match.group(2)
    if commit_type in NON_USER_TYPES:
        return False
    if scope and scope in INFRA_SCOPES:
        return False
    return True


def filter_stream(lines: Iterable[str]) -> Iterator[str]:
    """Yield "- <subject>" for each user-facing subject in *lines*.

    Iterating the stream (rather than splitting a string) is deliberate:
    `git log --pretty=format:` emits no trailing newline, and iterator
    semantics still hand us the final subject in that case.
    """
    for raw in lines:
        subject = raw.rstrip("\n")
        if not subject:
            continue
        if is_user_facing(subject):
            yield f"- {subject}"


def _stream(text: str) -> list[str]:
    return list(filter_stream(io.StringIO(text)))


def self_test() -> int:
    failures: list[str] = []

    def expect(want: str, subject: str) -> None:
        got = "keep" if is_user_facing(subject) else "drop"
        if got != want:
            failures.append(f"want={want} got={got} subject={subject!r}")

    # Kept: user-visible behaviour changes.
    expect("keep", "fix(android): match system bars to webui theme; drop drawer teal gradient (#276)")
    expect("keep", "fix(watcher): construct RustInterface off the calling thread (#277)")
    expect("keep", "feat(sync): show what synced and when")
    expect("keep", "perf(ui): render the timeline without a full reload")
    expect("keep", "fix: re-enable orientation change without reloading the webview (#271)")
    expect("keep", "fix(android): harden startup paths that turned a slow datastore into a hang")
    expect("keep", "Update README.md")

    # Dropped: conventional-commit types that are not user-facing.
    expect("drop", "chore: bump versionName to 0.14.2b1, versionCode to 43")
    expect("drop", "build(release): enable R8 minification and publish the mapping file (#270)")
    expect("drop", "ci(release): device gate, diff summary, and staged production rollout (#265)")
    expect("drop", "ci: switched to using rust-android-gradle for building aw-server-rust")
    expect("drop", "test(e2e): wait for the rotated layout before post-rotation inset assertion (#282)")
    expect("drop", "tests: add a unit test for the migration")
    expect("drop", "docs: add CITATION.cff pointing to ActivityWatch DOI (#279)")
    expect("drop", "fix(check-jnilibs): also validate libaw_sync.so, bound program-header reads")
    expect("drop", "style: reformat")
    expect("drop", "refactor: extract hostname helpers")
    expect("drop", "build(deps): bump actions/download-artifact from 3 to 4.1.7")

    # Dropped: infrastructure scopes regardless of type.
    expect("drop", "fix(ci): strip -research suffix in versionName assertion; document research builds (#280)")
    expect("drop", "fix(test): make the e2e helper deterministic")
    expect("drop", "feat(ci): cache the gradle wrapper")

    # Dropped: research-edition-only work.
    expect("drop", "feat(mobile): research build flavor with own applicationId and port (#281)")
    expect("drop", "fix(research): only show the consent screen once")

    # Dropped: merges.
    expect("drop", "Merge pull request #281 from TimeToBuildBob/feat/research-flavor")

    # Stream behaviour: "- " prefixes and drops filtered entries. Both the
    # trailing-newline and no-trailing-newline shapes are covered explicitly,
    # since `git log --pretty=format:` emits the latter.
    got = _stream("chore: bump versionName\nfix(ui): keep the last line")
    if got != ["- fix(ui): keep the last line"]:
        failures.append(f"stream no-trailing-newline got {got!r}")

    got = _stream("chore: bump versionName\nfix(ui): keep the last line\n")
    if got != ["- fix(ui): keep the last line"]:
        failures.append(f"stream trailing-newline got {got!r}")

    got = _stream("chore: nothing user-facing")
    if got != []:
        failures.append(f"stream all-filtered got {got!r}")

    got = _stream("")
    if got != []:
        failures.append(f"stream empty got {got!r}")

    got = _stream("\n\nfix: real change\n\n")
    if got != ["- fix: real change"]:
        failures.append(f"stream blank-lines got {got!r}")

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        print("user-facing-changelog self-test FAILED", file=sys.stderr)
        return 1
    print("user-facing-changelog self-test passed")
    return 0


def usage() -> int:
    print(
        "Usage:\n"
        "  git log <range> --pretty=format:%s | scripts/user-facing-changelog.py\n"
        "  scripts/user-facing-changelog.py --self-test",
        file=sys.stderr,
    )
    return 2


def main(argv: list[str]) -> int:
    if not argv:
        for line in filter_stream(sys.stdin):
            print(line)
        return 0
    if argv == ["--self-test"]:
        return self_test()
    return usage()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
