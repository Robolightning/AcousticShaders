#!/usr/bin/env python3
"""Fail if ProjectileFlightSound links directly to MCP-only inherited sound fields."""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: verify-projectile-moving-sound-linkage.py <javap-v.txt>')
text = Path(sys.argv[1]).read_text(errors='replace')
forbidden = [
    'repeat', 'repeatDelay', 'attenuationType', 'donePlaying',
    'xPosF', 'yPosF', 'zPosF', 'volume', 'pitch',
]
hits = []
for line in text.splitlines():
    if 'Fieldref' not in line and '// Field ' not in line:
        continue
    for name in forbidden:
        if re.search(rf'\.{re.escape(name)}:', line):
            hits.append(line.strip())
if hits:
    raise SystemExit('ERROR: projectile class contains MCP-only inherited Fieldref(s):\n' + '\n'.join(hits))
required = [
    'field_147659_g', 'field_147665_h', 'field_147666_i', 'field_147668_j',
    'field_147660_d', 'field_147661_e', 'field_147658_f', 'field_147662_b', 'field_147663_c',
]
missing = [name for name in required if name not in text]
if missing:
    raise SystemExit('ERROR: projectile class is missing SRG reflection literal(s): ' + ', '.join(missing))
if 'ForgeReflection.setField' not in text and 'ForgeReflection.setField:' not in text:
    raise SystemExit('ERROR: projectile class does not link ForgeReflection.setField')
print('[PASS] projectile inherited MovingSound fields are reflection-bridged; no MCP-only Fieldref leakage')
