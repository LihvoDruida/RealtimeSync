#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

for rel in ('fabric/build.gradle', 'quilt/build.gradle'):
    text = Path(rel).read_text(encoding='utf-8')
    if "isMinecraft26Plus ? 'net.fabricmc.fabric-loom' : 'net.fabricmc.fabric-loom-remap'" not in text:
        raise SystemExit(f'ERROR: {rel} must switch Loom plugin by Minecraft generation')
    if 'mappings loom.officialMojangMappings()' not in text:
        raise SystemExit(f'ERROR: {rel} must keep official mappings for the 1.21.x branch')
    if 'implementation "net.fabricmc:fabric-loader' not in text or 'implementation "net.fabricmc.fabric-api:fabric-api' not in text:
        raise SystemExit(f'ERROR: {rel} must use implementation dependencies for the 26.x branch')
    marker = "if (isMinecraft26Plus) {\n        // Minecraft 26.x"
    if marker not in text:
        raise SystemExit(f'ERROR: {rel} must document the dedicated 26.x dependency branch')
    branch = text.split(marker, 1)[1].split('} else {', 1)[0]
    branch_without_comments = '\n'.join(line.split('//', 1)[0] for line in branch.splitlines())
    if 'mappings loom.officialMojangMappings()' in branch_without_comments or 'modImplementation' in branch_without_comments:
        raise SystemExit(f'ERROR: {rel} 26.x branch must not use mappings or modImplementation')
print('Fabric/Quilt Loom branch validation passed.')
