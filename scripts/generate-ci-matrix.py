#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "config/build-compatibility.lock.json"


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def load_lock() -> dict:
    if not LOCK_PATH.is_file():
        fail(f"Missing compatibility lock: {LOCK_PATH}")
    return json.loads(LOCK_PATH.read_text(encoding="utf-8"))


def read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{path}: invalid property line: {raw}")
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate the supported RealtimeSync CI build matrix from the compatibility lock.")
    parser.add_argument("--profile", action="append", help="Optional profile filter. Can be provided multiple times.")
    parser.add_argument("--loader", action="append", choices=["fabric", "quilt", "forge", "neoforge"], help="Optional loader filter. Can be provided multiple times.")
    parser.add_argument("--github-output", action="store_true", help="Write build_matrix and has_entries to $GITHUB_OUTPUT.")
    args = parser.parse_args()

    lock = load_lock()
    profile_filter = set(args.profile or [])
    loader_filter = set(args.loader or [])
    include: list[dict[str, str]] = []

    for profile in lock.get("profileOrder", []):
        if profile_filter and profile not in profile_filter:
            continue
        profile_entry = lock["profiles"].get(profile)
        if not profile_entry:
            fail(f"Lock profileOrder references missing profile: {profile}")

        profile_props = read_properties(ROOT / "buildProfiles" / f"{profile}.properties")
        for loader in lock.get("loaders", []):
            if loader_filter and loader not in loader_filter:
                continue

            locked_supported = bool(profile_entry["loaders"][loader].get("supported"))
            profile_enabled = profile_props.get(f"enable_{loader}") == "true"
            if locked_supported != profile_enabled:
                fail(f"{profile} {loader}: compatibility lock supported={locked_supported} disagrees with enable_{loader}={profile_props.get(f'enable_{loader}')}")
            if not locked_supported:
                continue

            include.append({"mc_profile": profile, "loader": loader})

    matrix = {"include": include}
    payload = json.dumps(matrix, separators=(",", ":"))
    has_entries = "true" if include else "false"

    if args.github_output:
        output_path = os.environ.get("GITHUB_OUTPUT")
        if not output_path:
            fail("--github-output was requested but GITHUB_OUTPUT is not set")
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(f"build_matrix={payload}\n")
            output.write(f"has_entries={has_entries}\n")

    print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
