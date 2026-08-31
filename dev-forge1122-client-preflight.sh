#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

BUNDLE="${ACOUSTIC_FORGE1122_BUNDLE:-}"
MC_HOME="${ACOUSTIC_MC1122_HOME:-}"
OUT="$ROOT/out/forge1122-client-preflight"
EXPECTED_FORGE_SHA1='d0ab8e116da0e50c6e6099791f97772a08469626'

[[ -n "$BUNDLE" ]] || { echo 'ERROR: ACOUSTIC_FORGE1122_BUNDLE is required' >&2; exit 2; }
[[ -f "$BUNDLE" ]] || { echo "ERROR: Forge 1.12.2 bundle missing: $BUNDLE" >&2; exit 1; }
BUNDLE="$(cd "$(dirname "$BUNDLE")" && pwd)/$(basename "$BUNDLE")"

rm -rf "$OUT"
mkdir -p "$OUT/forge"

tar -xJf "$BUNDLE" -C "$OUT/forge" \
  forge-1.12.2-14.23.5.2864-installer.jar \
  forge-1.12.2-14.23.5.2864-mdk.zip \
  forge-1.12.2-14.23.5.2864-universal.jar

INSTALLER="$OUT/forge/forge-1.12.2-14.23.5.2864-installer.jar"
MDK="$OUT/forge/forge-1.12.2-14.23.5.2864-mdk.zip"
UNIVERSAL="$OUT/forge/forge-1.12.2-14.23.5.2864-universal.jar"

python3 - "$INSTALLER" "$MDK" "$UNIVERSAL" "$EXPECTED_FORGE_SHA1" "$OUT" <<'PY'
import hashlib, json, pathlib, re, sys, zipfile
installer, mdk, universal, expected_sha1, out = sys.argv[1:]
out = pathlib.Path(out)

def sha1(path):
    h=hashlib.sha1()
    with open(path,'rb') as f:
        for b in iter(lambda:f.read(1<<20),b''): h.update(b)
    return h.hexdigest()

actual=sha1(universal)
if actual != expected_sha1:
    raise SystemExit(f'ERROR: Forge universal SHA-1 mismatch: {actual}')

with zipfile.ZipFile(installer) as z:
    profile=json.loads(z.read('install_profile.json'))
    version=json.loads(z.read('version.json'))
    if profile.get('minecraft') != '1.12.2':
        raise SystemExit('ERROR: installer target is not Minecraft 1.12.2')
    if profile.get('version') != '1.12.2-forge-14.23.5.2864':
        raise SystemExit('ERROR: installer Forge version mismatch')
    if version.get('id') != '1.12.2-forge-14.23.5.2864':
        raise SystemExit('ERROR: Forge version.json id mismatch')
    if version.get('inheritsFrom') != '1.12.2':
        raise SystemExit('ERROR: Forge version.json must inherit vanilla 1.12.2')
    if version.get('mainClass') != 'net.minecraft.launchwrapper.Launch':
        raise SystemExit('ERROR: Forge client mainClass is not LaunchWrapper')
    args=version.get('minecraftArguments','')
    if '--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker' not in args:
        raise SystemExit('ERROR: Forge client FMLTweaker argument missing')
    artifact=None
    for lib in version.get('libraries',[]):
        if lib.get('name') == 'net.minecraftforge:forge:1.12.2-14.23.5.2864':
            artifact=lib.get('downloads',{}).get('artifact',{})
            break
    if not artifact or artifact.get('sha1') != expected_sha1:
        raise SystemExit('ERROR: Forge artifact metadata SHA-1 mismatch')
    embedded=z.read('maven/'+artifact['path'])
    if hashlib.sha1(embedded).hexdigest() != expected_sha1:
        raise SystemExit('ERROR: installer embedded Forge JAR mismatch')
    if embedded != pathlib.Path(universal).read_bytes():
        raise SystemExit('ERROR: installer embedded Forge JAR differs from universal')
    (out/'forge-version.json').write_text(json.dumps(version,indent=2),encoding='utf-8')

with zipfile.ZipFile(universal) as z:
    manifest=z.read('META-INF/MANIFEST.MF').decode('utf-8','replace').replace('\r\n','\n')
    if 'Tweak-Class: net.minecraftforge.fml.common.launcher.FMLTweaker' not in manifest:
        raise SystemExit('ERROR: Forge universal lost FMLTweaker manifest')
    if 'Implementation-Version: 14.23.5.2864' not in manifest:
        raise SystemExit('ERROR: Forge universal implementation version mismatch')

with zipfile.ZipFile(mdk) as z:
    build=z.read('build.gradle').decode('utf-8','replace')
    if '1.12.2-14.23.5.2864' not in build:
        raise SystemExit('ERROR: Forge MDK does not target 1.12.2-14.23.5.2864')

print('[PASS] official Forge 1.12.2 / 14.23.5.2864 installer + MDK + universal metadata')
print('[PASS] installer embedded Forge JAR is byte-identical to supplied universal')
print('[PASS] Forge client LaunchWrapper/FMLTweaker contract')
PY

# Existing binary ABI gate uses the same official universal and also compares our compile stubs.
ACOUSTIC_FORGE_1122_UNIVERSAL="$UNIVERSAL" \
ACOUSTIC_FORGE_1122_INSTALLER="$INSTALLER" \
  "$ROOT/dev-real-forge-abi.sh"

if [[ -z "$MC_HOME" ]]; then
  printf '%s\n' '[INFO] ACOUSTIC_MC1122_HOME is not set: official Forge artifact gate complete; full client runtime preflight skipped.'
  exit 0
fi

MC_HOME="$(cd "$MC_HOME" && pwd)"
VANILLA_JSON="$MC_HOME/versions/1.12.2/1.12.2.json"
VANILLA_JAR="$MC_HOME/versions/1.12.2/1.12.2.jar"
for f in "$VANILLA_JSON" "$VANILLA_JAR"; do
  [[ -f "$f" ]] || { echo "ERROR: complete Minecraft 1.12.2 launcher runtime missing: $f" >&2; exit 3; }
done

python3 - "$MC_HOME" "$VANILLA_JSON" "$VANILLA_JAR" "$OUT/forge-version.json" "$UNIVERSAL" "$OUT" <<'PY'
import hashlib, json, os, pathlib, re, sys
home=pathlib.Path(sys.argv[1]); vanilla_json=pathlib.Path(sys.argv[2]); vanilla_jar=pathlib.Path(sys.argv[3])
forge_json=pathlib.Path(sys.argv[4]); universal=pathlib.Path(sys.argv[5]); out=pathlib.Path(sys.argv[6])
vanilla=json.loads(vanilla_json.read_text(encoding='utf-8')); forge=json.loads(forge_json.read_text(encoding='utf-8'))
if vanilla.get('id') != '1.12.2': raise SystemExit('ERROR: vanilla version JSON is not 1.12.2')
if vanilla.get('mainClass') != 'net.minecraft.client.main.Main':
    raise SystemExit('ERROR: vanilla 1.12.2 version JSON has unexpected client mainClass')
if vanilla.get('assetIndex',{}).get('id') != '1.12':
    raise SystemExit('ERROR: vanilla 1.12.2 asset index metadata mismatch')
expected_client=vanilla.get('downloads',{}).get('client',{})
OFFICIAL_CLIENT_SHA1='0f275bc1547d01fa5f56ba34bdc87d981ee12daf'
if expected_client.get('sha1') != OFFICIAL_CLIENT_SHA1:
    raise SystemExit('ERROR: vanilla 1.12.2 client metadata SHA-1 mismatch')
actual=hashlib.sha1(vanilla_jar.read_bytes()).hexdigest()
if actual != OFFICIAL_CLIENT_SHA1:
    raise SystemExit(f'ERROR: vanilla 1.12.2 client.jar SHA-1 mismatch: {actual}')
vanilla_names={lib.get('name') for lib in vanilla.get('libraries',[])}
required_vanilla={
    'com.paulscode:soundsystem:20120107',
    'com.paulscode:librarylwjglopenal:20100824',
    'net.java.jinput:jinput:2.0.5',
    'org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209',
    'org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209',
    'org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209',
    'org.apache.logging.log4j:log4j-core:2.8.1',
}
absent=sorted(required_vanilla-vanilla_names)
if absent:
    raise SystemExit('ERROR: vanilla 1.12.2 version JSON is incomplete; missing: '+', '.join(absent))

def rule_matches(rule):
    osrule=rule.get('os')
    if not osrule: return True
    if osrule.get('name') and osrule['name'] != 'windows': return False
    # Architecture/version predicates are uncommon in 1.12.2. Treat x86_64 as the target.
    arch=osrule.get('arch')
    if arch and arch not in ('x86_64','amd64'): return False
    return True

def allowed(lib):
    rules=lib.get('rules')
    if not rules: return True
    state=False
    for rule in rules:
        if rule_matches(rule): state=(rule.get('action') == 'allow')
    return state

missing=[]; bad=[]; cp=[]; native_archives=[]

def accept_artifact(meta,label):
    if not meta: return
    rel=meta.get('path')
    if not rel: return
    p=home/'libraries'/rel
    if not p.is_file():
        # Mojang's LWJGL 2.9.4 platform artifact is intentionally a 22-byte empty ZIP.
        # Some launcher installations keep only the native classifier, so recreate the
        # exact canonical marker locally instead of treating it as an external runtime gap.
        marker_rel='org/lwjgl/lwjgl/lwjgl-platform/2.9.4-nightly-20150209/lwjgl-platform-2.9.4-nightly-20150209.jar'
        marker_sha1='b04f3ee8f5e43fa3b162981b50bb72fe1acabb33'
        if rel == marker_rel and meta.get('sha1') == marker_sha1 and meta.get('size') == 22:
            generated=out/'generated-libraries'/rel
            generated.parent.mkdir(parents=True,exist_ok=True)
            generated.write_bytes(b'PK\x05\x06'+b'\x00'*18)
            actual=hashlib.sha1(generated.read_bytes()).hexdigest()
            if actual != marker_sha1:
                raise SystemExit('ERROR: generated canonical LWJGL platform marker SHA-1 mismatch')
            cp.append(str(generated))
            return
        missing.append((label,str(p),meta.get('url','')))
        return
    if meta.get('sha1'):
        actual=hashlib.sha1(p.read_bytes()).hexdigest()
        if actual != meta['sha1']: bad.append((label,str(p),meta['sha1'],actual)); return
    cp.append(str(p))

for lib in vanilla.get('libraries',[]):
    if not allowed(lib): continue
    dl=lib.get('downloads',{})
    accept_artifact(dl.get('artifact'),lib.get('name','vanilla-lib'))
    classifier=lib.get('natives',{}).get('windows')
    if classifier:
        meta=dl.get('classifiers',{}).get(classifier)
        if meta:
            rel=meta.get('path'); p=home/'libraries'/rel
            if not p.is_file(): missing.append((lib.get('name','native'),str(p),meta.get('url','')))
            else:
                if meta.get('sha1'):
                    actual=hashlib.sha1(p.read_bytes()).hexdigest()
                    if actual != meta['sha1']: bad.append((lib.get('name','native'),str(p),meta['sha1'],actual)); continue
                native_archives.append(str(p))

for lib in forge.get('libraries',[]):
    if not allowed(lib): continue
    meta=lib.get('downloads',{}).get('artifact')
    if lib.get('name') == 'net.minecraftforge:forge:1.12.2-14.23.5.2864':
        cp.append(str(universal)); continue
    accept_artifact(meta,lib.get('name','forge-lib'))

# A complete launcher runtime also needs the 1.12 asset index and every addressed object.
asset_index=home/'assets'/'indexes'/'1.12.json'
if not asset_index.is_file():
    missing.append(('asset-index-1.12',str(asset_index),'https://piston-meta.mojang.com/'))
else:
    try:
        assets=json.loads(asset_index.read_text(encoding='utf-8')).get('objects',{})
    except Exception as e:
        raise SystemExit(f'ERROR: invalid assets/indexes/1.12.json: {e}')
    for logical,meta in assets.items():
        h=meta.get('hash','')
        if not re.fullmatch(r'[0-9a-f]{40}',h):
            raise SystemExit(f'ERROR: invalid asset hash for {logical}: {h!r}')
        obj=home/'assets'/'objects'/h[:2]/h
        if not obj.is_file():
            missing.append((f'asset:{logical}',str(obj),'')); continue
        if hashlib.sha1(obj.read_bytes()).hexdigest() != h:
            bad.append((f'asset:{logical}',str(obj),h,hashlib.sha1(obj.read_bytes()).hexdigest()))

if bad:
    for label,p,exp,act in bad: print(f'BAD {label}: {p} expected={exp} actual={act}',file=sys.stderr)
    raise SystemExit('ERROR: client runtime contains checksum-mismatched libraries/assets')
if missing:
    report=out/'missing-client-runtime.txt'
    report.write_text('\n'.join(f'{label}\t{path}\t{url}' for label,path,url in missing)+'\n',encoding='utf-8')
    print(f'ERROR: complete Windows Minecraft 1.12.2 client runtime is missing {len(missing)} required artifacts',file=sys.stderr)
    print(f'       exact list: {report}',file=sys.stderr)
    for label,path,url in missing[:12]: print(f'  MISSING {label}: {path}',file=sys.stderr)
    raise SystemExit(3)
if len(native_archives) < 3:
    raise SystemExit(f'ERROR: vanilla metadata exposes a suspiciously incomplete Windows native classifier set: {len(native_archives)} archives')

cp.append(str(vanilla_jar))
# Preserve order but remove duplicates.
cp=list(dict.fromkeys(cp))
(out/'client-classpath.txt').write_text('\n'.join(cp)+'\n',encoding='utf-8')
(out/'windows-native-archives.txt').write_text('\n'.join(dict.fromkeys(native_archives))+'\n',encoding='utf-8')
print(f'[PASS] complete Windows 1.12.2 client library set ({len(cp)} classpath entries, {len(native_archives)} native archives)')
PY
