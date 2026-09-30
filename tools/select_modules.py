#!/usr/bin/env python3
"""
Tick / untick the modules compiled into the Ajiriwa and PSBill apps.

    python3 tools/select_modules.py                 # interactive, pick the app first
    python3 tools/select_modules.py ajiriwa         # interactive for one app
    python3 tools/select_modules.py ajiriwa --all   # tick everything
    python3 tools/select_modules.py ajiriwa --only dashboard,sms,properties
    python3 tools/select_modules.py --list          # show current selection

Writes modules.properties at the repo root (comments are preserved); the next
Gradle build compiles only the ticked modules.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPS = ROOT / "modules.properties"
LINE = re.compile(r"^(\s*)([a-z0-9_]+)\.([a-z0-9_]+)\s*=\s*(\S*)\s*$", re.I)
TRUE = {"true", "yes", "1", "on", "x", ""}


def load():
    lines = PROPS.read_text().splitlines()
    apps = {}  # app -> list of [module, enabled, description, line_index]
    last_comment = ""
    for i, line in enumerate(lines):
        m = LINE.match(line)
        if m:
            _, app, module, value = m.groups()
            apps.setdefault(app, []).append([module, value.lower() in TRUE, last_comment, i])
            last_comment = ""
        elif line.strip().startswith("#"):
            last_comment = line.strip().lstrip("#").strip()
        elif not line.strip():
            last_comment = ""
    return lines, apps


def save(lines, apps):
    for app, modules in apps.items():
        for module, enabled, _, idx in modules:
            lines[idx] = f"{app}.{module}={'true' if enabled else 'false'}"
    PROPS.write_text("\n".join(lines) + "\n")


def show(app, modules):
    print(f"\n  {app} modules  ([x] = compiled in)\n")
    for n, (module, enabled, desc, _) in enumerate(modules, 1):
        print(f"   {n:>2}. [{'x' if enabled else ' '}] {module:<12} {desc}")


def interactive(app, modules):
    while True:
        show(app, modules)
        print("\n  numbers = toggle (e.g. 3 5 7)   a = tick all   n = untick all   s = save   q = quit without saving")
        choice = input("  > ").strip().lower()
        if choice == "s":
            if not any(m[1] for m in modules):
                print("  ! tick at least one module")
                continue
            return True
        if choice == "q":
            return False
        if choice == "a":
            for m in modules:
                m[1] = True
            continue
        if choice == "n":
            for m in modules:
                m[1] = False
            continue
        for token in re.split(r"[,\s]+", choice):
            if token.isdigit() and 1 <= int(token) <= len(modules):
                modules[int(token) - 1][1] = not modules[int(token) - 1][1]
            elif token:
                print(f"  ? ignored '{token}'")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("app", nargs="?", help="ajiriwa or psbill")
    ap.add_argument("--all", action="store_true", help="tick every module")
    ap.add_argument("--only", help="comma-separated modules to keep (all others unticked)")
    ap.add_argument("--list", action="store_true", help="print the current selection")
    args = ap.parse_args()

    lines, apps = load()
    if args.list:
        for app, modules in apps.items():
            show(app, modules)
        print()
        return

    app = args.app
    if not app:
        names = list(apps)
        for n, name in enumerate(names, 1):
            print(f"  {n}. {name}")
        pick = input("  Which app? ").strip()
        app = names[int(pick) - 1] if pick.isdigit() and 1 <= int(pick) <= len(names) else pick
    if app not in apps:
        sys.exit(f"Unknown app '{app}'. Known: {', '.join(apps)}")
    modules = apps[app]

    if args.all:
        for m in modules:
            m[1] = True
    elif args.only:
        wanted = {w.strip() for w in args.only.split(",") if w.strip()}
        known = {m[0] for m in modules}
        if wanted - known:
            sys.exit(f"Unknown {app} module(s): {', '.join(sorted(wanted - known))}. Known: {', '.join(sorted(known))}")
        for m in modules:
            m[1] = m[0] in wanted
    elif not interactive(app, modules):
        print("  Nothing saved.")
        return

    save(lines, apps)
    kept = [m[0] for m in modules if m[1]]
    print(f"\n  Saved. {app} will compile: {', '.join(kept)}")
    print(f"  Build:  ./gradlew :{app}:assembleDebug   (or assembleRelease)\n")


if __name__ == "__main__":
    main()
