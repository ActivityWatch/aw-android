#!/usr/bin/env python3
"""Refuse to start a stable release while a Play production rollout is staged.

Why this exists
---------------
Google Play keeps at most one in-progress (staged) release per track. Starting
a new staged production release *replaces* the one already in progress, so the
users still on the older completed release are now compared against the new
build and the half-rolled-out one silently drops out of the comparison.

That happened on 2026-10-11: v0.14.2 (versionCode 46) sat at a 50% staged
rollout and was never widened; releasing v0.14.3 at 50% replaced it, and the
staged comparison became 0.14.3 vs 0.14.1 (versionCode 42, at 100%).

Rule: complete (Actions -> Rollout, fraction 1.0) or halt the previous staged
production rollout before starting the next stable release.

How it reads track state
------------------------
Same approach as ActivityWatch/stats' play_tracks.py: the Android Publisher
API (v3) only exposes track state inside an "edit", so this opens one
(edits.insert), reads the production track (edits.tracks.get) and deletes the
edit again (edits.delete). The edit is never committed, so this is a read.

Fail closed: if the API call fails (credentials, permissions, network), the
check fails too. --allow-inprogress turns both a staged rollout and an API
failure into a warning; it exists for the rare case where superseding the
staged release is intended.

It runs twice: in the Release workflow before anything is bumped or tagged,
and in the Build workflow immediately before the production upload (which
also covers hand-pushed tags and two releases racing each other). There it
passes --own-version-code, so re-running a publish whose own staged rollout
already started is not blocked.

Usage:
  scripts/check-production-rollout.py --credentials fastlane/api-....json \
      [--allow-inprogress] [--own-version-code N]
  scripts/check-production-rollout.py --self-test

Requires `google-auth` and `requests` (not needed for --self-test).
"""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass, field

SCOPE = "https://www.googleapis.com/auth/androidpublisher"
BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3"
DEFAULT_PACKAGE = "net.activitywatch.android"
TRACK = "production"

REMEDY = (
    "Complete it first (Actions -> Rollout, fraction 1.0, version_code {code}), "
    "or halt it in Play Console, or - only if superseding it is intended - "
    "re-run Release with allow_inprogress_rollout=true (recorded in the tag "
    "annotation, which the Build workflow's pre-upload check also honours)."
)


@dataclass
class Decision:
    ok: bool
    errors: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def _codes(release: dict) -> str:
    return ",".join(str(c) for c in release.get("versionCodes") or []) or "?"


def _fraction(release: dict) -> str:
    fraction = release.get("userFraction")
    if fraction is None:
        return "unknown"
    return f"{float(fraction):g} ({float(fraction) * 100:g}% of users)"


def decide(track: dict, own_version_code: str | None = None) -> Decision:
    """Pass/fail for the production track JSON returned by edits.tracks.get.

    `inProgress` (a staged rollout) blocks. `completed` is the normal state;
    `halted` is the explicit "stop" the rule asks for; `draft` has not reached
    any users. Those three pass. Any other status (statusUnspecified, missing,
    or new) blocks too: fail closed.

    `own_version_code` is the versionCode about to be uploaded (the Build
    workflow's pre-upload check): a staged rollout *of that same build* is a
    re-run of this publish, not a different release being replaced.
    """
    decision = Decision(ok=True)
    for release in track.get("releases") or []:
        status = release.get("status", "")
        name = release.get("name") or "(unnamed)"
        codes = _codes(release)
        own = own_version_code is not None and own_version_code in [
            str(c) for c in release.get("versionCodes") or []
        ]
        if status == "inProgress" and own:
            decision.notes.append(
                f"versionCode {codes} ('{name}'): in progress, but it is the build being "
                "published (re-run), so nothing is replaced"
            )
        elif status == "inProgress":
            decision.ok = False
            decision.errors.append(
                f"Play {TRACK} track has a staged rollout in progress: "
                f"versionCode {codes}, release '{name}', userFraction {_fraction(release)}. "
                "Starting a new stable release would replace it (Play keeps one "
                "in-progress release per track). " + REMEDY.format(code=codes)
            )
        elif status == "completed":
            decision.notes.append(f"versionCode {codes} ('{name}'): completed")
        elif status == "halted":
            decision.notes.append(
                f"versionCode {codes} ('{name}'): halted (ok; the new release supersedes it)"
            )
        elif status == "draft":
            decision.notes.append(f"versionCode {codes} ('{name}'): draft (not serving users)")
        else:
            # statusUnspecified, a missing status, or a value Play adds later:
            # we can't tell it isn't staged, so fail closed like an API error.
            decision.ok = False
            decision.errors.append(
                f"Play {TRACK} track has versionCode {codes}, release '{name}' with "
                f"unrecognised status '{status or '(missing)'}', userFraction "
                f"{_fraction(release)}; cannot rule out a staged rollout. Check it in "
                "Play Console. " + REMEDY.format(code=codes)
            )
    if not decision.notes and decision.ok:
        decision.notes.append(f"no releases on the {TRACK} track")
    return decision


def fetch_track(package: str, credentials: str) -> dict:
    """Authenticate with the service-account key, then read the track."""
    import requests
    from google.auth.transport.requests import Request
    from google.oauth2 import service_account

    creds = service_account.Credentials.from_service_account_file(credentials, scopes=[SCOPE])
    creds.refresh(Request())
    return read_track(requests, creds.token, package)


def read_track(http, token: str, package: str) -> dict:
    """edits.insert -> edits.tracks.get(production) -> edits.delete.

    `http` is the `requests` module (or a stand-in with post/get/delete, for
    the self-test).
    """
    headers = {"Authorization": f"Bearer {token}"}

    r = http.post(f"{BASE}/applications/{package}/edits", headers=headers, timeout=30)
    if r.status_code >= 400:
        raise RuntimeError(f"edits.insert failed: HTTP {r.status_code}: {r.text[:300]}")
    edit_id = r.json()["id"]
    try:
        r = http.get(
            f"{BASE}/applications/{package}/edits/{edit_id}/tracks/{TRACK}",
            headers=headers,
            timeout=30,
        )
        if r.status_code >= 400:
            raise RuntimeError(f"edits.tracks.get failed: HTTP {r.status_code}: {r.text[:300]}")
        return r.json()
    finally:
        # Best-effort: an abandoned edit expires on its own, and a failed
        # cleanup must not mask the track state we just read.
        try:
            http.delete(
                f"{BASE}/applications/{package}/edits/{edit_id}", headers=headers, timeout=30
            )
        except Exception:
            pass


def report(decision: Decision, allow_inprogress: bool) -> tuple[int, list[str]]:
    """Exit code and the lines to print (GitHub annotations) for a decision."""
    lines = [f"Play {TRACK} track: {note}" for note in decision.notes]
    if decision.ok:
        lines.append(f"ok: no other staged {TRACK} rollout in progress")
        return 0, lines
    if allow_inprogress:
        lines += [f"::warning::allow_inprogress_rollout=true - superseding. {e}" for e in decision.errors]
        return 0, lines
    lines += [f"::error::{e}" for e in decision.errors]
    return 1, lines


def report_failure(exc: BaseException, allow_inprogress: bool) -> tuple[int, list[str]]:
    msg = (
        f"Could not read the Play {TRACK} track ({type(exc).__name__}: {exc}). "
        "Refusing to start a stable release without knowing whether a staged rollout "
        "is in progress. Check the KEY_FASTLANE_API_B64 secret and the service account's "
        "Play Console permissions, or re-run with allow_inprogress_rollout=true."
    )
    if allow_inprogress:
        return 0, [f"::warning::allow_inprogress_rollout=true - skipping the check. {msg}"]
    return 1, [f"::error::{msg}"]


def self_test() -> int:
    failures: list[str] = []

    def expect(name: str, track: dict, want_code: int, *, allow: bool = False,
               must_contain: tuple[str, ...] = ()) -> None:
        code, lines = report(decide(track), allow)
        text = "\n".join(lines)
        if code != want_code:
            failures.append(f"{name}: exit {code}, want {want_code}\n{text}")
        for needle in must_contain:
            if needle not in text:
                failures.append(f"{name}: output lacks {needle!r}\n{text}")

    staged = {
        "track": "production",
        "releases": [
            {"name": "0.14.2", "versionCodes": ["46"], "status": "inProgress", "userFraction": 0.5},
            {"name": "0.14.1", "versionCodes": ["42"], "status": "completed"},
        ],
    }
    expect("inProgress blocks", staged, 1, must_contain=(
        "::error::", "versionCode 46", "'0.14.2'", "0.5 (50% of users)",
        "Rollout", "1.0", "halt", "allow_inprogress_rollout=true",
    ))
    expect("inProgress + override warns", staged, 0, allow=True,
           must_contain=("::warning::", "versionCode 46"))
    expect("inProgress, no fraction", {"releases": [
        {"name": "0.14.2", "versionCodes": ["46"], "status": "inProgress"}]}, 1,
        must_contain=("userFraction unknown",))
    expect("completed passes", {"releases": [
        {"name": "0.14.1", "versionCodes": ["42"], "status": "completed"}]}, 0,
        must_contain=("ok:", "completed"))
    expect("halted passes", {"releases": [
        {"name": "0.14.2", "versionCodes": ["46"], "status": "halted", "userFraction": 0.5},
        {"name": "0.14.1", "versionCodes": ["42"], "status": "completed"}]}, 0,
        must_contain=("halted",))
    expect("draft passes", {"releases": [
        {"name": "0.14.3", "versionCodes": ["48"], "status": "draft"},
        {"name": "0.14.1", "versionCodes": ["42"], "status": "completed"}]}, 0,
        must_contain=("draft",))
    for odd in ("statusUnspecified", "", "somethingNew"):
        rel = {"name": "0.14.2", "versionCodes": ["46"], "userFraction": 0.5}
        if odd:
            rel["status"] = odd
        expect(f"status {odd or '(missing)'} fails closed", {"releases": [rel]}, 1,
               must_contain=("::error::", "versionCode 46", "unrecognised status"))
        expect(f"status {odd or '(missing)'} + override warns", {"releases": [rel]}, 0,
               allow=True, must_contain=("::warning::",))
    expect("empty track passes", {"track": "production"}, 0, must_contain=("no releases",))
    expect("override is a no-op when clean", {"releases": [
        {"name": "0.14.1", "versionCodes": ["42"], "status": "completed"}]}, 0, allow=True)

    code, lines = report_failure(RuntimeError("edits.insert failed: HTTP 403"), False)
    if code != 1 or not lines[0].startswith("::error::") or "HTTP 403" not in lines[0]:
        failures.append(f"API failure must fail closed: {code} {lines}")
    code, lines = report_failure(RuntimeError("timeout"), True)
    if code != 0 or not lines[0].startswith("::warning::"):
        failures.append(f"API failure + override must warn: {code} {lines}")

    own = {"releases": [
        {"name": "0.14.3", "versionCodes": ["48"], "status": "inProgress", "userFraction": 0.1},
        {"name": "0.14.1", "versionCodes": ["42"], "status": "completed"}]}
    code, lines = report(decide(own, own_version_code="48"), False)
    if code != 0:
        failures.append(f"own in-progress build (re-run) must pass: {lines}")
    code, lines = report(decide(own, own_version_code="49"), False)
    if code != 1 or "versionCode 48" not in "\n".join(lines):
        failures.append(f"another build's in-progress rollout must block: {lines}")

    # The API path, with a stand-in for `requests`: call sequence, the edit is
    # always deleted, and HTTP errors reach the fail-closed path in main().
    class FakeResponse:
        def __init__(self, status_code: int, payload: dict) -> None:
            self.status_code, self._payload, self.text = status_code, payload, str(payload)

        def json(self) -> dict:
            return self._payload

    class FakeHttp:
        def __init__(self, get_status: int, track: dict) -> None:
            self.calls: list[tuple[str, str]] = []
            self.get_status, self.track = get_status, track

        def post(self, url, **kw):
            self.calls.append(("POST", url))
            return FakeResponse(200, {"id": "E1"})

        def get(self, url, **kw):
            self.calls.append(("GET", url))
            return FakeResponse(self.get_status, self.track)

        def delete(self, url, **kw):
            self.calls.append(("DELETE", url))
            return FakeResponse(204, {})

    app = f"{BASE}/applications/{DEFAULT_PACKAGE}"
    want_calls = [("POST", f"{app}/edits"), ("GET", f"{app}/edits/E1/tracks/{TRACK}"),
                  ("DELETE", f"{app}/edits/E1")]
    for get_status, want_code in ((200, 1), (403, 1)):
        http = FakeHttp(get_status, staged)
        fetch = lambda package, credentials, http=http: read_track(http, "t", package)
        code = main(["--credentials", "unused.json"], fetch=fetch, quiet=True)
        if http.calls != want_calls:
            failures.append(f"GET {get_status}: calls {http.calls}, want {want_calls}")
        if code != want_code:
            failures.append(f"GET {get_status}: main() exit {code}, want {want_code}")
    http = FakeHttp(200, staged)
    fetch = lambda package, credentials: read_track(http, "t", package)
    if main(["--credentials", "x", "--allow-inprogress"], fetch=fetch, quiet=True) != 0:
        failures.append("main() with --allow-inprogress must pass")
    if main(["--credentials", "x", "--own-version-code", "46"], fetch=fetch, quiet=True) != 0:
        failures.append("main() with --own-version-code of the staged build must pass")

    for f in failures:
        print(f"FAIL {f}", file=sys.stderr)
    if failures:
        print("check-production-rollout self-test FAILED", file=sys.stderr)
        return 1
    print("check-production-rollout self-test passed")
    return 0


def main(argv: list[str], fetch=fetch_track, quiet: bool = False) -> int:
    if argv == ["--self-test"]:
        return self_test()
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--credentials", required=True, help="service-account JSON key")
    p.add_argument("--package", default=DEFAULT_PACKAGE)
    p.add_argument("--allow-inprogress", action="store_true",
                   help="warn instead of failing on a staged rollout or an API error")
    p.add_argument("--own-version-code",
                   help="versionCode being published; its own staged rollout does not block")
    args = p.parse_args(argv)

    try:
        track = fetch(args.package, args.credentials)
    except Exception as exc:  # fail closed on anything: auth, network, JSON
        code, lines = report_failure(exc, args.allow_inprogress)
    else:
        code, lines = report(decide(track, args.own_version_code), args.allow_inprogress)

    if quiet:
        return code
    for line in lines:
        print(line)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(f"### Play {TRACK} rollout check\n")
            for line in lines:
                f.write(f"- {line.removeprefix('::error::').removeprefix('::warning::')}\n")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
