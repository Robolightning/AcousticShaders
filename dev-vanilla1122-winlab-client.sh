#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
: "${ACOUSTIC_MC1122_HOME:?set ACOUSTIC_MC1122_HOME}"
: "${ACOUSTIC_WINLAB_ROOT:?set ACOUSTIC_WINLAB_ROOT}"
: "${ACOUSTIC_WINDOWS_JAVA8:?set ACOUSTIC_WINDOWS_JAVA8 to java.exe or a Java home}"
OUT="$ROOT/out/vanilla1122-winlab-client"
MC_HOME="$(cd "$ACOUSTIC_MC1122_HOME" && pwd)"
WINLAB_ROOT="$(cd "$ACOUSTIC_WINLAB_ROOT" && pwd)"
JAVA="$ACOUSTIC_WINDOWS_JAVA8"
if [[ -d "$JAVA" ]]; then JAVA="$JAVA/bin/java.exe"; fi
[[ -f "$JAVA" ]] || { echo "ERROR: Windows Java 8 executable missing: $JAVA" >&2; exit 1; }
if [[ ! -x "$WINLAB_ROOT/run" ]]; then
  mapfile -t kids < <(find "$WINLAB_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'winlab*' -print | sort)
  [[ ${#kids[@]} -eq 1 && -x "${kids[0]}/run" ]] || { echo "ERROR: WinLab run missing under $WINLAB_ROOT" >&2; exit 1; }
  WINLAB_ROOT="${kids[0]}"
fi
RUN="$WINLAB_ROOT/run"
rm -rf "$OUT"; mkdir -p "$OUT/game" "$OUT/natives" "$OUT/generated-libraries"
python3 - "$MC_HOME" "$OUT" <<'PY'
import hashlib,json,pathlib,sys,zipfile
home=pathlib.Path(sys.argv[1]); out=pathlib.Path(sys.argv[2])
vj=home/'versions/1.12.2/1.12.2.json'; client=home/'versions/1.12.2/1.12.2.jar'
if not vj.is_file() or not client.is_file(): raise SystemExit('ERROR: vanilla 1.12.2 version JSON/client JAR missing')
j=json.loads(vj.read_text(encoding='utf-8'))
OFF='0f275bc1547d01fa5f56ba34bdc87d981ee12daf'
if hashlib.sha1(client.read_bytes()).hexdigest()!=OFF: raise SystemExit('ERROR: vanilla client.jar SHA-1 mismatch')
def rule_matches(r):
 o=r.get('os')
 if not o:return True
 if o.get('name') and o['name']!='windows':return False
 if o.get('arch') and o['arch'] not in ('x86_64','amd64'):return False
 return True
def allowed(lib):
 rules=lib.get('rules')
 if not rules:return True
 state=False
 for r in rules:
  if rule_matches(r): state=r.get('action')=='allow'
 return state
cp=[]; natives=[]; missing=[]; bad=[]
marker_rel='org/lwjgl/lwjgl/lwjgl-platform/2.9.4-nightly-20150209/lwjgl-platform-2.9.4-nightly-20150209.jar'
marker_sha='b04f3ee8f5e43fa3b162981b50bb72fe1acabb33'
for lib in j.get('libraries',[]):
 if not allowed(lib): continue
 dl=lib.get('downloads',{}); art=dl.get('artifact')
 if art and art.get('path'):
  rel=art['path']; p=home/'libraries'/rel
  if not p.is_file() and rel==marker_rel and art.get('sha1')==marker_sha and art.get('size')==22:
   p=out/'generated-libraries'/rel; p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(b'PK\x05\x06'+b'\x00'*18)
  if not p.is_file(): missing.append((lib.get('name',''),str(p))); continue
  act=hashlib.sha1(p.read_bytes()).hexdigest(); exp=art.get('sha1')
  if exp and act!=exp: bad.append((lib.get('name',''),str(p),exp,act)); continue
  cp.append(p)
 cl=lib.get('natives',{}).get('windows')
 if cl:
  meta=dl.get('classifiers',{}).get(cl)
  if meta:
   p=home/'libraries'/meta['path']
   if not p.is_file(): missing.append((lib.get('name','')+'#'+cl,str(p))); continue
   act=hashlib.sha1(p.read_bytes()).hexdigest(); exp=meta.get('sha1')
   if exp and act!=exp: bad.append((lib.get('name','')+'#'+cl,str(p),exp,act)); continue
   natives.append(p)
if missing or bad:
 for x in missing: print('MISSING',*x,file=sys.stderr)
 for x in bad: print('BAD',*x,file=sys.stderr)
 raise SystemExit('ERROR: incomplete/corrupt vanilla Windows runtime')
asset_index=home/'assets/indexes/1.12.json'
assets=json.loads(asset_index.read_text(encoding='utf-8')).get('objects',{})
for logical,meta in assets.items():
 h=meta['hash']; p=home/'assets/objects'/h[:2]/h
 if not p.is_file() or hashlib.sha1(p.read_bytes()).hexdigest()!=h:
  raise SystemExit(f'ERROR: missing/corrupt asset {logical}')
for jar in natives:
 with zipfile.ZipFile(jar) as z:
  for n in z.namelist():
   if n.endswith('/') or n.startswith('META-INF/'):continue
   z.extract(n,out/'natives')
cp=list(dict.fromkeys(map(str,cp+[client])))
(out/'classpath.txt').write_text('\n'.join(cp)+'\n',encoding='utf-8')
print(f'[PASS] vanilla Windows runtime: classpath={len(cp)} natives={len(natives)} assets={len(assets)}')
PY
to_z(){ local p; p="$(readlink -f "$1")"; printf 'Z:%s' "${p//\//\\}"; }
JAVA_Z="$(to_z "$JAVA")"; GAME_Z="$(to_z "$OUT/game")"; ASSETS_Z="$(to_z "$MC_HOME/assets")"; NATIVES_Z="$(to_z "$OUT/natives")"
CP_Z=''; while IFS= read -r p; do [[ -n "$p" ]] || continue; z="$(to_z "$p")"; [[ -z "$CP_Z" ]] && CP_Z="$z" || CP_Z="$CP_Z;$z"; done < "$OUT/classpath.txt"
LOG="$OUT/client-console.log"
cd "$OUT/game"
CMD=("$RUN" wine "$JAVA_Z" -Xms256M -Xmx1024M -Dfile.encoding=UTF-8 "-Djava.library.path=$NATIVES_Z" -cp "$CP_Z" net.minecraft.client.main.Main --username AcousticShadersWinLab --version 1.12.2 --gameDir "$GAME_Z" --assetsDir "$ASSETS_Z" --assetIndex 1.12 --uuid 00000000000000000000000000000001 --accessToken 0 --userType legacy --versionType release --userProperties '{}')
cleanup_client(){
  set +e
  [[ -n "${CLIENT_PID:-}" ]] && kill -TERM "$CLIENT_PID" 2>/dev/null || true
  sleep 1
  [[ -n "${CLIENT_PID:-}" ]] && kill -KILL "$CLIENT_PID" 2>/dev/null || true
  "$RUN" wineserver -k >/dev/null 2>&1 || true
  wait "${CLIENT_PID:-}" 2>/dev/null || true
  set -e
}
trap cleanup_client EXIT INT TERM
: > "$LOG"
if command -v xvfb-run >/dev/null 2>&1; then
  xvfb-run -a "${CMD[@]}" >"$LOG" 2>&1 &
else
  "${CMD[@]}" >"$LOG" 2>&1 &
fi
CLIENT_PID=$!
DEADLINE=$((SECONDS + ${ACOUSTIC_CLIENT_BOOT_TIMEOUT:-90}))
SUCCESS=0
while (( SECONDS < DEADLINE )); do
  if grep -Eq 'Could not find or load main class|NoClassDefFoundError|UnsupportedClassVersionError|Exception in thread "(main|Client thread)"' "$LOG"; then
    break
  fi
  if grep -F 'LWJGL Version: 2.9.4' "$LOG" >/dev/null \
     && grep -F 'Reloading ResourceManager: Default' "$LOG" >/dev/null \
     && grep -F 'textures-atlas' "$LOG" >/dev/null; then
    SUCCESS=1
    break
  fi
  if ! kill -0 "$CLIENT_PID" 2>/dev/null; then break; fi
  sleep 1
done
cat "$LOG"
if (( SUCCESS != 1 )); then
  echo 'ERROR: vanilla client boot did not reach the required graphics/resource markers' >&2
  exit 1
fi
printf '%s\n' '[PASS] real Windows vanilla Minecraft 1.12.2 client reached graphics/resource initialization'
if grep -F 'Switching to No Sound' "$LOG" >/dev/null; then
  printf '%s\n' '[INFO] WinLab host exposes no usable playback device; Minecraft correctly fell back to No Sound.'
fi
exit 0
