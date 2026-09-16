#!/usr/bin/env bash
# Resolve or assert the Play Store supply track for an aw-android release tag.
#
# Contract:
#   X.Y.Z (no suffix)         → production
#   X.Y.Z-research (any form) → none      (GitHub release only; Play publish skipped)
#   anything else             → internal  (0.14.0b2, 0.14.0devYYYYMMDD, 0.14.0-rc1, …)
#
# Fail closed: a pre-release tag must never publish to production, even if
# SUPPLY_TRACK is later hardcoded or the resolver regresses. Stable tags may
# still be sent to internal (staged rollout); that is not this guard.
# Research tags must NEVER reach Play at all — they are GitHub-only study builds.
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
  scripts/assert-play-track.sh rollout <tag> <track>
  scripts/assert-play-track.sh --self-test
EOF
  exit 2
}

is_stable_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

is_research_tag() {
  # A -research suffix (any case) marks a study/research build: GitHub release
  # only, never Play. Case-insensitive so a tag like v1.2.3-Research cannot
  # fall through to the internal Play track.
  [[ "$1" == *-[rR][eE][sS][eE][aA][rR][cC][hH]* ]]
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
  if is_research_tag "$version"; then
    echo none   # GitHub-only; Play publish must be skipped by the caller
  elif is_stable_version "$version"; then
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
  if [[ "$track" != production && "$track" != internal && "$track" != none ]]; then
    echo "error: unknown SUPPLY_TRACK=${track} for ${tag}" >&2
    return 1
  fi
  if [[ "$track" == none ]]; then
    if ! is_research_tag "$version"; then
      echo "error: SUPPLY_TRACK=none is only valid for -research tags (tag=${tag})" >&2
      return 1
    fi
    echo "ok: tag=${tag} version=${version} SUPPLY_TRACK=none (research build; Play publish skipped)"
    return 0
  fi
  if is_research_tag "$version"; then
    echo "error: research tag ${tag} must use SUPPLY_TRACK=none; refusing to publish to Play (track=${track})" >&2
    return 1
  fi
  if [[ "$track" == production ]] && ! is_stable_version "$version"; then
    echo "error: refusing to publish pre-release tag ${tag} to production (SUPPLY_TRACK=${track})" >&2
    return 1
  fi
  echo "ok: tag=${tag} version=${version} SUPPLY_TRACK=${track}"
}

DEFAULT_ROLLOUT=0.1
ALLOWED_ROLLOUTS="0.1 0.25 0.5 1.0"

tag_message() {
  # ROLLOUT_TAG_MESSAGE overrides the git lookup (self-test, dry runs).
  if [[ -n "${ROLLOUT_TAG_MESSAGE+x}" ]]; then
    printf '%s\n' "$ROLLOUT_TAG_MESSAGE"
  else
    git for-each-ref "refs/tags/$1" --format='%(contents)' 2>/dev/null || true
  fi
}

resolve_rollout() {
  local tag="$1" track="$2" line fraction
  if [[ "$track" != production ]]; then
    return 0
  fi
  line="$(tag_message "$tag" | grep -E '^rollout=' | head -n1 || true)"
  fraction="${line#rollout=}"
  if [[ -z "$fraction" ]]; then
    fraction="$DEFAULT_ROLLOUT"
  fi
  for allowed in $ALLOWED_ROLLOUTS; do
    if [[ "$fraction" == "$allowed" ]]; then
      echo "$fraction"
      return 0
    fi
  done
  echo "error: unsupported rollout fraction '${fraction}' in tag ${tag} (allowed: ${ALLOWED_ROLLOUTS})" >&2
  return 1
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
  # research tags → none (GitHub-only; Play publish must be skipped)
  expect_resolve v0.14.2b1-research none
  expect_resolve v0.14.2-research none
  expect_resolve 0.14.2b1-research none
  expect_resolve v0.14.2b1-Research none   # case-insensitive
  expect_resolve v0.14.2-RESEARCH none     # case-insensitive
  expect_resolve v0.14.2b1 internal    # confirm plain prerelease still → internal

  expect_assert_ok v0.14.0 production
  expect_assert_ok v0.14.0 internal
  expect_assert_ok v0.14.0b2 internal
  expect_assert_fail v0.14.0b2 production
  expect_assert_fail v0.14.0dev20260723 production
  expect_assert_fail v0.14.0beta2 production
  expect_assert_fail v0.14.0 ""
  expect_assert_fail v0.14.0 alpha
  expect_assert_fail "" production
  # research tags: only none is valid; production/internal must be rejected
  expect_assert_ok v0.14.2b1-research none
  expect_assert_ok v0.14.2-research none
  expect_assert_ok v0.14.2b1-Research none
  expect_assert_fail v0.14.2b1-Research internal   # case variants must also never reach Play
  expect_assert_fail v0.14.2b1-research internal   # research → Play (any track) is wrong
  expect_assert_fail v0.14.2b1-research production
  expect_assert_fail v0.14.2b1 none               # none is only for research tags

  expect_rollout() {
    local msg="$1" tag="$2" track="$3" want="$4" got
    got="$(ROLLOUT_TAG_MESSAGE="$msg" resolve_rollout "$tag" "$track" 2>/dev/null || echo FAIL)"
    if [[ "$got" != "$want" ]]; then
      echo "FAIL rollout msg='${msg}' tag=${tag} track=${track}: got '${got}' want '${want}'" >&2
      fail=1
    fi
  }
  expect_rollout $'Release v0.14.1\n\nrollout=0.1' v0.14.1 production 0.1
  expect_rollout $'Release v0.14.1\n\nrollout=1.0' v0.14.1 production 1.0
  expect_rollout "Release v0.14.1" v0.14.1 production 0.1        # hand-pushed tag: default
  expect_rollout "" v0.14.1 production 0.1                        # lightweight tag: default
  expect_rollout $'rollout=0.1' v0.14.1b1 internal ""             # no staged rollout on internal
  expect_rollout $'rollout=0.37' v0.14.1 production FAIL          # unknown fraction: refuse
  expect_rollout $'rollout=' v0.14.1 production 0.1               # empty value: default

  # Publication path: read an annotated tag via `git for-each-ref`, not the
  # ROLLOUT_TAG_MESSAGE override the cases above use.
  expect_git_rollout() {
    local msg="$1" want="$2" tmpdir got
    tmpdir="$(mktemp -d)"
    git init -q "$tmpdir"
    git -C "$tmpdir" config user.email "test@example.com"
    git -C "$tmpdir" config user.name "test"
    git -C "$tmpdir" config core.hooksPath /dev/null
    git -C "$tmpdir" -c core.hooksPath=/dev/null commit --allow-empty -qm init
    git -C "$tmpdir" tag -a v0.14.1 -m "$msg"
    got="$(
      env -u ROLLOUT_TAG_MESSAGE GIT_DIR="$tmpdir/.git" GIT_WORK_TREE="$tmpdir" \
        bash "$0" rollout v0.14.1 production 2>/dev/null || echo FAIL
    )"
    rm -rf "$tmpdir"
    if [[ "$got" != "$want" ]]; then
      echo "FAIL git-backed rollout msg='${msg}': got '${got}' want '${want}'" >&2
      fail=1
    fi
  }
  expect_git_rollout $'Release v0.14.1\n\nrollout=0.25' 0.25
  expect_git_rollout "Release v0.14.1" 0.1

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
  rollout)
    [[ $# -eq 3 ]] || usage
    resolve_rollout "$2" "$3"
    ;;
  --self-test)
    self_test
    ;;
  *)
    usage
    ;;
esac
