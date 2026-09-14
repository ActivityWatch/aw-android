#!/usr/bin/env bash
# Pick the git tag a release should diff against.
#
# Stable X.Y.Z  → previous stable ancestor (skip betas/dev so the summary
#                 covers the full production scope).
# Anything else → nearest tag of any kind (`git describe`).
#
# Usage:
#   scripts/previous-release-tag.sh <version>
#   scripts/previous-release-tag.sh --self-test

set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  scripts/previous-release-tag.sh <version>
  scripts/previous-release-tag.sh --self-test
EOF
  exit 2
}

is_stable_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

previous_release_tag() {
  local version="$1" t
  if is_stable_version "$version"; then
    while IFS= read -r t; do
      if [[ "$t" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        printf '%s\n' "$t"
        return 0
      fi
    done < <(git tag --merged HEAD --list 'v*' --sort=-v:refname)
    return 0
  fi
  git describe --tags --abbrev=0 HEAD 2>/dev/null || true
}

self_test() {
  local tmp fail=0 script got
  script="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  git init -q "$tmp"
  git -C "$tmp" config user.email "test@example.com"
  git -C "$tmp" config user.name "test"
  git -C "$tmp" config core.hooksPath /dev/null
  git_commit() {
    git -C "$tmp" -c core.hooksPath=/dev/null commit --allow-empty -qm "$1"
  }
  git_commit init
  git -C "$tmp" tag v0.13.2
  git_commit 'beta 0.14.0'
  git -C "$tmp" tag v0.14.0b1
  git_commit 'stable 0.14.0'
  git -C "$tmp" tag v0.14.0
  git_commit 'beta 0.14.1'
  git -C "$tmp" tag v0.14.1b1
  git_commit 'ready for 0.14.1'

  run_in_repo() {
    GIT_DIR="$tmp/.git" GIT_WORK_TREE="$tmp" bash "$script" "$1"
  }

  got="$(run_in_repo 0.14.1)"
  if [[ "$got" != "v0.14.0" ]]; then
    echo "FAIL stable 0.14.1: got '${got}' want v0.14.0 (must skip v0.14.1b1)" >&2
    fail=1
  fi

  got="$(run_in_repo 0.14.1b2)"
  if [[ "$got" != "v0.14.1b1" ]]; then
    echo "FAIL prerelease 0.14.1b2: got '${got}' want v0.14.1b1" >&2
    fail=1
  fi

  # A repo that has only prereleases: stable summary has no production baseline.
  git init -q "$tmp/prerelease-only"
  git -C "$tmp/prerelease-only" config user.email "test@example.com"
  git -C "$tmp/prerelease-only" config user.name "test"
  git -C "$tmp/prerelease-only" config core.hooksPath /dev/null
  git -C "$tmp/prerelease-only" -c core.hooksPath=/dev/null commit --allow-empty -qm init
  git -C "$tmp/prerelease-only" tag v0.14.0b1
  got="$(GIT_DIR="$tmp/prerelease-only/.git" GIT_WORK_TREE="$tmp/prerelease-only" bash "$script" 0.14.0)"
  if [[ -n "$got" ]]; then
    echo "FAIL stable with no prior stable: got '${got}' want empty" >&2
    fail=1
  fi

  if [[ "$fail" -ne 0 ]]; then
    echo "previous-release-tag self-test FAILED" >&2
    return 1
  fi
  echo "previous-release-tag self-test passed"
}

cmd="${1:-}"
case "$cmd" in
  --self-test)
    self_test
    ;;
  "")
    usage
    ;;
  *)
    previous_release_tag "$cmd"
    ;;
esac
