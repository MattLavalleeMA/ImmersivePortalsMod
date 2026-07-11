#!/usr/bin/env python3
"""
Disassemble a class's public API (fields + method signatures, including generics)
straight from a jar using the JDK's `javap` tool. Much cheaper than decompiling
(genSources) when all we need is method/field signatures to figure out how an API
shape changed (e.g. new type parameters, renamed methods, changed parameter types).

Usage:
    python migration_tools/inspect_class.py net.minecraft.client.renderer.entity.EntityRenderer
    python migration_tools/inspect_class.py net.minecraft.world.level.block.state.BlockBehaviour$Properties
    python migration_tools/inspect_class.py net.minecraft.server.level.TicketType --jar path/to/other.jar

By default searches the auto-discovered Minecraft 26.1.2 merged jar (same discovery
logic as find_candidates.py). Pass --jar to point at a different jar (e.g. a Fabric
API submodule jar).
"""
import argparse
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def find_default_jar():
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
        candidates = list(search_root.glob("**/*.jar"))
    if not candidates:
        return None
    return max(candidates, key=lambda p: p.stat().st_size)


def find_javap():
    # Prefer JAVA_HOME's javap if set, otherwise rely on PATH.
    import os
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("javap.exe" if sys.platform.startswith("win") else "javap")
        if candidate.exists():
            return str(candidate)
    return "javap"


def inspect_class(jar_path, fqn, private=False):
    """
    Extract the single .class file for `fqn` from the jar into a temp dir and run
    javap on it directly (avoids needing to pass a huge -classpath for resolution,
    and works even for jars javap can't introspect directly on some setups).
    """
    entry = fqn.replace(".", "/") + ".class"
    with zipfile.ZipFile(jar_path) as zf:
        if entry not in zf.namelist():
            print(f"'{fqn}' not found in {jar_path}", file=sys.stderr)
            return 1
        with tempfile.TemporaryDirectory() as tmp:
            zf.extract(entry, path=tmp)
            class_file = Path(tmp) / entry
            cmd = [find_javap(), "-p" if private else "-public", "-s"]  # -s: show signatures (generics)
            cmd.append(str(class_file))
            proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            print(proc.stdout)
            return proc.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("fqn", help="Fully-qualified class name, e.g. net.minecraft.client.renderer.entity.EntityRenderer")
    parser.add_argument("--jar", type=str, default=None, help="Path to the jar (default: auto-discovered Minecraft 26.1.2 jar)")
    parser.add_argument("--private", action="store_true", help="Show private/protected members too (default: public only)")
    args = parser.parse_args()

    jar_path = Path(args.jar) if args.jar else find_default_jar()
    if jar_path is None or not jar_path.exists():
        print("Could not find a jar to inspect. Pass --jar explicitly.", file=sys.stderr)
        sys.exit(1)

    sys.exit(inspect_class(jar_path, args.fqn, private=args.private))


if __name__ == "__main__":
    main()
