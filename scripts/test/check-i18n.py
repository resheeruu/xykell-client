#!/usr/bin/env python3
"""Host i18n gate: every values-XX/strings.xml must mirror values/.

Checks per locale:
  1. XML well-formed, utf-8
  2. exact same key set as the default strings.xml
  3. identical positional placeholder multiset per key (%1$s, %s, %d, %.1f...)
  4. no raw ASCII apostrophe (must be \'), no raw leading @ or ?
  5. no leftover translator markers (TODO / FIXME / <<<)
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app/src/main/res"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sdfx]|%\.\d+f|%1\$\.\d+f")


def strings_of(path: Path):
    root = ET.parse(path).getroot()
    out = {}
    for el in root:
        if el.tag == "string" and el.get("name"):
            out[el.get("name")] = el.text or ""
    return out


def main() -> int:
    base_path = RES / "values" / "strings.xml"
    base = strings_of(base_path)
    failures = []
    locales = sorted(d for d in RES.glob("values-*") if (d / "strings.xml").is_file())
    if not locales:
        print("i18n: no values-* locale directories found", file=sys.stderr)
        return 1
    for d in locales:
        loc = d.name
        try:
            trans = strings_of(d / "strings.xml")
        except ET.ParseError as e:
            failures.append(f"{loc}: XML parse error: {e}")
            continue
        missing = set(base) - set(trans)
        extra = set(trans) - set(base)
        if missing:
            failures.append(f"{loc}: {len(missing)} missing keys, e.g. {sorted(missing)[:5]}")
        if extra:
            failures.append(f"{loc}: {len(extra)} extra keys, e.g. {sorted(extra)[:5]}")
        for k in sorted(set(base) & set(trans)):
            t = trans[k]
            if PLACEHOLDER.findall(base[k]) != PLACEHOLDER.findall(t):
                failures.append(
                    f"{loc}: placeholders differ in '{k}': "
                    f"{PLACEHOLDER.findall(base[k])} vs {PLACEHOLDER.findall(t)}"
                )
            if re.search(r"(?<!\\)'", t):
                failures.append(f"{loc}: raw apostrophe in '{k}' (escape as \\' )")
            if t and t[0] in "@?":
                failures.append(f"{loc}: unescaped leading '{t[0]}' in '{k}'")
            if "TODO" in t or "FIXME" in t or "<<<" in t:
                failures.append(f"{loc}: translator marker left in '{k}'")
    if failures:
        print(f"i18n: FAIL ({len(failures)} problems)", file=sys.stderr)
        for f in failures:
            print("  " + f, file=sys.stderr)
        return 1
    print(f"i18n: PASS ({len(locales)} locales x {len(base)} keys)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
