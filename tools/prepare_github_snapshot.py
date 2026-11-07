"""Prepare an isolated, single-root Git snapshot and an asset allowlist. No network writes."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PIN = "81487c363a56779e745eae927e130a813535c71e"


def git(*args, cwd=ROOT, data=None):
    return subprocess.check_output(["git", "-C", str(cwd), *args], input=data, stderr=subprocess.PIPE)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", required=True)
    parser.add_argument("--name", default="EasyTierCompose-Keepalive")
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9-]+", args.owner) or not re.fullmatch(r"[A-Za-z0-9_.-]+", args.name):
        raise SystemExit("Invalid repository identity")
    destination = (ROOT / args.destination).resolve()
    if not destination.is_relative_to((ROOT / ".local").resolve()) or destination.exists():
        raise SystemExit("Choose a new destination inside ignored .local")
    if git("status", "--porcelain").strip():
        raise SystemExit("Commit the reviewed application source first")
    source_commit = git("rev-parse", "HEAD").decode().strip()
    source_tree = git("rev-parse", "HEAD^{tree}").decode().strip()
    version = git("show", "HEAD:version.txt").decode().strip()
    tag = "v" + version
    dist = ROOT / "dist"
    delivery = json.loads((dist / "DELIVERY-MANIFEST.json").read_text(encoding="utf-8"))
    if delivery["applicationCommit"] != source_commit:
        raise SystemExit("Distribution manifest is not for the current source")
    expected_assets = {delivery["apk"]["file"], delivery["source"]["file"],
                       "DELIVERY-MANIFEST.json", "APK-SIGNATURE.txt", "RELEASE-NOTES.md"}
    checksums = {}
    for line in (dist / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        digest, name = line.split("  ", 1)
        if name not in expected_assets or name in checksums or sha256(dist / name) != digest:
            raise SystemExit("Unexpected or changed distribution asset")
        checksums[name] = digest
    if set(checksums) != expected_assets:
        raise SystemExit("Incomplete asset checksum allowlist")
    for name in ("source", "apk", "bundle"):
        if json.loads((ROOT / f".local/final-{name}-audit.json").read_text(encoding="utf-8"))["findings"]:
            raise SystemExit("Privacy audit has unresolved findings")
    destination.mkdir(parents=True)
    repository = destination / "repository"
    repository.mkdir()
    git("init", "--initial-branch=main", cwd=repository)
    git("config", "core.autocrlf", "false", cwd=repository)
    git("config", "core.longpaths", "true", cwd=repository)
    git("config", "core.hooksPath", "NUL", cwd=repository)
    tree_entries = []
    for entry in git("ls-tree", "-rz", "HEAD").split(b"\0"):
        if not entry:
            continue
        metadata, path = entry.split(b"\t", 1)
        mode, kind, oid = metadata.split()
        tree_entries.append((mode, kind, oid, path.decode("utf-8")))
    archive_bytes = git("-c", "core.autocrlf=false", "archive", "--format=zip", "HEAD")
    timestamp = git("show", "-s", "--format=%ct", "HEAD").decode().strip()
    message = (f"Prepare experimental no-TUN keepalive {version} snapshot\n\n"
               f"Development source: {source_commit}\n"
               "Upstream application: d3c013b9db714271cebc20e5df0499e5fbb79a7a\n"
               f"Pinned native source: {PIN}\n"
               "Unofficial GPL-3.0 fork; preserve upstream attribution and notices.\n").encode()
    stream = io.BytesIO()
    stream.write(b"commit refs/heads/main\n")
    stream.write(f"committer Keepalive Candidate <candidate@example.invalid> {timestamp} +0800\n".encode())
    stream.write(f"data {len(message)}\n".encode() + message + b"\ndeleteall\n")
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        for mode, kind, oid, path in tree_entries:
            # Fast-import accepts quoted UTF-8 paths; JSON supplies the needed ASCII escaping.
            quoted = json.dumps(path, ensure_ascii=False).encode("utf-8")
            if kind == b"commit":
                if path != "EasyTier-Core" or oid.decode() != PIN:
                    raise SystemExit("Unexpected submodule")
                stream.write(b"M 160000 " + oid + b" " + quoted + b"\n")
            else:
                data = archive.read(path)
                stream.write(b"M " + mode + b" inline " + quoted + b"\n")
                stream.write(f"data {len(data)}\n".encode() + data + b"\n")
    stream.write(b"\ndone\n")
    git("fast-import", "--quiet", cwd=repository, data=stream.getvalue())
    published_commit = git("rev-parse", "HEAD", cwd=repository).decode().strip()
    if git("rev-parse", "HEAD^{tree}", cwd=repository).decode().strip() != source_tree:
        raise SystemExit("Publication tree differs from reviewed source tree")
    git("reset", "--hard", "HEAD", cwd=repository)
    (repository / "EasyTier-Core").mkdir(exist_ok=True)
    git("tag", tag, cwd=repository)
    if git("rev-list", "--count", "HEAD", cwd=repository).decode().strip() != "1":
        raise SystemExit("Publication history must contain exactly one root commit")
    if git("status", "--porcelain", cwd=repository).strip():
        raise SystemExit("Publication snapshot is dirty")
    git("bundle", "create", str(destination / "source.bundle"), "main", "refs/tags/" + tag, cwd=repository)
    assets_dir = destination / "assets"
    assets_dir.mkdir()
    assets = []
    for name in sorted(expected_assets | {"SHA256SUMS"}):
        shutil.copyfile(dist / name, assets_dir / name)
        digest = sha256(assets_dir / name)
        if digest != sha256(dist / name):
            raise SystemExit("Frozen asset copy differs")
        assets.append({"file": name, "bytes": (assets_dir / name).stat().st_size, "sha256": digest})
    plan = {"status": "prepared-locally-awaiting-publication-approval", "repository": args.owner + "/" + args.name,
            "visibility": "public", "branch": "main", "tag": tag,
            "releaseTitle": version + " — experimental no-TUN keepalive",
            "draftFirst": True, "prerelease": True, "developmentCommit": source_commit,
            "publicationCommit": published_commit, "identicalSourceTree": source_tree,
            "nativeCommit": PIN, "sourceBundleSHA256": sha256(destination / "source.bundle"),
            "assets": assets, "externalWritesPerformed": False,
            "binaryLimitations": ["Android Debug signature; future stable signing identity undecided",
                                   "Unmodified upstream JNI; bit-for-bit native rebuild not established",
                                   "Conservative native inventory is not an exact APK SBOM; see explicit notice dispositions",
                                   "User reported background/lock-screen success; package version/duration/network not independently verified"]}
    (destination / "PUBLICATION-PLAN.json").write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"directory": str(destination), "repository": plan["repository"],
                      "developmentCommit": source_commit, "publicationCommit": published_commit,
                      "treeMatches": True, "assets": len(assets), "externalWritesPerformed": False}, indent=2))


if __name__ == "__main__":
    main()
