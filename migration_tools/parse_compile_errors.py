#!/usr/bin/env python3
"""
Parse javac error output from `gradlew compileJava` into a structured report,
grouped by missing symbol, so the migration can be tracked/prioritized instead of
scrolling through a wall of stack traces.

Usage:
    # Run gradlew compileJava itself and parse its output
    python migration_tools/parse_compile_errors.py --run

    # Or parse an already-captured log file
    python migration_tools/parse_compile_errors.py --log gradle_compile.log

Output:
    Prints a summary table (symbol -> occurrence count -> example files) to stdout
    and writes the full structured data to migration_tools/reports/compile_errors.json
"""
import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
REPORTS_DIR = Path(__file__).resolve().parent / "reports"

# Matches e.g.:
# C:\repos\...\Foo.java:42: error: cannot find symbol
ERROR_LINE_RE = re.compile(r"^(?P<file>.+\.java):(?P<line>\d+): error: (?P<message>.+)$")
SYMBOL_LINE_RE = re.compile(r"^\s*symbol:\s+(?P<kind>\S+)\s+(?P<name>.+)$")
LOCATION_LINE_RE = re.compile(r"^\s*location:\s+(?P<location>.+)$")


def run_gradle_compile(gradlew):
    """Run gradlew compileJava and return combined stdout+stderr text."""
    cmd = [gradlew, "compileJava", "--stacktrace"]
    proc = subprocess.run(
        cmd,
        cwd=str(REPO_ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return proc.stdout


def parse_errors(text):
    """
    Parse javac error blocks out of raw gradle/javac output.

    Returns a list of dicts: {file, line, message, symbol, location}
    """
    lines = text.splitlines()
    errors = []
    i = 0
    while i < len(lines):
        m = ERROR_LINE_RE.match(lines[i])
        if not m:
            i += 1
            continue

        file_path = m.group("file")
        line_no = int(m.group("line"))
        message = m.group("message")

        symbol = None
        location = None
        # Look ahead a few lines for "symbol:" / "location:" (javac prints these
        # right after the offending source line + caret line).
        for j in range(i + 1, min(i + 6, len(lines))):
            sym_m = SYMBOL_LINE_RE.match(lines[j])
            if sym_m:
                symbol = sym_m.group("name").strip()
                continue
            loc_m = LOCATION_LINE_RE.match(lines[j])
            if loc_m:
                location = loc_m.group("location").strip()
                continue
            # Stop scanning ahead once we hit the next error/file or a blank line
            # after we've already found something, to avoid bleeding into the next
            # error block.
            if ERROR_LINE_RE.match(lines[j]):
                break

        # Fall back to extracting a symbol name from the message itself, e.g.
        # "cannot find symbol" alone isn't useful, but messages like
        # "package X does not exist" or "type X does not take parameters" are.
        if symbol is None:
            pkg_m = re.search(r"package (\S+) does not exist", message)
            generic_m = re.search(r"type (\S+) does not take parameters", message)
            if pkg_m:
                symbol = pkg_m.group(1)
            elif generic_m:
                symbol = generic_m.group(1)
            else:
                symbol = message

        errors.append({
            "file": file_path,
            "line": line_no,
            "message": message,
            "symbol": symbol,
            "location": location,
        })
        i += 1

    return errors


def group_by_symbol(errors):
    groups = defaultdict(list)
    for e in errors:
        groups[e["symbol"]].append(e)
    # Sort groups by occurrence count, descending
    return dict(sorted(groups.items(), key=lambda kv: len(kv[1]), reverse=True))


def print_summary(groups):
    total = sum(len(v) for v in groups.values())
    print(f"\n{total} compile error(s) across {len(groups)} distinct symbol(s)\n")
    print(f"{'count':>5}  symbol")
    print(f"{'-----':>5}  ------")
    for symbol, occurrences in groups.items():
        print(f"{len(occurrences):>5}  {symbol}")
        example_files = sorted({Path(o['file']).name for o in occurrences})[:3]
        print(f"       e.g. in: {', '.join(example_files)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--run", action="store_true", help="Run `gradlew compileJava` and parse its output")
    group.add_argument("--log", type=str, help="Path to an already-captured compile log to parse")
    parser.add_argument(
        "--gradlew",
        type=str,
        default=str(REPO_ROOT / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew")),
        help="Path to the gradlew wrapper script (only used with --run)",
    )
    args = parser.parse_args()

    if args.run:
        print("Running gradlew compileJava (this can take a while)...", file=sys.stderr)
        text = run_gradle_compile(args.gradlew)
    else:
        text = Path(args.log).read_text(encoding="utf-8", errors="replace")

    errors = parse_errors(text)
    groups = group_by_symbol(errors)
    print_summary(groups)

    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = REPORTS_DIR / "compile_errors.json"
    out_path.write_text(json.dumps({"errors": errors, "groups": groups}, indent=2), encoding="utf-8")
    print(f"\nFull report written to {out_path}")


if __name__ == "__main__":
    main()
