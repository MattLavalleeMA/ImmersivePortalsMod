#!/usr/bin/env python3
"""
Search the real, unobfuscated 26.1.2 Minecraft jar for candidate replacement class
names for symbols that no longer resolve (moved/renamed/removed classes found by
parse_compile_errors.py).

Since Minecraft 26.1+ ships unobfuscated, the class names inside the merged jar in
Loom's cache ARE the real Mojang names - this is ground truth, no decompiling or
guessing from changelogs required.

Usage:
    # Look up one or more symbols (simple class names) by fuzzy + substring match
    python migration_tools/find_candidates.py ResourceLocation Util GameRules

    # Point at a specific jar instead of auto-discovering it
    python migration_tools/find_candidates.py LightTexture --jar path/to/minecraft.jar

    # Only substring search (faster, useful for very short/common names)
    python migration_tools/find_candidates.py Ticket --no-fuzzy
"""
import argparse
import difflib
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def find_default_jar():
    """
    Auto-discover the merged Minecraft jar for the version configured in
    gradle.properties, under .gradle/loom-cache/minecraftMaven/net/minecraft/.
    """
    props = (REPO_ROOT / "gradle.properties").read_text(encoding="utf-8")
    mc_version = None
    for line in props.splitlines():
        if line.startswith("minecraft_version="):
            mc_version = line.split("=", 1)[1].strip()
            break

    search_root = REPO_ROOT / ".gradle" / "loom-cache" / "minecraftMaven" / "net" / "minecraft"
    if not search_root.exists():
        return None

    candidates = list(search_root.glob(f"**/*-{mc_version}.jar")) if mc_version else []
    if not candidates:
        # Fall back to any merged jar present
        candidates = list(search_root.glob("**/*.jar"))
    if not candidates:
        return None
    # Prefer the largest jar (merged client+server is bigger than any partial jar)
    return max(candidates, key=lambda p: p.stat().st_size)


def list_class_names(jar_path):
    """
    Return a list of (simple_name, fully_qualified_name) for every class in the jar,
    excluding anonymous/synthetic inner classes (those with a purely numeric suffix
    after '$', e.g. Foo$1) since they're not useful rename candidates.
    """
    names = []
    with zipfile.ZipFile(jar_path) as zf:
        for entry in zf.namelist():
            if not entry.endswith(".class"):
                continue
            fqn = entry[:-len(".class")].replace("/", ".")
            simple = fqn.rsplit(".", 1)[-1]
            # Skip fully-numeric anonymous class suffixes like Foo$1, Foo$2$3
            inner_parts = simple.split("$")
            if len(inner_parts) > 1 and all(p.isdigit() for p in inner_parts[1:]):
                continue
            names.append((simple, fqn))
    return names


def search(symbol, all_names, fuzzy=True, limit=15):
    exact = [fqn for simple, fqn in all_names if simple == symbol]
    substring = sorted({
        fqn for simple, fqn in all_names
        if symbol.lower() in simple.lower() and simple != symbol
    })

    fuzzy_matches = []
    if fuzzy:
        simple_names = sorted({simple for simple, _ in all_names})
        close = difflib.get_close_matches(symbol, simple_names, n=limit, cutoff=0.6)
        fqn_by_simple = {}
        for simple, fqn in all_names:
            fqn_by_simple.setdefault(simple, []).append(fqn)
        for simple in close:
            for fqn in fqn_by_simple[simple]:
                fuzzy_matches.append((simple, fqn))

    return exact, substring[:limit], fuzzy_matches[:limit]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("symbols", nargs="+", help="Simple class name(s) to search for, e.g. ResourceLocation")
    parser.add_argument("--jar", type=str, default=None, help="Path to the Minecraft jar (auto-discovered by default)")
    parser.add_argument("--no-fuzzy", action="store_true", help="Skip fuzzy matching, only do substring search")
    parser.add_argument("--limit", type=int, default=15, help="Max results per category")
    args = parser.parse_args()

    jar_path = Path(args.jar) if args.jar else find_default_jar()
    if jar_path is None or not jar_path.exists():
        print(
            "Could not find a Minecraft jar. Run `./gradlew help` at least once so Loom "
            "populates .gradle/loom-cache/minecraftMaven, or pass --jar explicitly.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"Searching {jar_path}\n", file=sys.stderr)
    all_names = list_class_names(jar_path)
    print(f"({len(all_names)} classes indexed)\n", file=sys.stderr)

    for symbol in args.symbols:
        exact, substring, fuzzy_matches = search(symbol, all_names, fuzzy=not args.no_fuzzy, limit=args.limit)
        print(f"=== {symbol} ===")
        if exact:
            print("  Exact simple-name match(es) (unexpected if this was reported missing):")
            for fqn in exact:
                print(f"    {fqn}")
        if substring:
            print("  Substring matches:")
            for fqn in substring:
                print(f"    {fqn}")
        if fuzzy_matches:
            print("  Fuzzy matches:")
            for simple, fqn in fuzzy_matches:
                print(f"    {fqn}")
        if not exact and not substring and not fuzzy_matches:
            print("  No candidates found - the class may have been removed outright, "
                  "or renamed to something unrelated. Try a shorter/partial search term.")
        print()


if __name__ == "__main__":
    main()
