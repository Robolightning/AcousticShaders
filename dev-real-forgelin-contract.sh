#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
: "${ACOUSTIC_FORGELIN_JAR:?set ACOUSTIC_FORGELIN_JAR to Forgelin-Continuous 2.4.0.0+ for Minecraft 1.12.2}"
JAR="$ACOUSTIC_FORGELIN_JAR"
[[ -f "$JAR" ]] || { echo "ERROR: Forgelin-Continuous jar missing: $JAR" >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo 'ERROR: unzip required' >&2; exit 1; }
command -v javac >/dev/null 2>&1 || { echo 'ERROR: javac required' >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo 'ERROR: java required' >&2; exit 1; }
OUT="$ROOT/out/real-forgelin"; rm -rf "$OUT"; mkdir -p "$OUT"
python3 - "$JAR" <<'PYMETA'
import json,struct,sys,zipfile
p=sys.argv[1]
with zipfile.ZipFile(p) as z:
    names=set(z.namelist())
    for required in ('mcmod.info','META-INF/MANIFEST.MF','kotlin/KotlinVersion.class','kotlin/KotlinVersionCurrentValue.class'):
        if required not in names: raise SystemExit('ERROR: Forgelin jar missing '+required)
    mods=json.loads(z.read('mcmod.info').decode('utf-8'))
    main=next((m for m in mods if m.get('modid')=='forgelin_continuous'),None)
    if main is None: raise SystemExit('ERROR: Forgelin jar has no forgelin_continuous mod metadata')
    if main.get('mcversion')!='1.12.2': raise SystemExit('ERROR: Forgelin mcversion is not 1.12.2: '+str(main.get('mcversion')))
    version=str(main.get('version',''))
    def nums(v):
        try: return tuple(int(x) for x in v.split('.'))
        except ValueError: raise SystemExit('ERROR: non-numeric Forgelin version: '+v)
    got=nums(version); floor=(2,4,0,0)
    got=got+(0,)*(len(floor)-len(got))
    if got < floor: raise SystemExit('ERROR: Forgelin version below required 2.4.0.0: '+version)
    manifest=z.read('META-INF/MANIFEST.MF').decode('utf-8','replace')
    if 'FMLCorePluginContainsFMLMod: true' not in manifest: raise SystemExit('ERROR: Forgelin manifest lost FMLCorePluginContainsFMLMod')
    plugin=None
    for line in manifest.replace('\r\n','\n').split('\n'):
        if line.startswith('FMLCorePlugin: '): plugin=line.split(': ',1)[1].strip(); break
    if not plugin: raise SystemExit('ERROR: Forgelin manifest has no FMLCorePlugin')
    plugin_entry=plugin.replace('.','/')+'.class'
    if plugin_entry not in names: raise SystemExit('ERROR: Forgelin FMLCorePlugin class missing: '+plugin_entry)
    majors={}; max_major=0; classes=0
    for name in names:
        if not name.endswith('.class'): continue
        data=z.read(name)
        if data[:4] != b'\xca\xfe\xba\xbe': raise SystemExit('ERROR: invalid classfile '+name)
        major=struct.unpack('>H',data[6:8])[0]
        majors[major]=majors.get(major,0)+1; max_major=max(max_major,major); classes+=1
        if major>52: raise SystemExit(f'ERROR: Forgelin contains post-Java8 class {name}: major={major}')
print(f'[PASS] Forgelin metadata/coreplugin/Java8: version={version} classes={classes} maxMajor={max_major}')
PYMETA
cat > "$OUT/KotlinRuntimeProbe.java" <<'EOFJAVA'
public final class KotlinRuntimeProbe {
  public static void main(String[] args) throws Exception {
    Class<?> c=Class.forName("kotlin.KotlinVersion");
    Object current=c.getField("CURRENT").get(null);
    System.out.println(current.toString());
  }
}
EOFJAVA
javac --release 8 -Xlint:all,-options -Werror -d "$OUT" "$OUT/KotlinRuntimeProbe.java"
KOTLIN_RUNTIME_VERSION="$(java -cp "$OUT:$JAR" KotlinRuntimeProbe | tr -d '\r' | tail -1)"
python3 - "$KOTLIN_RUNTIME_VERSION" <<'PYKOTLIN'
import sys
v=sys.argv[1]
try: parts=tuple(int(x) for x in v.split('.'))
except ValueError: raise SystemExit('ERROR: Forgelin embedded Kotlin version is not numeric: '+v)
if parts < (2,4,0): raise SystemExit('ERROR: Forgelin embedded Kotlin runtime is older than 2.4.0: '+v)
print('[PASS] Forgelin embedded Kotlin runtime >= 2.4.0: '+v)
PYKOTLIN
printf '[PASS] physical Forgelin-Continuous runtime dependency: %s  %s\n' "$(sha256sum "$JAR" | awk '{print $1}')" "$JAR"
