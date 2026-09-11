#!/usr/bin/env python3
"""Mechanical package/prefix rewrite. Run from repo root."""
from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {".git", "node_modules", "target", "dist", "_upstream"}
TEXT_EXT = {
    ".java", ".xml", ".yml", ".yaml", ".properties", ".md", ".sql",
    ".imports", ".json", ".txt", ".factories", ".kt",
}

# Order matters (longest / most specific first).
REPLACEMENTS = [
    ("com.xx.cloud.ai", "com.sn68.agent"),
    ("com.xx.framework", "com.sn68.agent.framework"),
    ("spring.ai.xx.data-agent", "spring.ai.agent"),
    ("spring.ai.xx.digital-employee", "spring.ai.agent.digital-employee"),
    ("spring.ai.xx.tool-center", "spring.ai.agent.tool-center"),
    ("spring.ai.xx.task.scheduler", "spring.ai.agent.task.scheduler"),
    ("spring.ai.xx.runtime", "spring.ai.agent.runtime"),
    ("data-agent.temporal", "agent.temporal"),
    ("spring.ai.xx.", "spring.ai.agent."),
]


def should_touch(path: Path) -> bool:
    if path.suffix.lower() not in TEXT_EXT and path.name != "org.springframework.boot.autoconfigure.AutoConfiguration.imports":
        return False
    return True


def rewrite_file(path: Path) -> bool:
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = path.read_text(encoding="utf-8", errors="ignore")
    original = text
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def move_tree(src: Path, dst: Path) -> None:
    if not src.exists():
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        return
    src.rename(dst)


changed = 0
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for name in filenames:
        path = Path(dirpath) / name
        if should_touch(path) and rewrite_file(path):
            changed += 1

# Relocate Java trees after text rewrite.
move_tree(
    ROOT / "backend/src/main/java/com/xx/cloud/ai",
    ROOT / "backend/src/main/java/com/sn68/agent",
)
move_tree(
    ROOT / "backend/src/test/java/com/xx/cloud/ai",
    ROOT / "backend/src/test/java/com/sn68/agent",
)
move_tree(
    ROOT / "framework-lite/_upstream/common-framework-core/src/main/java/com/xx/framework",
    ROOT / "framework-lite/src/main/java/com/sn68/agent/framework",
)

print(f"rewrote {changed} files")
