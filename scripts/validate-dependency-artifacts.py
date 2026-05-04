#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "config/build-compatibility.lock.json"


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def artifact_url(group: str, artifact: str, version: str, classifier: str | None = None, ext: str = "pom", repo: str = "") -> str:
    path = "/".join(group.split("."))
    filename = f"{artifact}-{version}"
    if classifier:
        filename += f"-{classifier}"
    filename += f".{ext}"
    return f"{repo.rstrip('/')}/{path}/{artifact}/{quote(version, safe='+.-')}/{quote(filename, safe='+.-')}"


def urls_for(profile: str, loader: str, entry: dict) -> list[str]:
    profile_entry = entry["profiles"][profile]
    loader_entry = profile_entry["loaders"][loader]
    urls: list[str] = []

    if loader in {"fabric", "quilt"}:
        fabric_loader = profile_entry["fabricLoader"]
        fabric_api = profile_entry["fabricApi"]
        urls.append(artifact_url("net.fabricmc", "fabric-loader", fabric_loader, repo="https://maven.fabricmc.net"))
        urls.append(artifact_url("net.fabricmc.fabric-api", "fabric-api", fabric_api, repo="https://maven.fabricmc.net"))
    elif loader == "forge":
        if not loader_entry.get("supported"):
            return urls
        mc = profile_entry["minecraftVersion"]
        forge_version = loader_entry["version"]
        version = f"{mc}-{forge_version}"
        urls.append(artifact_url("net.minecraftforge", "forge", version, classifier="userdev", ext="jar", repo="https://maven.minecraftforge.net"))
    elif loader == "neoforge":
        if not loader_entry.get("supported"):
            return urls
        neoforge_version = loader_entry["version"]
        urls.append(artifact_url("net.neoforged", "neoforge", neoforge_version, classifier="userdev", ext="jar", repo="https://maven.neoforged.net/releases"))

    return urls


def check_url(url: str, attempts: int = 3, timeout: int = 20) -> None:
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, method="HEAD")
            with urllib.request.urlopen(request, timeout=timeout) as response:
                if 200 <= response.status < 400:
                    return
        except urllib.error.HTTPError as exc:
            # Some Maven endpoints reject HEAD. Retry with GET for those only.
            if exc.code in {403, 405}:
                try:
                    request = urllib.request.Request(url, method="GET")
                    with urllib.request.urlopen(request, timeout=timeout) as response:
                        if 200 <= response.status < 400:
                            return
                except Exception as get_exc:  # noqa: BLE001 - CLI probe should show final failure.
                    last_error = get_exc
            else:
                last_error = exc
        except Exception as exc:  # noqa: BLE001
            last_error = exc

        if attempt < attempts:
            time.sleep(2 * attempt)

    fail(f"Dependency artifact is not reachable: {url} ({last_error})")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate that compatibility-lock dependency artifacts exist.")
    parser.add_argument("--online", action="store_true", help="Perform network checks against Maven repositories.")
    parser.add_argument("--profile", action="append", help="Optional profile filter.")
    parser.add_argument("--loader", action="append", choices=["fabric", "quilt", "forge", "neoforge"], help="Optional loader filter.")
    args = parser.parse_args()

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    profiles = args.profile or lock["profileOrder"]
    loaders = args.loader or lock["loaders"]

    checked = 0
    for profile in profiles:
        if profile not in lock["profiles"]:
            fail(f"Unknown profile in compatibility lock: {profile}")
        for loader in loaders:
            loader_entry = lock["profiles"][profile]["loaders"][loader]
            if not loader_entry.get("supported"):
                continue
            for url in urls_for(profile, loader, lock):
                checked += 1
                if args.online:
                    check_url(url)

    mode = "online" if args.online else "offline"
    print(f"Dependency artifact validation passed in {mode} mode. Checked {checked} artifact coordinate(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
