#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

for rel in ('fabric/build.gradle', 'quilt/build.gradle'):
    text = Path(rel).read_text(encoding='utf-8')
    if "apply plugin: 'net.fabricmc.fabric-loom'" not in text:
        raise SystemExit(f'ERROR: {rel} must use Fabric Loom for the 26.1.x branch')
    forbidden = (
        'net.fabricmc.fabric-loom-remap',
        'mappings loom.officialMojangMappings()',
        'modImplementation "net.fabricmc:fabric-loader',
        'modImplementation "net.fabricmc.fabric-api:fabric-api',
        'isMinecraft26Plus',
    )
    without_comments = '\n'.join(line.split('//', 1)[0] for line in text.splitlines())
    for marker in forbidden:
        if marker in without_comments:
            raise SystemExit(f'ERROR: {rel} contains remap-only marker that is invalid on 26.1.x: {marker}')
    if 'implementation "net.fabricmc:fabric-loader' not in text or 'implementation "net.fabricmc.fabric-api:fabric-api' not in text:
        raise SystemExit(f'ERROR: {rel} must use implementation for Fabric Loader and Fabric API on 26.1.x')
print('Fabric/Quilt 26.1.x Loom validation passed.')
