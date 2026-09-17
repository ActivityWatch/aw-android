#!/usr/bin/env bash
# Strip a trailing -research suffix from a version string (case-insensitive).
#
# The -research suffix is a distribution-channel marker (GitHub-release-only
# study build), not a version component — see scripts/assert-play-track.sh and
# the "Research builds" section of README.md. Used by the Build workflow's
# versionName assertion, which normalizes BOTH sides of the comparison so the
# check passes regardless of whether the suffix came from the tag, the
# committed versionName (Release workflow path), or both.
#
# Usage:
#   scripts/strip-research-suffix.sh <version>     e.g. 0.14.2b1-research → 0.14.2b1
#   scripts/strip-research-suffix.sh --self-test

set -euo pipefail

strip_research_suffix() {
  # Only a trailing suffix is stripped (matches assert-play-track.sh's
  # case-insensitive glob on the tag shape).
  printf '%s' "${1%-[rR][eE][sS][eE][aA][rR][cC][hH]}"
}

self_test() {
  local failures=0
  check() {
    local got
    got="$(strip_research_suffix "$1")"
    if [ "$got" = "$2" ]; then
      echo "ok: '$1' -> '$got'"
    else
      echo "FAIL: '$1' -> '$got' (expected '$2')"
      failures=$((failures + 1))
    fi
  }
  # Trailing suffix, all case variants
  check 0.14.2b1-research 0.14.2b1
  check 0.14.2b1-Research 0.14.2b1
  check 0.14.2b1-RESEARCH 0.14.2b1
  check 0.14.2b1-ReSeArCh 0.14.2b1
  # No suffix: unchanged
  check 0.14.2b1 0.14.2b1
  check 0.14.0 0.14.0
  check 0.14.0dev20260712 0.14.0dev20260712
  # Only a TRAILING suffix is stripped: -research elsewhere is kept
  check 0.14.2-researchb1 0.14.2-researchb1
  # Tag with v prefix: suffix still stripped (caller strips v first)
  check v0.14.2b1-research v0.14.2b1

  if [ "$failures" -gt 0 ]; then
    echo "self-test FAILED: $failures case(s)"
    exit 1
  fi
  echo "self-test passed"
}

case "${1:-}" in
  --self-test) self_test ;;
  "") echo "usage: $0 <version> | --self-test" >&2; exit 2 ;;
  *) strip_research_suffix "$1"; echo ;;
esac
