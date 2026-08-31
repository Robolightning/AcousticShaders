#!/usr/bin/env python3
"""Verify that the RFG output really contains production SRG GUI overrides.

The authoritative source deliberately carries dual MCP/SRG GUI methods for the custom deterministic
builder.  The temporary RFG source view removes the SRG aliases, so RetroFuturaGradle must rename the
remaining MCP overrides during reobfuscation.  This checks the final classfile method tables directly;
a successful Gradle task alone is not accepted as proof of that inheritance-aware remap.
"""
from __future__ import annotations

import argparse
import io
import struct
import zipfile
from dataclasses import dataclass
from pathlib import Path


class ClassFormatError(RuntimeError):
    pass


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise ClassFormatError("truncated classfile")
        out = self.data[self.pos:self.pos+n]
        self.pos += n
        return out

    def u1(self) -> int:
        return self.take(1)[0]

    def u2(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u4(self) -> int:
        return struct.unpack(">I", self.take(4))[0]


def skip_attributes(r: Reader, count: int) -> None:
    for _ in range(count):
        r.u2()
        r.take(r.u4())


def method_table(data: bytes) -> set[tuple[str, str]]:
    r = Reader(data)
    if r.take(4) != b"\xca\xfe\xba\xbe":
        raise ClassFormatError("bad class magic")
    r.u2()  # minor
    major = r.u2()
    if major != 52:
        raise ClassFormatError(f"expected Java 8 classfile, got major={major}")
    cp_count = r.u2()
    utf8: dict[int, str] = {}
    i = 1
    while i < cp_count:
        tag = r.u1()
        if tag == 1:  # Utf8
            n = r.u2()
            utf8[i] = r.take(n).decode("utf-8", "replace")
        elif tag in (3, 4):
            r.take(4)
        elif tag in (5, 6):
            r.take(8)
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            r.take(2)
        elif tag in (9, 10, 11, 12, 17, 18):
            r.take(4)
        elif tag == 15:
            r.take(3)
        else:
            raise ClassFormatError(f"unsupported constant-pool tag {tag} at {i}")
        i += 1

    r.take(6)  # access, this, super
    interfaces = r.u2()
    r.take(interfaces * 2)
    fields = r.u2()
    for _ in range(fields):
        r.take(6)
        skip_attributes(r, r.u2())

    methods: set[tuple[str, str]] = set()
    method_count = r.u2()
    for _ in range(method_count):
        r.u2()  # access
        name_idx = r.u2()
        desc_idx = r.u2()
        name = utf8.get(name_idx)
        desc = utf8.get(desc_idx)
        if name is None or desc is None:
            raise ClassFormatError("method name/descriptor missing from UTF8 pool")
        methods.add((name, desc))
        skip_attributes(r, r.u2())
    return methods


@dataclass(frozen=True)
class GuiContract:
    entry: str
    required_srg: tuple[tuple[str, str], ...]
    forbidden_mcp: tuple[tuple[str, str], ...]


BUTTON = "Lnet/minecraft/client/gui/GuiButton;"
CONTRACTS = (
    GuiContract(
        "dev/acoustic/mc1122/forge/GuiAcousticShaders.class",
        (
            ("func_73866_w_", "()V"),
            ("func_146284_a", f"({BUTTON})V"),
            ("func_73863_a", "(IIF)V"),
            ("func_73864_a", "(III)V"),
            ("func_146273_a", "(IIIJ)V"),
            ("func_146286_b", "(III)V"),
        ),
        (
            ("initGui", "()V"),
            ("actionPerformed", f"({BUTTON})V"),
            ("drawScreen", "(IIF)V"),
            ("mouseClicked", "(III)V"),
            ("mouseClickMove", "(IIIJ)V"),
            ("mouseReleased", "(III)V"),
        ),
    ),
    GuiContract(
        "dev/acoustic/mc1122/forge/GuiAcousticShaderOptions.class",
        (
            ("func_73866_w_", "()V"),
            ("func_146284_a", f"({BUTTON})V"),
            ("func_73863_a", "(IIF)V"),
        ),
        (
            ("initGui", "()V"),
            ("actionPerformed", f"({BUTTON})V"),
            ("drawScreen", "(IIF)V"),
        ),
    ),
    GuiContract(
        "dev/acoustic/mc1122/forge/GuiRuntimeAudio.class",
        (
            ("func_73866_w_", "()V"),
            ("func_146284_a", f"({BUTTON})V"),
            ("func_73863_a", "(IIF)V"),
        ),
        (
            ("initGui", "()V"),
            ("actionPerformed", f"({BUTTON})V"),
            ("drawScreen", "(IIF)V"),
        ),
    ),
)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("jar", type=Path)
    args = ap.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        raise SystemExit(f"ERROR: RFG JAR missing: {jar}")

    checked = 0
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for contract in CONTRACTS:
            if contract.entry not in names:
                raise SystemExit(f"ERROR: RFG JAR missing GUI class {contract.entry}")
            try:
                methods = method_table(z.read(contract.entry))
            except ClassFormatError as exc:
                raise SystemExit(f"ERROR: cannot parse {contract.entry}: {exc}") from exc
            for sig in contract.required_srg:
                if sig not in methods:
                    raise SystemExit(
                        f"ERROR: RFG reobf did not produce SRG override {contract.entry}:{sig[0]}{sig[1]}"
                    )
                checked += 1
            for sig in contract.forbidden_mcp:
                if sig in methods:
                    raise SystemExit(
                        f"ERROR: MCP GUI override survived RFG reobf {contract.entry}:{sig[0]}{sig[1]}"
                    )

    print(f"[PASS] RFG inheritance reobf produced {checked} SRG GUI overrides with no MCP duplicates")


if __name__ == "__main__":
    main()
