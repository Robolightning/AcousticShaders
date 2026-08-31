#!/usr/bin/env python3
"""Prepare an RFG/MCP compile view without changing the authoritative production sources.

The normal deterministic release path compiles against dual-name contract stubs and deliberately
ships both MCP and SRG GUI entry points. RetroFuturaGradle compiles against MCP names and later
reobfuscates those methods to SRG. Feeding the dual-name source to RFG would therefore create
method collisions after reobf. This tool removes only the explicit SRG GUI aliases and rewrites
explicit SRG super-calls back to their MCP equivalents in the temporary RFG source view.
"""
from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path

GUI_RULES = {
    "GuiAcousticShaders.kt": {
        "aliases": [
            "func_73866_w_", "func_146284_a", "func_73863_a",
            "func_73864_a", "func_146273_a", "func_146286_b",
        ],
        "super": {
            "func_73863_a": "drawScreen",
            "func_73864_a": "mouseClicked",
            "func_146286_b": "mouseReleased",
        },
    },
    "GuiAcousticShaderOptions.kt": {
        "aliases": ["func_73866_w_", "func_146284_a", "func_73863_a"],
        "super": {"func_73863_a": "drawScreen"},
    },
    "GuiRuntimeAudio.kt": {
        "aliases": ["func_73866_w_", "func_146284_a", "func_73863_a"],
        "super": {"func_73863_a": "drawScreen"},
    },
}


def remove_one_line_alias(source: str, name: str) -> tuple[str, int]:
    # All intentional dual-name aliases are one-line expression-body functions. Keep the regex
    # strict so a future multiline/semantic change fails loudly instead of silently deleting code.
    pattern = re.compile(
        rf"(?m)^\s*override\s+fun\s+{re.escape(name)}\([^\n]*\)\s*(?::\s*[^=\n]+)?\s*=\s*[^\n]+\n"
    )
    return pattern.subn("", source, count=1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    root = args.root.resolve()
    out = args.out.resolve()

    roots = [
        root / "acoustic-api/src/main/kotlin",
        root / "acoustic-platform-api/src/main/kotlin",
        root / "acoustic-core/src/main/kotlin",
        root / "minecraft-1.12.2/src/main/kotlin",
        root / "minecraft-1.12.2/src/forge/kotlin",
    ]
    missing = [str(p) for p in roots if not p.is_dir()]
    if missing:
        raise SystemExit("missing source roots: " + ", ".join(missing))

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    copied = 0
    for src_root in roots:
        for src in sorted(src_root.rglob("*.kt")):
            rel = src.relative_to(src_root)
            dest = out / rel
            if dest.exists():
                raise SystemExit(f"duplicate Kotlin source path across production roots: {rel}")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)
            copied += 1

    alias_count = 0
    super_count = 0
    for filename, rules in GUI_RULES.items():
        matches = list(out.rglob(filename))
        if len(matches) != 1:
            raise SystemExit(f"expected exactly one {filename}, found {len(matches)}")
        path = matches[0]
        source = path.read_text()
        for alias in rules["aliases"]:
            source, count = remove_one_line_alias(source, alias)
            if count != 1:
                raise SystemExit(f"expected exactly one RFG SRG alias {alias} in {filename}, removed {count}")
            alias_count += count
        for srg, mcp in rules["super"].items():
            old = f"super.{srg}("
            new = f"super.{mcp}("
            count = source.count(old)
            if count != 1:
                raise SystemExit(f"expected exactly one {old} in {filename}, found {count}")
            source = source.replace(old, new)
            super_count += count
        path.write_text(source)

    # RFG must now see only MCP overrides in those GUI classes. Reflection strings elsewhere are
    # intentionally untouched and continue to carry both MCP/SRG aliases for runtime forks.
    for filename, rules in GUI_RULES.items():
        path = next(out.rglob(filename))
        source = path.read_text()
        for alias in rules["aliases"]:
            if re.search(rf"\boverride\s+fun\s+{re.escape(alias)}\b", source):
                raise SystemExit(f"SRG override survived RFG normalization: {filename}:{alias}")
        for mcp in ("initGui", "actionPerformed", "drawScreen"):
            if not re.search(rf"\boverride\s+fun\s+{mcp}\b", source):
                raise SystemExit(f"required MCP override missing after normalization: {filename}:{mcp}")

    expected_aliases = sum(len(v["aliases"]) for v in GUI_RULES.values())
    expected_supers = sum(len(v["super"]) for v in GUI_RULES.values())
    if alias_count != expected_aliases or super_count != expected_supers:
        raise SystemExit(
            f"normalization count mismatch aliases={alias_count}/{expected_aliases} "
            f"supers={super_count}/{expected_supers}"
        )
    print(
        f"[PASS] prepared RFG MCP source view: {copied} Kotlin files, "
        f"removed {alias_count} explicit SRG GUI aliases, rewrote {super_count} SRG super-calls"
    )


if __name__ == "__main__":
    main()
