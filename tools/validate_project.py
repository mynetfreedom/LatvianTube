#!/usr/bin/env python3
"""
LatvianTube — static project validator.

This machine has no JDK and no Android SDK (see ANALYSIS.md), so the Android
module cannot be compiled. This script is the substitute: it cannot prove the
project compiles, but it does catch the whole class of error that an author
working without a compiler actually makes — a referenced resource that does not
exist, a malformed XML file, a package that disagrees with its directory, or a
weight in the Java filter that has drifted from the verified JavaScript one.

It is explicitly NOT a compiler. It does not type-check, resolve imports, or
verify that a method exists on a class. Where API existence was uncertain during
development it was checked against the androidx source directly instead; see
ANALYSIS.md, evidence group E7.

Run:  python3 tools/validate_project.py
Exit: 0 if no errors, 1 otherwise.
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app")
RES = os.path.join(APP, "src", "main", "res")
JAVA = os.path.join(APP, "src", "main", "java")
ASSETS = os.path.join(APP, "src", "main", "assets")

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def rel(p: str) -> str:
    return os.path.relpath(p, ROOT)


# --------------------------------------------------------------------------
# 1. XML well-formedness
# --------------------------------------------------------------------------
def check_xml() -> None:
    targets = [os.path.join(APP, "src", "main", "AndroidManifest.xml")]
    for base, _dirs, files in os.walk(RES):
        targets += [os.path.join(base, f) for f in files if f.endswith(".xml")]

    for path in targets:
        try:
            ET.parse(path)
        except ET.ParseError as e:
            err(f"XML malformed: {rel(path)}: {e}")
    print(f"  XML files parsed: {len(targets)}")


# --------------------------------------------------------------------------
# 2. JSON well-formedness (assets + the shared registry)
# --------------------------------------------------------------------------
def check_json() -> None:
    count = 0
    for base, _dirs, files in os.walk(ASSETS):
        for f in files:
            if not f.endswith(".json"):
                continue
            path = os.path.join(base, f)
            try:
                json.load(open(path, encoding="utf-8"))
                count += 1
            except Exception as e:
                err(f"JSON malformed: {rel(path)}: {e}")
    print(f"  JSON files parsed: {count}")


# --------------------------------------------------------------------------
# 3. Resource inventory + R.* reference resolution
# --------------------------------------------------------------------------
def collect_resources() -> dict:
    defined = {"string": set(), "color": set(), "style": set(),
               "layout": set(), "drawable": set(), "id": set(), "array": set()}

    strings = os.path.join(RES, "values", "strings.xml")
    if os.path.exists(strings):
        for el in ET.parse(strings).getroot():
            if el.tag in ("string", "array", "integer-array", "string-array"):
                defined["array" if "array" in el.tag else "string"].add(el.get("name"))

    colors = os.path.join(RES, "values", "colors.xml")
    if os.path.exists(colors):
        for el in ET.parse(colors).getroot():
            if el.tag == "color":
                defined["color"].add(el.get("name"))

    themes = os.path.join(RES, "values", "themes.xml")
    if os.path.exists(themes):
        for el in ET.parse(themes).getroot():
            if el.tag == "style":
                # R.style.AppTheme_Leanback is the flattened form of "AppTheme.Leanback".
                defined["style"].add(el.get("name").replace(".", "_"))

    for base, _dirs, files in os.walk(RES):
        folder = os.path.basename(base)
        for f in files:
            stem, ext = os.path.splitext(f)
            if folder.startswith("layout") and ext == ".xml":
                defined["layout"].add(stem)
            elif (folder.startswith("drawable") or folder.startswith("mipmap")):
                if ext == ".xml" or ext == ".png" or ext == ".webp":
                    defined["drawable"].add(stem)
            if ext == ".xml":
                try:
                    text = open(os.path.join(base, f), encoding="utf-8").read()
                except Exception:
                    continue
                for m in re.finditer(r'@\+id/(\w+)', text):
                    defined["id"].add(m.group(1))
    return defined


def check_java_references(defined: dict) -> None:
    pattern = re.compile(r'(?<![\w.])R\.(string|color|style|layout|drawable|id|array)\.(\w+)')
    checked = 0
    for base, _dirs, files in os.walk(JAVA):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(base, f)
            text = open(path, encoding="utf-8").read()
            for m in pattern.finditer(text):
                kind, name = m.group(1), m.group(2)
                checked += 1
                if name not in defined[kind]:
                    err(f"Undefined resource: R.{kind}.{name} in {rel(path)}")
    print(f"  Java R.* references checked: {checked}")


def check_manifest_references(defined: dict) -> None:
    path = os.path.join(APP, "src", "main", "AndroidManifest.xml")
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r'@(string|color|style|drawable|layout|mipmap)/([\w.]+)', text):
        kind, name = m.group(1), m.group(2)
        lookup = name.replace(".", "_") if kind == "style" else name
        kind = "drawable" if kind == "mipmap" else kind
        if lookup not in defined[kind]:
            err(f"Manifest references undefined @{kind}/{name}")


def check_layout_references(defined: dict) -> None:
    for base, _dirs, files in os.walk(RES):
        if not os.path.basename(base).startswith("layout"):
            continue
        for f in files:
            if not f.endswith(".xml"):
                continue
            path = os.path.join(base, f)
            text = open(path, encoding="utf-8").read()
            for m in re.finditer(r'"@(string|color|style|drawable)/([\w.]+)"', text):
                kind, name = m.group(1), m.group(2)
                lookup = name.replace(".", "_") if kind == "style" else name
                if lookup not in defined[kind]:
                    err(f"Layout {rel(path)} references undefined @{kind}/{name}")


# --------------------------------------------------------------------------
# 4. Java structure: package matches directory, balanced braces
# --------------------------------------------------------------------------
def strip_java_literals(text: str) -> str:
    """
    Remove comments and string/char literals in a single left-to-right pass.

    A regex-based approach cannot do this correctly: stripping `//` comments
    before strings turns the `//` in `"https://..."` into a comment and silently
    deletes the rest of the line, which then reports every URL-bearing file as
    having unbalanced braces. That is exactly the false positive this scanner
    exists to avoid.
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c in "\"'":
            quote = c
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                i += 1
            out.append('""')
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_java_structure() -> None:
    count = 0
    for base, _dirs, files in os.walk(JAVA):
        for f in files:
            if not f.endswith(".java"):
                continue
            count += 1
            path = os.path.join(base, f)
            text = open(path, encoding="utf-8").read()

            pkg = re.search(r'^package\s+([\w.]+);', text, re.M)
            if not pkg:
                err(f"No package declaration: {rel(path)}")
                continue
            expected = os.path.relpath(base, JAVA).replace(os.sep, ".")
            if pkg.group(1) != expected:
                err(f"Package/dir mismatch: {rel(path)} declares {pkg.group(1)}, expected {expected}")

            stripped = strip_java_literals(text)
            for open_c, close_c, label in (("{", "}", "braces"), ("(", ")", "parens")):
                if stripped.count(open_c) != stripped.count(close_c):
                    err(f"Unbalanced {label} in {rel(path)}: "
                        f"{stripped.count(open_c)} vs {stripped.count(close_c)}")
    print(f"  Java files checked: {count}")


# --------------------------------------------------------------------------
# 5. Filter parity: the Java weights must equal the verified JavaScript ones
# --------------------------------------------------------------------------
def check_filter_parity() -> None:
    js_path = os.path.join(ROOT, "tools", "latvian-filter.mjs")
    java_path = os.path.join(JAVA, "lv", "latviantube", "data", "filter",
                             "LatvianLanguageFilter.java")
    if not (os.path.exists(js_path) and os.path.exists(java_path)):
        warn("Filter parity skipped: source file missing")
        return

    js = open(js_path, encoding="utf-8").read()
    java = open(java_path, encoding="utf-8").read()

    mapping = {
        "exclusiveDiacritic": "W_EXCLUSIVE_DIACRITIC",
        "sharedDiacritic": "W_SHARED_DIACRITIC",
        "diacriticCap": "W_DIACRITIC_CAP",
        "stopwordPerHit": "W_STOPWORD",
        "stopwordCap": "W_STOPWORD_CAP",
        "curatedChannel": "W_CURATED_CHANNEL",
        "cyrillic": "W_CYRILLIC",
        "russianStopwordPerHit": "W_RUSSIAN_STOPWORD",
        "russianStopwordCap": "W_RUSSIAN_STOPWORD_CAP",
        "englishMarkerPerHit": "W_ENGLISH_MARKER",
        "englishMarkerCap": "W_ENGLISH_MARKER_CAP",
    }
    for js_name, java_name in mapping.items():
        jm = re.search(rf'{js_name}:\s*(-?\d+)', js)
        jv = re.search(rf'{java_name}\s*=\s*(-?\d+)', java)
        if not jm or not jv:
            err(f"Filter parity: could not read {js_name} / {java_name}")
            continue
        if jm.group(1) != jv.group(1):
            err(f"Filter weight drift: {js_name}={jm.group(1)} vs {java_name}={jv.group(1)}")

    jt = re.search(r'ACCEPT_THRESHOLD\s*=\s*(\d+)', js)
    vt = re.search(r'ACCEPT_THRESHOLD\s*=\s*(\d+)', java)
    if jt and vt and jt.group(1) != vt.group(1):
        err(f"Filter threshold drift: JS {jt.group(1)} vs Java {vt.group(1)}")

    # The stopword vocabulary must match too, or the two implementations
    # silently disagree about which videos are Latvian. Comments are stripped
    # first so that prose containing quoted examples is not read as vocabulary.
    js_words = set(re.findall(r"'([^']+)'", js.split("LATVIAN_STOPWORDS = [")[1].split("];")[0]))
    raw_block = java.split("LATVIAN_STOPWORDS = new HashSet")[1].split("));")[0]
    raw_block = re.sub(r"//[^\n]*", "", raw_block)
    java_words = set(re.findall(r'"([^"]+)"', raw_block))
    if js_words != java_words:
        warn(f"Stopword vocabularies differ: JS-only={sorted(js_words - java_words)[:8]}, "
             f"Java-only={sorted(java_words - js_words)[:8]}")
    print(f"  Filter parity: weights compared, stopwords JS={len(js_words)} Java={len(java_words)}")


# --------------------------------------------------------------------------
# 6. Registry cross-check: the JSON asset is the single source of truth
# --------------------------------------------------------------------------
def check_registry() -> None:
    path = os.path.join(ASSETS, "channels_lv.json")
    try:
        data = json.load(open(path, encoding="utf-8"))
    except Exception as e:
        err(f"Registry unreadable: {e}")
        return
    ids = []
    for cat in data.get("categories", []):
        for ch in cat.get("channels", []):
            ids.append(ch["id"])
            if not re.fullmatch(r"UC[\w-]{22}", ch["id"]):
                err(f"Registry channel id looks malformed: {ch['id']} ({ch.get('title')})")
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        err(f"Duplicate channel ids in registry: {sorted(dupes)}")
    excluded = {e["channelId"] for e in data.get("excluded", [])}
    overlap = excluded & set(ids)
    if overlap:
        err(f"Channel both curated and excluded: {sorted(overlap)}")
    print(f"  Registry: {len(ids)} channels in {len(data.get('categories', []))} categories, "
          f"{len(excluded)} documented exclusions")


def main() -> int:
    print("LatvianTube static validation\n")
    check_xml()
    check_json()
    defined = collect_resources()
    print(f"  Resources: {sum(len(v) for v in defined.values())} defined "
          f"({', '.join(f'{k}={len(v)}' for k, v in defined.items())})")
    check_java_references(defined)
    check_manifest_references(defined)
    check_layout_references(defined)
    check_java_structure()
    check_filter_parity()
    check_registry()

    print()
    if warnings:
        print(f"WARNINGS ({len(warnings)}):")
        for w in warnings:
            print(f"  ! {w}")
        print()
    if errors:
        print(f"ERRORS ({len(errors)}):")
        for e in errors:
            print(f"  x {e}")
        return 1
    print("No errors found. (Note: this is a static check, not a compilation.)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
