#!/usr/bin/env bash
# Resolve or assert the Play Store supply track for an aw-android release tag.
#
# Contract:
#   X.Y.Z (no suffix)  → production
#   anything else      → internal  (0.14.0b2, 0.14.0devYYYYMMDD, 0.14.0-rc1, …)
#
# Fail closed: a pre-release tag must never publish to production, even if
# SUPPLY_TRACK is later hardcoded or the resolver regresses. Stable tags may
# still be sent to internal (staged rollout); that is not this guard.
#
# Usage:
#   scripts/assert-play-track.sh resolve <tag>
#   scripts/assert-play-track.sh assert  <tag> <track>
#   scripts/assert-play-track.sh --self-test

set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  scripts/assert-play-track.sh resolve <tag>
  scripts/assert-play-track.sh assert  <tag> <track>
  scripts/assert-play-track.sh --self-test
EOF
  exit 2
}

is_stable_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

tag_to_version() {
  local tag="$1"
  if [[ -z "$tag" ]]; then
    echo "error: empty tag" >&2
    return 1
  fi
  echo "${tag#v}"
}

resolve_track() {
  local version
  version="$(tag_to_version "$1")"
  if is_stable_version "$version"; then
    echo production
  else
    echo internal
  fi
}

assert_track() {
  local tag="$1"
  local track="$2"
  local version
  version="$(tag_to_version "$tag")"

  if [[ -z "$track" ]]; then
    echo "error: SUPPLY_TRACK is empty; refusing to publish ${tag}" >&2
    return 1
  fi
  if [[ "$track" != production && "$track" != internal ]]; then
    echo "error: unknown SUPPLY_TRACK=${track} for ${tag}" >&2
    return 1
  fi
  if [[ "$track" == production ]] && ! is_stable_version "$version"; then
    echo "error: refusing to publish pre-release tag ${tag} to production (SUPPLY_TRACK=${track})" >&2
    return 1
  fi
  echo "ok: tag=${tag} version=${version} SUPPLY_TRACK=${track}"
}

self_test() {
  local fail=0
  expect_resolve() {
    local tag="$1" want="$2" got
    got="$(resolve_track "$tag")"
    if [[ "$got" != "$want" ]]; then
      echo "FAIL resolve ${tag}: got ${got} want ${want}" >&2
      fail=1
    fi
  }
  expect_assert_ok() {
    if ! assert_track "$1" "$2" >/dev/null; then
      echo "FAIL assert should pass: tag=$1 track=$2" >&2
      fail=1
    fi
  }
  expect_assert_fail() {
    if assert_track "$1" "$2" >/dev/null 2>&1; then
      echo "FAIL assert should fail: tag=$1 track=$2" >&2
      fail=1
    fi
  }

  expect_resolve v0.14.0 production
  expect_resolve 0.14.0 production
  expect_resolve v1.0.0 production
  expect_resolve v0.14.0b2 internal
  expect_resolve v0.14.0beta2 internal
  expect_resolve v0.14.0dev20260723 internal
  expect_resolve v0.14.0-rc1 internal
  expect_resolve v0.14.0rc1 internal
  expect_resolve v0.14 internal

  expect_assert_ok v0.14.0 production
  expect_assert_ok v0.14.0 internal
  expect_assert_ok v0.14.0b2 internal
  expect_assert_fail v0.14.0b2 production
  expect_assert_fail v0.14.0dev20260723 production
  expect_assert_fail v0.14.0beta2 production
  expect_assert_fail v0.14.0 ""
  expect_assert_fail v0.14.0 alpha
  expect_assert_fail "" production

  if [[ "$fail" -ne 0 ]]; then
    echo "assert-play-track self-test FAILED" >&2
    return 1
  fi
  echo "assert-play-track self-test passed"
}

cmd="${1:-}"
case "$cmd" in
  resolve)
    [[ $# -eq 2 ]] || usage
    resolve_track "$2"
    ;;
  assert)
    [[ $# -eq 3 ]] || usage
    assert_track "$2" "$3"
    ;;
  --self-test)
    self_test
    ;;
  *)
    usage
    ;;
esac
