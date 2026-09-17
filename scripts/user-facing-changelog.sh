#!/usr/bin/env bash
# Filter commit subjects down to user-facing changes, for the Play Store
# "What's new" changelog.
#
# Reads commit subjects on stdin (one per line, as produced by
# `git log --pretty=format:%s`) and writes "- <subject>" lines for the
# user-facing ones. Non-user-facing commits are dropped so the handful of
# salient changes surface within Google Play's 500-byte limit, instead of the
# list being truncated mid-way through CI/dependency noise.
#
# Dropped:
#   - conventional-commit types that never reach users: chore, build, ci,
#     test(s), style, docs, refactor, deps, release, lint
#   - any commit scoped to infrastructure (ci, build, test, deps, gradle,
#     fastlane, signing, workflow, actions), regardless of type
#   - research-edition-only work (subjects mentioning "research")
#   - merge commits
#
# Kept: feat/fix/perf/… unless one of the rules above applies, and any subject
# that is not a conventional commit (usually a human-written one, where
# guessing wrong is worse than showing one extra line).
#
# Usage:
#   git log v0.13.2..HEAD --pretty=format:%s | scripts/user-facing-changelog.sh
#   scripts/user-facing-changelog.sh --self-test

set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  git log <range> --pretty=format:%s | scripts/user-facing-changelog.sh
  scripts/user-facing-changelog.sh --self-test
EOF
  exit 2
}

NON_USER_TYPES='chore|build|ci|test|tests|style|docs|refactor|deps|release|lint'
INFRA_SCOPES='ci|build|test|tests|deps|gradle|fastlane|signing|release|workflow|actions|lint'

is_user_facing() {
  local subject="$1" type scope
  # Research-edition-only work is not part of the public Play listing.
  if [[ "$subject" == *[Rr]esearch* ]]; then
    return 1
  fi
  # Merge commits are noise in a "what's new" list.
  if [[ "$subject" == Merge\ * ]]; then
    return 1
  fi
  # Split "type(scope): subject" (with optional breaking-change bang).
  if [[ "$subject" =~ ^([a-z]+)(\(([^\)]+)\))?!?:[[:space:]] ]]; then
    type="${BASH_REMATCH[1]}"
    scope="${BASH_REMATCH[3]}"
  else
    # Not a conventional commit — keep it.
    return 0
  fi
  if [[ "$type" =~ ^($NON_USER_TYPES)$ ]]; then
    return 1
  fi
  if [[ -n "$scope" && "$scope" =~ ^($INFRA_SCOPES)$ ]]; then
    return 1
  fi
  return 0
}

filter_stream() {
  local subject
  # `git log --pretty=format:` emits no trailing newline, so the last subject
  # must still be processed when `read` hits EOF without a delimiter.
  while IFS= read -r subject || [ -n "$subject" ]; do
    [ -n "$subject" ] || continue
    if is_user_facing "$subject"; then
      printf -- '- %s\n' "$subject"
    fi
  done
}

self_test() {
  local fail=0

  expect() {
    local want="$1" subject="$2" got
    if is_user_facing "$subject"; then got=keep; else got=drop; fi
    if [[ "$got" != "$want" ]]; then
      echo "FAIL: want=$want got=$got subject='${subject}'" >&2
      fail=1
    fi
  }

  # Kept: user-visible behaviour changes.
  expect keep 'fix(android): match system bars to webui theme; drop drawer teal gradient (#276)'
  expect keep 'fix(watcher): construct RustInterface off the calling thread (#277)'
  expect keep 'feat(sync): show what synced and when'
  expect keep 'perf(ui): render the timeline without a full reload'
  expect keep 'fix: re-enable orientation change without reloading the webview (#271)'
  expect keep 'fix(android): harden startup paths that turned a slow datastore into a hang'
  expect keep 'Update README.md'

  # Dropped: conventional-commit types that are not user-facing.
  expect drop 'chore: bump versionName to 0.14.2b1, versionCode to 43'
  expect drop 'build(release): enable R8 minification and publish the mapping file (#270)'
  expect drop 'ci(release): device gate, diff summary, and staged production rollout (#265)'
  expect drop 'ci: switched to using rust-android-gradle for building aw-server-rust'
  expect drop 'test(e2e): wait for the rotated layout before post-rotation inset assertion (#282)'
  expect drop 'tests: add a unit test for the migration'
  expect drop 'docs: add CITATION.cff pointing to ActivityWatch DOI (#279)'
  expect drop 'style: reformat'
  expect drop 'refactor: extract hostname helpers'
  expect drop 'build(deps): bump actions/download-artifact from 3 to 4.1.7'

  # Dropped: infrastructure scopes regardless of type.
  expect drop 'fix(ci): strip -research suffix in versionName assertion; document research builds (#280)'
  expect drop 'fix(test): make the e2e helper deterministic'
  expect drop 'feat(ci): cache the gradle wrapper'

  # Dropped: research-edition-only work.
  expect drop 'feat(mobile): research build flavor with own applicationId and port (#281)'
  expect drop 'fix(research): only show the consent screen once'

  # Dropped: merges.
  expect drop 'Merge pull request #281 from TimeToBuildBob/feat/research-flavor'

  # Stream behaviour: "- " prefixes, drops filtered entries, and — because
  # `git log --pretty=format:` emits no trailing newline — still keeps the
  # final subject.
  local got
  got="$(printf '%s\n%s' 'chore: bump versionName' 'fix(ui): keep the last line' | filter_stream)"
  if [[ "$got" != '- fix(ui): keep the last line' ]]; then
    echo "FAIL stream: got '${got}'" >&2
    fail=1
  fi
  got="$(printf '%s' 'chore: nothing user-facing' | filter_stream)"
  if [[ -n "$got" ]]; then
    echo "FAIL stream all-filtered: got '${got}' want empty" >&2
    fail=1
  fi

  if [[ "$fail" -ne 0 ]]; then
    echo "user-facing-changelog self-test FAILED" >&2
    return 1
  fi
  echo "user-facing-changelog self-test passed"
}

case "${1:-}" in
  --self-test)
    self_test
    ;;
  "")
    filter_stream
    ;;
  *)
    usage
    ;;
esac
