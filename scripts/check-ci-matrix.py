#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

matrix = json.loads(Path('/tmp/realtime-sync-matrix.json').read_text(encoding='utf-8'))
items = matrix.get('include', [])
if not items:
    raise SystemExit('ERROR: generated CI matrix is empty')
if {'mc_profile': '1.21.2', 'loader': 'forge'} in items:
    raise SystemExit('ERROR: generated CI matrix must not include unsupported 1.21.2 Forge')
for item in items:
    if set(item) != {'mc_profile', 'loader'}:
        raise SystemExit(f'ERROR: invalid matrix item: {item}')
print(f'Generated CI matrix contains {len(items)} supported build entries.')
