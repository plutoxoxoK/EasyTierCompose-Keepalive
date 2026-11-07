"""Scan reachable application Git objects; print categories/paths, never matched bytes."""
from pathlib import Path, PurePosixPath
import argparse
import json
import subprocess
import sys

sys.dont_write_bytecode = True
from audit_source import PATTERNS

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=ROOT)
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--deny-pattern-file", type=Path)
    args = parser.parse_args()
    prefix = ["git", "-C", str(args.repository)]
    entries = subprocess.check_output(prefix + ["rev-list", "--objects", args.ref]).splitlines()
    names = {}
    for entry in entries:
        oid, _, name = entry.partition(b" ")
        names[oid.decode()] = name.decode("utf-8", errors="replace")
    deny = []
    if args.deny_pattern_file:
        deny = [s.strip().encode().lower() for s in args.deny_pattern_file.read_text(encoding="utf-8").splitlines()
                if s.strip() and not s.startswith("#")]
    stream = subprocess.check_output(prefix + ["cat-file", "--batch"], input=b"\n".join(n.encode() for n in names) + b"\n")
    offset = 0
    findings = []
    counts = {}
    for expected_oid, name in names.items():
        end = stream.index(b"\n", offset)
        oid, kind, length = stream[offset:end].split()
        assert oid.decode() == expected_oid
        offset = end + 1
        data = stream[offset:offset + int(length)]
        offset += int(length) + 1
        key = kind.decode()
        counts[key] = counts.get(key, 0) + 1
        if key not in {"blob", "commit", "tag"}:
            continue
        path = PurePosixPath(name)
        if key == "blob" and (any(p in {".local", ".git", ".gradle", "dist"} for p in path.parts)
                              or path.name in {"local.properties", "keystore.properties"}
                              or path.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx", ".apk", ".key", ".pem"}):
            findings.append({"object": expected_oid, "path": name, "category": "local-or-signing-material"})
        for category, pattern in PATTERNS.items():
            if pattern.search(data):
                findings.append({"object": expected_oid, "path": name, "category": category})
        if any(marker in data.lower() for marker in deny):
            findings.append({"object": expected_oid, "path": name, "category": "private-deny-marker"})
    report = {"ref": subprocess.check_output(prefix + ["rev-parse", args.ref]).decode().strip(),
              "shallow": subprocess.check_output(prefix + ["rev-parse", "--is-shallow-repository"]).decode().strip() == "true",
              "objectsScanned": counts, "findings": findings,
              "scope": "Only objects reachable from the selected application ref; submodule objects are not traversed"}
    print(json.dumps(report, indent=2))
    raise SystemExit(bool(findings))


if __name__ == "__main__":
    main()
