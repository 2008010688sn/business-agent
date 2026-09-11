#!/usr/bin/env python3
"""Fail if company / private-stack tokens appear outside NOTICE."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {
    ".git",
    "node_modules",
    "target",
    "dist",
    ".idea",
    "docs",  # extraction notes may mention history
}
SKIP_FILES = {"NOTICE", "LICENSE"}
# NOTICE may mention upstream; docs/config-gap may mention Nacos as a migration note.
PATTERNS = [
    re.compile(r"com\.xx\.", re.I),
    re.compile(r"xx-cloud", re.I),
    re.compile(r"xiangxiang", re.I),
    re.compile(r"箱箱云"),
    re.compile(r"suning@", re.I),
    re.compile(r"nacos:", re.I),
    re.compile(r"cn\.dev33\.satoken"),
    re.compile(r"xx-cloud-zeus"),
    re.compile(r"demandCreateExecute"),
    re.compile(r"v4_iam"),
]

hits: list[str] = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for name in filenames:
        if name in SKIP_FILES:
            continue
        path = Path(dirpath) / name
        if path.suffix.lower() in {".jar", ".class", ".png", ".jpg", ".ico", ".woff", ".woff2"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for pat in PATTERNS:
            if pat.search(text):
                rel = path.relative_to(ROOT)
                hits.append(f"{rel}: {pat.pattern}")
                break

if hits:
    print("brand-scan failed:")
    for line in hits[:80]:
        print(" ", line)
    if len(hits) > 80:
        print(f"  ... {len(hits) - 80} more")
    sys.exit(1)
print("brand-scan ok")
