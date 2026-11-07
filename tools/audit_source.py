"""Conservative publication scan. Reports paths/categories only, never matched values.

Supplement with a private deny-pattern file outside exported source. A clean scan is not
a claim that every possible credential format or every dependency license was identified.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    "private-key-block": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "github-token": re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "aws-access-id": re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    "google-api-key": re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b"),
    "credential-in-url": re.compile(rb"https?://[^\s/:]{1,100}:[^\s/@]{1,100}@"),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-zip", type=Path)
    parser.add_argument("--deny-pattern-file", type=Path)
    args = parser.parse_args()
    deny = []
    if args.deny_pattern_file:
        deny = [line.strip().encode().lower() for line in args.deny_pattern_file.read_text(encoding="utf-8").splitlines()
                if line.strip() and not line.startswith("#")]
    if args.source_zip:
        archive = zipfile.ZipFile(args.source_zip)
        members = ((n, archive.read(n)) for n in archive.namelist() if not n.endswith("/"))
    else:
        paths = subprocess.check_output(["git", "-C", str(ROOT), "ls-files", "-z", "--cached", "--others", "--exclude-standard"])
        members = ((s.decode(), (ROOT / s.decode()).read_bytes()) for s in sorted(set(paths.split(b"\0")))
                   if s and (ROOT / s.decode()).is_file())
    findings = []; count = 0
    for name, data in members:
        count += 1
        path = PurePosixPath(name)
        if any(part in {".git", ".local", ".gradle", "dist"} for part in path.parts):
            findings.append({"path": name, "category": "local-material"})
        if path.name in {"local.properties", "keystore.properties"} or path.suffix.lower() in {".jks", ".keystore", ".apk"}:
            findings.append({"path": name, "category": "local-material"})
        for category, pattern in PATTERNS.items():
            if pattern.search(data): findings.append({"path": name, "category": category})
        if any(value in data.lower() for value in deny):
            findings.append({"path": name, "category": "private-deny-marker"})
    print(json.dumps({"filesScanned": count, "findings": findings}, indent=2))
    raise SystemExit(bool(findings))


if __name__ == "__main__":
    main()
