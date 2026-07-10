#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate a generated RealtimeSync CI matrix JSON file.")
    parser.add_argument("matrix", type=Path, help="Path produced by generate-ci-matrix.py")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.matrix.is_file():
        raise SystemExit(f"ERROR: matrix file does not exist: {args.matrix}")
    matrix = json.loads(args.matrix.read_text(encoding="utf-8"))
    items = matrix.get("include", [])
    if not items:
        raise SystemExit("ERROR: generated CI matrix is empty")
    if {"mc_profile": "1.21.2", "loader": "forge"} in items:
        raise SystemExit("ERROR: generated CI matrix must not include unsupported 1.21.2 Forge")
    for item in items:
        if set(item) != {"mc_profile", "loader"}:
            raise SystemExit(f"ERROR: invalid matrix item: {item}")
    print(f"Generated CI matrix contains {len(items)} supported build entries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
