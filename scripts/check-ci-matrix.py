#!/usr/bin/env python3
from __future__ import annotations

import json
import sys

matrix = json.loads(sys.stdin.read())
items = matrix.get('include', [])
if not items:
    raise SystemExit('ERROR: generated CI matrix is empty')
for item in items:
    profile = item.get('mc_profile', '')
    loader = item.get('loader', '')
    if not profile.startswith('26.1'):
        raise SystemExit(f'ERROR: mc-26.1.x matrix contains non-26.1.x profile: {profile}')
    if loader not in {'fabric', 'quilt', 'forge', 'neoforge'}:
        raise SystemExit(f'ERROR: unsupported loader in matrix: {loader}')
print(f'CI matrix guard passed for {len(items)} 26.1.x entries.')
