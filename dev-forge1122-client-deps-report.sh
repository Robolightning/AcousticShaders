#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
: "${ACOUSTIC_FORGE1122_BUNDLE:?set ACOUSTIC_FORGE1122_BUNDLE}"
: "${ACOUSTIC_MC1122_HOME:?set ACOUSTIC_MC1122_HOME}"
OUT="$ROOT/out/forge1122-client-deps"
rm -rf "$OUT"; mkdir -p "$OUT"
python3 - "$ACOUSTIC_FORGE1122_BUNDLE" "$ACOUSTIC_MC1122_HOME" "$OUT" <<'PY'
import hashlib, io, json, pathlib, sys, tarfile, zipfile
bundle=pathlib.Path(sys.argv[1]); home=pathlib.Path(sys.argv[2]); out=pathlib.Path(sys.argv[3])
if not bundle.is_file(): raise SystemExit(f'ERROR: Forge bundle missing: {bundle}')
if not home.is_dir(): raise SystemExit(f'ERROR: Minecraft home missing: {home}')
installer_name='forge-1.12.2-14.23.5.2864-installer.jar'
universal_name='forge-1.12.2-14.23.5.2864-universal.jar'
with tarfile.open(bundle,'r:xz') as t:
    installer=t.extractfile(installer_name).read()
    universal=t.extractfile(universal_name).read()
with zipfile.ZipFile(io.BytesIO(installer)) as z:
    version=json.loads(z.read('version.json'))
missing=[]; bad=[]; present=[]
for lib in version.get('libraries',[]):
    name=lib.get('name','')
    meta=lib.get('downloads',{}).get('artifact') or {}
    rel=meta.get('path')
    if not rel: continue
    if name == 'net.minecraftforge:forge:1.12.2-14.23.5.2864':
        exp=meta.get('sha1')
        act=hashlib.sha1(universal).hexdigest()
        if exp and act != exp: bad.append((name,'<bundle universal>',exp,act))
        else: present.append((name,'<bundle universal>',act))
        continue
    p=home/'libraries'/rel
    if not p.is_file():
        missing.append((name,rel,meta.get('sha1',''),meta.get('url','')))
        continue
    act=hashlib.sha1(p.read_bytes()).hexdigest()
    exp=meta.get('sha1','')
    if exp and act != exp: bad.append((name,str(p),exp,act))
    else: present.append((name,str(p),act))
report=out/'missing-forge1122-client-libraries.tsv'
report.write_text('name\tpath\tsha1\turl\n'+'\n'.join('\t'.join(x) for x in missing)+('\n' if missing else ''),encoding='utf-8')
(out/'present-forge1122-client-libraries.tsv').write_text('name\tpath\tsha1\n'+'\n'.join('\t'.join(x) for x in present)+'\n',encoding='utf-8')
if bad:
    for x in bad: print('BAD\t'+'\t'.join(x),file=sys.stderr)
    raise SystemExit('ERROR: checksum-mismatched Forge client libraries')
print(f'[INFO] Forge 1.12.2 client dependencies present={len(present)} missing={len(missing)}')
if missing:
    for name,rel,sha1,url in missing: print(f'MISSING {name} :: {rel} :: {sha1} :: {url}')
    print(f'[INFO] exact missing dependency report: {report}')
    raise SystemExit(3)
print('[PASS] complete Forge 1.12.2 client dependency set')
PY
