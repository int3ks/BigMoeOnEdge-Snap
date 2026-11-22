#!/usr/bin/env python3
"""Collects the app's translatable texts into app/src/main/assets/language/english.json.

A text is translatable when it reaches I18n.t(): written as t("...") anywhere, or passed as a literal to the settings
helpers that translate internally (Hint, Section, SwitchRow, ChoiceSetting and its option labels, PerformanceGroup,
SettingsFoldout) or as a TopTab label. Literals with $ (Kotlin templates) are skipped: such texts must use {0}.
Run after adding texts: python3 scripts/extract-strings.py
"""
import json, os, re, sys

ROOT = os.path.join(os.path.dirname(__file__), '..')
SRC = os.path.join(ROOT, 'app/src/main/java')
OUT = os.path.join(ROOT, 'app/src/main/assets/language/english.json')
LIT = r'"((?:[^"\\\n]|\\.)*)"'
# A call whose following literals (up to n) are texts.
CALLS = {r'\bt\(': 1, r'\bHint\(': 1, r'\bSection\(': 1, r'\bSwitchRow\(': 2, r'\bChoiceSetting\(': 1,
         r'\bPerformanceGroup\(': 2}
KEYED = [r'\btitle\s*=\s*', r'\bsubtitle\s*=\s*', r'\bto\s+']

def unescape(s):
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), s)

def texts(code):
    found = []
    for call, n in CALLS.items():
        for m in re.finditer(call, code):
            rest = code[m.end():m.end() + 4000]
            for _ in range(n):
                lm = re.match(r'\s*' + LIT + r'\s*,?', rest)
                if not lm:
                    break
                found.append(lm.group(1))
                rest = rest[lm.end():]
    for k in KEYED:
        found += [m.group(1) for m in re.finditer(k + LIT, code)]
    found += [m.group(1) for m in re.finditer(r'^\s*[A-Z_]+\(' + LIT + r',\s*Icons', code, re.M)]
    return found

keys = set()
for d, _, files in os.walk(SRC):
    for f in files:
        if f.endswith('.kt'):
            for s in texts(open(os.path.join(d, f), encoding='utf-8').read()):
                if '$' in s or not re.search('[A-Za-z]{2}', s):
                    continue
                keys.add(unescape(s))
os.makedirs(os.path.dirname(OUT), exist_ok=True)
data = {"name": "English", "lang": "en", "strings": {k: k for k in sorted(keys, key=str.lower)}}
with open(OUT, 'w', encoding='utf-8') as fh:
    json.dump(data, fh, ensure_ascii=False, indent=2)
    fh.write('\n')
print(f"{len(keys)} texts -> {os.path.relpath(OUT, ROOT)}", file=sys.stderr)
