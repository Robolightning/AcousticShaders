#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
MC="${ACOUSTIC_REAL_SRG_MC_JAR:-$ROOT/out/real-srg/minecraft-client-srg.jar}"
MAP="${ACOUSTIC_REAL_SRG_MAP:-$ROOT/out/real-srg/joined.tsrg}"
[[ -s "$MC" && -s "$MAP" ]] || { echo 'ERROR: prepared real SRG client/mappings missing' >&2; exit 1; }
python3 - "$MAP" "$ROOT/minecraft-1.12.2/src/forge/kotlin" <<'PY'
from pathlib import Path
import re,sys
mapping=Path(sys.argv[1]).read_text(errors='replace')
known=set(re.findall(r'\b(?:func|field)_[0-9A-Za-z_]+\b',mapping))
used=set()
for p in Path(sys.argv[2]).rglob('*.kt'):
    used.update(re.findall(r'"((?:func|field)_[0-9A-Za-z_]+)"',p.read_text(errors='replace')))
missing=sorted(used-known)
if missing: raise SystemExit('ERROR: production SRG literals absent from joined.tsrg: '+', '.join(missing))
print(f'[PASS] all {len(used)} production SRG reflection literals exist in official mappings')
PY
require_members(){
  local cls="$1"; shift; local text
  text="$(javap -classpath "$MC" -p "$cls" 2>/dev/null)" || { echo "ERROR: real SRG class missing: $cls" >&2; exit 1; }
  local sym
  for sym in "$@"; do grep -qE "(^|[ (.;])${sym}([ (;]|$)" <<<"$text" || { echo "ERROR: $cls missing audited SRG member $sym" >&2; exit 1; }; done
}
require_members net.minecraft.block.Block field_149771_c field_149782_v field_149781_w field_149762_H func_176201_c func_176203_a func_176223_P func_185477_a
require_members net.minecraft.block.state.IBlockProperties func_185904_a func_185917_h func_185914_p func_185890_d
require_members net.minecraft.block.state.IBlockState func_177230_c
require_members net.minecraft.block.material.Material func_76224_d func_76230_c
require_members net.minecraft.block.SoundType func_185845_c
require_members net.minecraft.client.Minecraft func_71410_x field_71441_e field_71439_g field_71474_y func_175606_aa func_110438_M func_110436_a func_147108_a
require_members net.minecraft.client.settings.GameSettings field_151453_l func_74303_b
require_members net.minecraft.client.resources.ResourcePackRepository func_110611_a func_110609_b func_110613_c func_148527_a
require_members 'net.minecraft.client.resources.ResourcePackRepository$Entry' func_110515_d
require_members net.minecraft.client.gui.GuiScreen field_146294_l field_146295_m field_146292_n field_146289_q field_146297_k func_146276_q_ func_73866_w_ func_146284_a func_73863_a func_73864_a func_146273_a func_146286_b
require_members net.minecraft.client.gui.Gui func_73732_a func_73731_b func_73734_a
require_members net.minecraft.client.gui.GuiButton field_146127_k field_146128_h field_146129_i field_146120_f field_146121_g field_146124_l field_146126_j
require_members net.minecraft.client.gui.FontRenderer func_78256_a
require_members net.minecraft.world.World func_180495_p func_72964_e
require_members net.minecraft.world.chunk.Chunk func_177435_g
require_members 'net.minecraft.util.math.BlockPos$MutableBlockPos' func_181079_c
require_members net.minecraft.entity.Entity field_70177_z field_70165_t field_70163_u field_70161_v func_70047_e
require_members net.minecraft.util.math.AxisAlignedBB field_72340_a field_72338_b field_72339_c field_72336_d field_72337_e field_72334_f
echo '[PASS] real 1.12.2 SRG reflection owner/member audit'
