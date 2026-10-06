#!/usr/bin/env python3
"""Regenerates GhosttyKey.kt from the libghostty-vt headers.

The enum values are obtained by compiling a tiny C program against the real
header (with `zig cc`), so the Kotlin constants can never drift from the ABI.

Usage: native/scripts/gen-kotlin-keys.py   (run from anywhere)
"""
import os
import re
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INCLUDE = os.path.join(ROOT, "native", "vendor", "ghostty", "include")
OUT = os.path.join(ROOT, "src", "main", "kotlin", "com", "github", "nihaiden",
                   "ghostty", "vt", "GhosttyKey.kt")


def main():
    header = open(os.path.join(INCLUDE, "ghostty", "vt", "key", "event.h")).read()
    start = header.index("GHOSTTY_KEY_UNIDENTIFIED")
    body = header[start:header.index("} GhosttyKey;")]
    names = [n for n in re.findall(r"(GHOSTTY_KEY_[A-Z0-9_]+)", body)
             if n != "GHOSTTY_KEY_MAX_VALUE"]
    names = list(dict.fromkeys(names))

    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "keys.c")
        exe = os.path.join(tmp, "keys")
        with open(src, "w") as f:
            f.write("#include <stdio.h>\n#include <ghostty/vt.h>\nint main(void){\n")
            for n in names:
                f.write(f'printf("%s %d\\n", "{n}", (int){n});\n')
            f.write("return 0;}\n")
        subprocess.check_call(["zig", "cc", "-DGHOSTTY_STATIC", "-I", INCLUDE, src, "-o", exe])
        rows = [l.split() for l in subprocess.check_output([exe]).decode().splitlines() if l]

    lines = [
        "package com.github.nihaiden.ghostty.vt",
        "",
        "/**",
        " * GhosttyKey values from ghostty/vt/key/event.h (W3C UI Events KeyboardEvent.code).",
        " *",
        " * Generated from the libghostty-vt headers by native/scripts/gen-kotlin-keys.py;",
        " * do not edit by hand.",
        " */",
        '@Suppress("unused")',
        "object GhosttyKey {",
    ]
    lines += [f"    const val {n[len('GHOSTTY_KEY_'):]}: Int = {v}" for n, v in rows]
    lines.append("}")
    with open(OUT, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"wrote {len(rows)} keys to {OUT}")


if __name__ == "__main__":
    main()
