#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import sys

MAGIC = "ACOUSTIC-RFG-BUILDENV-MANIFEST-V1"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def entries(root: Path):
    root = root.resolve()
    result = []
    for base, dirs, files in os.walk(root, topdown=True, followlinks=False):
        base_path = Path(base)
        dirs.sort()
        files.sort()
        # os.walk lists symlinked directories in dirs; record them as links and
        # remove them so the manifest never follows out-of-tree content.
        for name in list(dirs):
            p = base_path / name
            if p.is_symlink():
                rel = p.relative_to(root).as_posix()
                target = os.readlink(p)
                digest = hashlib.sha256(target.encode("utf-8")).hexdigest()
                result.append(("L", len(target.encode("utf-8")), digest, rel, target))
                dirs.remove(name)
        for name in files:
            p = base_path / name
            rel = p.relative_to(root).as_posix()
            if p.is_symlink():
                target = os.readlink(p)
                digest = hashlib.sha256(target.encode("utf-8")).hexdigest()
                result.append(("L", len(target.encode("utf-8")), digest, rel, target))
            elif p.is_file():
                result.append(("F", p.stat().st_size, sha256_file(p), rel, ""))
            else:
                raise RuntimeError(f"unsupported payload entry: {p}")
    return sorted(result, key=lambda row: row[3])


def create(root: Path, manifest: Path) -> None:
    rows = entries(root)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    with manifest.open("w", encoding="utf-8", newline="\n") as out:
        out.write(MAGIC + "\n")
        for kind, size, digest, rel, target in rows:
            if "\t" in rel or "\n" in rel:
                raise RuntimeError(f"unsafe manifest path: {rel!r}")
            if kind == "L" and ("\t" in target or "\n" in target):
                raise RuntimeError(f"unsafe symlink target: {target!r}")
            out.write(f"{kind}\t{size}\t{digest}\t{rel}")
            if kind == "L":
                out.write(f"\t{target}")
            out.write("\n")
    print(f"[PASS] RFG buildenv payload manifest created: {len(rows)} entries")


def parse_manifest(manifest: Path):
    lines = manifest.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != MAGIC:
        raise RuntimeError("invalid RFG buildenv manifest header")
    expected = {}
    for line in lines[1:]:
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) not in (4, 5):
            raise RuntimeError(f"invalid manifest row: {line!r}")
        kind, size_s, digest, rel = parts[:4]
        target = parts[4] if len(parts) == 5 else ""
        if kind not in ("F", "L"):
            raise RuntimeError(f"invalid manifest kind: {kind}")
        if rel.startswith("/") or rel == ".." or rel.startswith("../") or "/../" in rel:
            raise RuntimeError(f"unsafe manifest path: {rel}")
        if rel in expected:
            raise RuntimeError(f"duplicate manifest path: {rel}")
        expected[rel] = (kind, int(size_s), digest, target)
    return expected


def verify(root: Path, manifest: Path) -> None:
    root = root.resolve()
    expected = parse_manifest(manifest)
    actual_rows = entries(root)
    actual = {row[3]: (row[0], row[1], row[2], row[4]) for row in actual_rows}
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    mismatched = []
    for rel in sorted(set(expected) & set(actual)):
        if expected[rel] != actual[rel]:
            mismatched.append((rel, expected[rel], actual[rel]))
    if missing or extra or mismatched:
        if missing:
            print("ERROR: manifest missing payload entries:", file=sys.stderr)
            for rel in missing[:25]:
                print(f"  {rel}", file=sys.stderr)
        if extra:
            print("ERROR: manifest has unexpected payload entries:", file=sys.stderr)
            for rel in extra[:25]:
                print(f"  {rel}", file=sys.stderr)
        if mismatched:
            print("ERROR: manifest payload mismatch:", file=sys.stderr)
            for rel, exp, got in mismatched[:25]:
                print(f"  {rel}\n    expected={exp}\n    actual={got}", file=sys.stderr)
        raise SystemExit(1)
    print(f"[PASS] RFG buildenv payload manifest verified: {len(expected)} entries")


def main() -> None:
    ap = argparse.ArgumentParser(description="Create/verify exact RFG offline build-environment payload manifests")
    sub = ap.add_subparsers(dest="command", required=True)
    c = sub.add_parser("create")
    c.add_argument("root", type=Path)
    c.add_argument("manifest", type=Path)
    v = sub.add_parser("verify")
    v.add_argument("root", type=Path)
    v.add_argument("manifest", type=Path)
    args = ap.parse_args()
    if args.command == "create":
        create(args.root, args.manifest)
    else:
        verify(args.root, args.manifest)


if __name__ == "__main__":
    main()
