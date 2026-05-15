#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

for rel in ('fabric/build.gradle', 'quilt/build.gradle'):
    text = Path(rel).read_text(encoding='utf-8')
    if "apply plugin: 'net.fabricmc.fabric-loom-remap'" not in text:
        raise SystemExit(f'ERROR: {rel} must use Fabric Loom Remap for the 1.21.x branch')
    if 'mappings loom.officialMojangMappings()' not in text:
        raise SystemExit(f'ERROR: {rel} must keep official Mojang mappings for 1.21.x')
    if 'modImplementation "net.fabricmc:fabric-loader' not in text or 'modImplementation "net.fabricmc.fabric-api:fabric-api' not in text:
        raise SystemExit(f'ERROR: {rel} must use modImplementation for Fabric Loader and Fabric API on 1.21.x')
    forbidden = ('isMinecraft26Plus', "apply plugin: isMinecraft26Plus", 'implementation "net.fabricmc:fabric-loader', 'implementation "net.fabricmc.fabric-api:fabric-api')
    for marker in forbidden:
        if marker in text:
            raise SystemExit(f'ERROR: {rel} still contains obsolete 26.x Fabric/Loom branch marker: {marker}')
print('Fabric/Quilt 1.21.x Loom validation passed.')
