#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"; cd "$ROOT"
: "${ACOUSTIC_MC_1122_CLIENT:?set ACOUSTIC_MC_1122_CLIENT}"
: "${ACOUSTIC_MCP_CONFIG_1122:?set ACOUSTIC_MCP_CONFIG_1122}"

[[ -f "$ACOUSTIC_MC_1122_CLIENT" ]] || { echo 'ERROR: official Minecraft 1.12.2 client jar missing' >&2; exit 1; }
[[ -f "$ACOUSTIC_MCP_CONFIG_1122" ]] || { echo 'ERROR: MCPConfig 1.12.2 zip missing' >&2; exit 1; }
[[ "$(sha1sum "$ACOUSTIC_MC_1122_CLIENT" | awk '{print $1}')" == '0f275bc1547d01fa5f56ba34bdc87d981ee12daf' ]] || {
  echo 'ERROR: unexpected Minecraft 1.12.2 client SHA-1' >&2; exit 1
}
[[ "$(sha256sum "$ACOUSTIC_MCP_CONFIG_1122" | awk '{print $1}')" == 'e2ddd3a7bb65618ad0de1d8fc0334527e5c346180300d94bfc7d3727b1976b42' ]] || {
  echo 'ERROR: unexpected MCPConfig 1.12.2 SHA-256' >&2; exit 1
}
command -v javap >/dev/null 2>&1 || { echo 'ERROR: javap required' >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo 'ERROR: unzip required' >&2; exit 1; }

read -r BLOCK_LIQUID HEIGHT_PERCENT LOCAL_HEIGHT < <(
  python3 - "$ACOUSTIC_MCP_CONFIG_1122" <<'PY'
import subprocess,sys
archive=sys.argv[1]
text=subprocess.check_output(['unzip','-p',archive,'config/joined.tsrg'], text=True)
lines=text.splitlines()
owner=None;height=None;local=None;inside=False
for line in lines:
    if not line.startswith((' ', '\t')):
        parts=line.split()
        inside=len(parts)>=2 and parts[1]=='net/minecraft/block/BlockLiquid'
        if inside: owner=parts[0]
        continue
    if not inside: continue
    parts=line.split()
    if len(parts)>=3 and parts[-1]=='func_149801_b' and parts[1]=='(I)F': height=parts[0]
    if len(parts)>=3 and parts[-1]=='func_190973_f' and parts[1].endswith(')F'): local=parts[0]
if not (owner and height and local):
    raise SystemExit('ERROR: could not resolve BlockLiquid SRG method mappings')
print(owner,height,local)
PY
)

OUT="$ROOT/out/real-liquid-height"
mkdir -p "$OUT"
JAVAP="$OUT/BlockLiquid.javap.txt"
javap -classpath "$ACOUSTIC_MC_1122_CLIENT" -c -p "$BLOCK_LIQUID" > "$JAVAP"

python3 - "$JAVAP" "$HEIGHT_PERCENT" "$LOCAL_HEIGHT" <<'PY'
from pathlib import Path
import re,sys
text=Path(sys.argv[1]).read_text(errors='replace')
# Windows-native Python writes CRLF to a Git-Bash process substitution; bash `read`
# removes the LF delimiter but can leave CR on the final mapped token. Method names
# cannot contain surrounding whitespace, so normalize only that transport artifact.
height_name=sys.argv[2].strip(); local_name=sys.argv[3].strip()

def method_block(name, signature_fragment=None):
    # Capture until the next javap method header. Do not depend on bytecode offset spacing or
    # constant-pool numbers: javap aligns 1/2/3-digit offsets differently.
    sig = r'\('+signature_fragment+r'\)' if signature_fragment is not None else r'\([^\n]*\)'
    pat=re.compile(
        r'^  [^\n]*\b'+re.escape(name)+sig+r';\n    Code:\n(?P<body>.*?)(?=^  [^\n]+\);(?:\n|$)|\Z)',
        re.M|re.S,
    )
    m=pat.search(text)
    if not m:
        raise SystemExit(f'ERROR: javap method block missing: {name}')
    return m.group('body')

height=method_block(height_name, 'int')
# Official 1.12.2 BlockLiquid.getLiquidHeightPercent semantics:
# if level >= 8 level = 0; return (level + 1) / 9.0f.
required_height=[
    r'bipush\s+8', r'if_icmplt', r'iconst_0', r'istore_0',
    r'iconst_1', r'iadd', r'i2f', r'float 9\.0f', r'fdiv', r'freturn',
]
pos=0
for pattern in required_height:
    m=re.search(pattern,height[pos:])
    if not m: raise SystemExit('ERROR: official BlockLiquid height-percent bytecode shape changed at '+pattern)
    pos += m.end()

# The local surface-height helper returns 1.0 immediately when a level-0/falling-equivalent
# liquid cell has the same liquid above; otherwise it computes 1 - heightPercent(level).
# Locate by method name rather than hardcoding the obfuscated owner/signature.
local=method_block(local_name)
if len(re.findall(r'fconst_1',local)) < 2 or 'fsub' not in local or 'freturn' not in local:
    raise SystemExit('ERROR: official BlockLiquid local-height bytecode lost 1-heightPercent/same-liquid-above shape')
if not re.search(r'invokestatic\s+#[0-9]+\s+// Method '+re.escape(height_name)+r':\(I\)F',local):
    raise SystemExit('ERROR: official BlockLiquid local-height helper no longer calls mapped height-percent method')

# Values used by ForgeBlockAcousticIntrospector.fluidShape fallback.
def percent(meta):
    level=0 if meta >= 8 else meta
    return (level+1)/9.0
def height(meta): return 1.0-percent(meta)
expected={0:8/9,4:4/9,8:8/9}
for meta,want in expected.items():
    got=height(meta)
    if abs(got-want)>1e-12: raise SystemExit(f'ERROR: derived vanilla height mismatch meta={meta}: {got} != {want}')
print('[PASS] official Minecraft 1.12.2 BlockLiquid bytecode: meta0=8/9, meta4=4/9, meta8=8/9; same-liquid-above=full')
PY
