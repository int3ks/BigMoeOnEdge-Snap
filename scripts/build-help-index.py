#!/usr/bin/env python3
"""Builds assets/help/help.json: the knowledge the in-app Help tool (app_help) searches.

Sources: docs/*.md and docs/wiki/*.md (not docs/archive), CHANGELOG.md, and the longer settings texts of
assets/language/english.json. Markdown is cut into chunks of roughly 300 tokens along headings and list items.
Run it after changing docs, the CHANGELOG or UI texts (after scripts/extract-strings.py), like extract-strings.py.
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/help/help.json"
MAX = 1100  # characters per chunk

def split_long(text):
    if len(text) <= MAX * 1.6:
        return [text]
    out, cur = [], ""
    for s in re.split(r"(?<=[.!?])\s+", text):
        if len(cur) + len(s) > MAX and cur:
            out.append(cur); cur = ""
        cur = (cur + " " + s).strip()
    if cur:
        out.append(cur)
    return out

def chunks_of(md, source):
    heading, units, res = source, [], []
    def flush(unit_list, head):
        cur = ""
        for u in unit_list:
            if cur and len(cur) + len(u) > MAX:
                res.append((source, head, cur.strip())); cur = ""
            cur += ("\n" if cur else "") + u
        if cur.strip():
            res.append((source, head, cur.strip()))
    for block in re.split(r"\n(?=#{1,4} )", md):
        lines = block.split("\n")
        if lines and lines[0].startswith("#"):
            heading = lines[0].lstrip("# ").strip() or heading
            lines = lines[1:]
        units, cur = [], []
        for ln in lines:
            if re.match(r"^\s*([-*]|\d+\.) ", ln) and cur:
                units.append("\n".join(cur)); cur = []
            if ln.strip() == "" and cur:
                units.append("\n".join(cur)); cur = []
                continue
            if ln.strip():
                cur.append(ln)
        if cur:
            units.append("\n".join(cur))
        flat = []
        for u in units:
            flat.extend(split_long(u))
        flush(flat, heading)
    return res

def main():
    items = []
    files = sorted((ROOT / "docs").glob("*.md")) + sorted((ROOT / "docs/wiki").glob("*.md"))
    for f in files:
        items += chunks_of(f.read_text(encoding="utf-8"), f"docs/{f.relative_to(ROOT / 'docs')}")
    items += chunks_of((ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), "CHANGELOG")
    strings = json.loads((ROOT / "app/src/main/assets/language/english.json").read_text(encoding="utf-8"))
    texts = strings.get("strings", strings) if isinstance(strings, dict) else {}
    for key in texts:
        if len(key) >= 70:
            items.append(("settings text", "Settings text", key))
    data = [{"s": s, "h": h, "t": t} for s, h, t in items]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{len(data)} chunks, {OUT.stat().st_size // 1024} KiB -> {OUT.relative_to(ROOT)}")

if __name__ == "__main__":
    sys.exit(main())
