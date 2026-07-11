#!/usr/bin/env python3
"""
Bulk-apply confirmed old-symbol -> new-symbol renames across the Java source tree,
based on a JSON mapping file (see renames.json).

Safe by default: prints a dry-run diff-style summary and writes nothing unless
--apply is passed. Always review the dry-run output (or better, `git diff` after
--apply) before committing.

For each "old.fully.Qualified.Name" -> "new.fully.Qualified.Name" mapping entry:
  1. In any file that has `import old.fully.Qualified.Name;`, that import line is
     rewritten to `import new.fully.Qualified.Name;`.
  2. If the simple class name changed too (e.g. ResourceLocation -> Identifier),
     whole-word occurrences of the old simple name elsewhere in that same file are
     replaced with the new simple name. This is scoped to files that actually had
     the import, to avoid clobbering unrelated identifiers that happen to share a
     name elsewhere in the codebase.
  3. Fully-qualified inline usages (old FQN used directly without an import) are
     also replaced, file-wide, since they unambiguously reference the symbol.

Entries whose value is empty/"" or starts with "TODO" are skipped (not yet
resolved) - fill them in via find_candidates.py first. Entries starting with
"NOT_A_RENAME" are also skipped - these document symbols that needed a real API-shape
fix (not a plain rename) applied directly to specific files; see renames.json and
docs/migration-26.1-plan.md for details.

Usage:
    python migration_tools/bulk_rename.py                  # dry run
    python migration_tools/bulk_rename.py --apply          # actually write changes
    python migration_tools/bulk_rename.py --mapping foo.json --src src/main/java
"""
import argparse
import json
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MAPPING = Path(__file__).resolve().parent / "renames.json"
DEFAULT_SRC = REPO_ROOT / "src"


def load_mapping(path):
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    mapping = {}
    for old_fqn, new_fqn in raw.items():
        if old_fqn.startswith("_"):
            continue  # allow "_comment"-style metadata keys
        value = str(new_fqn).strip().upper()
        if not new_fqn or value.startswith("TODO") or value.startswith("NOT_A_RENAME"):
            continue
        mapping[old_fqn] = new_fqn
    return mapping


def import_line_re(fqn):
    return re.compile(r"^import\s+" + re.escape(fqn) + r"\s*;\s*$", re.MULTILINE)


def word_re(name):
    return re.compile(r"\b" + re.escape(name) + r"\b")


def apply_rename_to_text(text, old_fqn, new_fqn):
    """Returns (new_text, replacement_count) for a single mapping applied to one file's text."""
    old_simple = old_fqn.rsplit(".", 1)[-1]
    new_simple = new_fqn.rsplit(".", 1)[-1]
    count = 0

    # 1. Import line rewrite
    import_re = import_line_re(old_fqn)
    new_text, n = import_re.subn(f"import {new_fqn};", text)
    had_import = n > 0
    count += n
    text = new_text

    # 2. Fully-qualified inline usage (e.g. `net.minecraft.resources.ResourceLocation.parse(...)`)
    fqn_re = word_re(old_fqn)
    text, n = fqn_re.subn(new_fqn, text)
    count += n

    # 3. Bare simple-name usages, only in files that had the import and only if the
    #    simple name actually changed.
    if had_import and old_simple != new_simple:
        simple_re = word_re(old_simple)
        text, n = simple_re.subn(new_simple, text)
        count += n

    return text, count


def iter_java_files(src_root):
    return sorted(src_root.rglob("*.java"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mapping", type=str, default=str(DEFAULT_MAPPING), help="Path to the renames JSON mapping file")
    parser.add_argument("--src", type=str, default=str(DEFAULT_SRC), help="Source root to scan (default: src/)")
    parser.add_argument("--apply", action="store_true", help="Write changes to disk (default is dry-run)")
    args = parser.parse_args()

    mapping = load_mapping(args.mapping)
    if not mapping:
        print(
            f"No resolved entries in {args.mapping} yet (all empty/TODO). "
            "Use find_candidates.py to figure out the new names first, then fill them in."
        )
        return

    print(f"{len(mapping)} resolved rename(s) loaded from {args.mapping}:")
    for old_fqn, new_fqn in mapping.items():
        print(f"  {old_fqn}  ->  {new_fqn}")
    print()

    src_root = Path(args.src)
    java_files = iter_java_files(src_root)

    total_files_changed = 0
    total_replacements = 0

    for file_path in java_files:
        original_text = file_path.read_text(encoding="utf-8")
        text = original_text
        file_replacements = 0

        for old_fqn, new_fqn in mapping.items():
            text, count = apply_rename_to_text(text, old_fqn, new_fqn)
            file_replacements += count

        if file_replacements > 0:
            total_files_changed += 1
            total_replacements += file_replacements
            rel = file_path.relative_to(REPO_ROOT)
            print(f"{'[APPLY]' if args.apply else '[DRY-RUN]'} {rel}: {file_replacements} replacement(s)")
            if args.apply:
                file_path.write_text(text, encoding="utf-8")

    print(f"\n{total_files_changed} file(s), {total_replacements} total replacement(s).")
    if not args.apply:
        print("Dry run only - nothing was written. Re-run with --apply once you've reviewed this.")


if __name__ == "__main__":
    main()
