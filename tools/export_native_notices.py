"""Collect declared licenses/notices for a conservative pinned JNI lock graph.

No Cargo install or native build. Registry archives must match Cargo.lock checksums.
This is a cross-target/feature superset, not an exact Android binary SBOM or legal review.
Downloads stay under ignored .local; only metadata and notice texts are exported.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tomllib
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "EasyTier-Core"
PIN = "81487c363a56779e745eae927e130a813535c71e"


def is_notice(path):
    parts = PurePosixPath(path).parts
    return (bool(re.match(r"(?i)^(license|licence|notice|copying|copyright|authors|unlicense)", parts[-1]))
            or any(p.lower() in {"license", "licenses", "licence", "licences", "notice", "notices"} for p in parts[:-1]))


def closure(packages):
    by_name = {}
    for package in packages:
        by_name.setdefault(package["name"], []).append(package)
    pending = by_name["easytier-android-jni"][:]
    seen = {}
    while pending:
        package = pending.pop()
        key = (package["name"], package["version"], package.get("source", ""))
        if key in seen:
            continue
        seen[key] = package
        for dep in package.get("dependencies", []):
            parts = dep.split()
            candidates = by_name[parts[0]]
            if len(parts) > 1:
                candidates = [p for p in candidates if p["version"] == parts[1]]
            if len(parts) > 2:
                candidates = [p for p in candidates if p.get("source") == " ".join(parts[2:])[1:-1]]
            if len(candidates) != 1:
                raise ValueError("Ambiguous locked dependency: " + dep)
            pending.extend(candidates)
    return sorted(seen.values(), key=lambda p: (p["name"], p["version"], p.get("source", "")))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workers", type=int, default=10)
    parser.add_argument("--retry-unavailable", action="store_true")
    args = parser.parse_args()
    actual = subprocess.check_output(["git", "-C", str(CORE), "rev-parse", "HEAD"]).decode().strip()
    if actual != PIN:
        raise SystemExit("Native source must be at the pinned commit")
    cache = ROOT / ".local/native-license-cache"
    output = ROOT / "licenses/native"
    cache.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    packages = tomllib.loads((CORE / "Cargo.lock").read_text(encoding="utf-8"))["package"]
    selected = closure(packages)

    def download(url, path, expected=None):
        if path.is_file():
            data = path.read_bytes()
            if expected is None or hashlib.sha256(data).hexdigest() == expected:
                return data
        request = urllib.request.Request(url, headers={"User-Agent": "EasyTierCompose-Keepalive-notice-inventory/1.0"})
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read(80 * 1024 * 1024 + 1)
        if len(data) > 80 * 1024 * 1024:
            raise ValueError("Archive exceeds inventory download limit")
        if expected and hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Cargo.lock archive checksum mismatch")
        path.write_bytes(data)
        return data

    def collect(package):
        name, version = package["name"], package["version"]
        source = package.get("source", "")
        record = {"name": name, "version": version, "source": source or "pinned workspace",
                  "declaredLicense": None, "declaredLicenseFile": None, "noticeFiles": []}
        try:
            if source.startswith("registry+"):
                url = f"https://static.crates.io/crates/{name}/{name}-{version}.crate"
                data = download(url, cache / f"{name}-{version}.crate", package["checksum"])
                record.update({"archiveURL": url, "archiveSHA256": hashlib.sha256(data).hexdigest(),
                               "cargoLockChecksumVerified": True})
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                    files = {m.name: archive.extractfile(m).read() for m in archive.getmembers()
                             if m.isfile() and m.size <= 2 * 1024 * 1024
                             and (PurePosixPath(m.name).name in {"Cargo.toml", "Cargo.toml.orig"}
                                  or is_notice(m.name))}
                cargo_path = f"{name}-{version}/Cargo.toml"
                manifest = tomllib.loads(files[cargo_path].decode("utf-8"))
            elif source.startswith("git+"):
                url = urllib.parse.urlsplit(source[4:])
                if url.hostname != "github.com" or not re.fullmatch(r"[0-9a-f]{40}", url.fragment):
                    raise ValueError("Unsupported pinned Git source")
                repo = url.path.removesuffix(".git").strip("/")
                archive_url = f"https://codeload.github.com/{repo}/tar.gz/{url.fragment}"
                cache_path = cache / (repo.replace("/", "_") + "_" + url.fragment + ".tar.gz")
                data = download(archive_url, cache_path)
                record.update({"archiveURL": archive_url, "archiveSHA256": hashlib.sha256(data).hexdigest(),
                               "gitCommit": url.fragment, "cargoLockChecksumVerified": False})
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                    files = {m.name: archive.extractfile(m).read() for m in archive.getmembers()
                             if m.isfile() and m.size <= 2 * 1024 * 1024
                             and (PurePosixPath(m.name).name == "Cargo.toml"
                                  or is_notice(m.name))}
                candidates = []
                for path, blob in files.items():
                    if PurePosixPath(path).name == "Cargo.toml":
                        parsed = tomllib.loads(blob.decode("utf-8"))
                        if parsed.get("package", {}).get("name") == name:
                            candidates.append((path, parsed))
                if len(candidates) != 1:
                    raise ValueError("Cannot identify package manifest in pinned Git source")
                cargo_path, manifest = candidates[0]
            else:
                candidates = []
                for path in CORE.rglob("Cargo.toml"):
                    parsed = tomllib.loads(path.read_text(encoding="utf-8"))
                    if parsed.get("package", {}).get("name") == name:
                        candidates.append((path, parsed))
                if len(candidates) != 1:
                    raise ValueError("Cannot identify pinned workspace package")
                path, manifest = candidates[0]
                cargo_path = path.relative_to(CORE).as_posix()
                files = {p.relative_to(CORE).as_posix(): p.read_bytes() for p in CORE.rglob("*")
                         if p.is_file() and p.stat().st_size <= 2 * 1024 * 1024
                         and is_notice(p.relative_to(CORE).as_posix())}
            metadata = manifest["package"]
            license_value = metadata.get("license")
            license_file = metadata.get("license-file")
            if isinstance(license_value, dict) and license_value.get("workspace"):
                workspace_candidates = [(p, tomllib.loads(b.decode("utf-8"))) for p, b in files.items()
                                        if PurePosixPath(p).name == "Cargo.toml"]
                if not source:
                    workspace_candidates.append(("Cargo.toml", tomllib.loads((CORE / "Cargo.toml").read_text(encoding="utf-8"))))
                inherited = [m["workspace"]["package"]["license"] for _, m in workspace_candidates
                             if "license" in m.get("workspace", {}).get("package", {})]
                license_value = inherited[0] if len(set(inherited)) == 1 else None
            record.update({"declaredLicense": license_value, "declaredLicenseFile": license_file,
                           "manifestPath": cargo_path})
            safe_prefix = re.sub(r"[^A-Za-z0-9._-]", "_", name + "-" + version)
            for path, blob in sorted(files.items()):
                if PurePosixPath(path).name in {"Cargo.toml", "Cargo.toml.orig"}:
                    continue
                try:
                    blob.decode("utf-8")
                except UnicodeDecodeError:
                    continue
                # Content-addressed suffixes avoid colliding nested upstream notices.
                suffix = hashlib.sha256(path.encode()).hexdigest()[:12]
                target = output / f"{safe_prefix}__{suffix}__{PurePosixPath(path).name}.txt"
                target.write_bytes(blob)
                record["noticeFiles"].append({"path": target.relative_to(ROOT).as_posix(),
                                               "upstreamPath": path, "sha256": hashlib.sha256(blob).hexdigest()})
            record["status"] = "collected" if (license_value or license_file) and record["noticeFiles"] else "review-needed"
        except Exception as error:
            record["status"] = "unavailable"
            record["errorType"] = type(error).__name__
        return record

    records = []
    if args.retry_unavailable:
        existing = json.loads((ROOT / "docs/NATIVE-DEPENDENCY-LICENSES.json").read_text(encoding="utf-8"))
        records = [r for r in existing["packages"] if r["status"] != "unavailable"]
        completed = {(r["name"], r["version"], r["source"]) for r in records}
        selected = [p for p in selected if (p["name"], p["version"], p.get("source", "pinned workspace")) not in completed]
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        pending = [pool.submit(collect, package) for package in selected]
        for future in as_completed(pending):
            records.append(future.result())
            if len(records) % 50 == 0:
                print(json.dumps({"collected": len(records), "total": len(selected)}), flush=True)
    records.sort(key=lambda r: (r["name"], r["version"], r["source"]))
    result = {"nativeCommit": PIN, "scope": "Conservative Cargo.lock dependency closure from easytier-android-jni; all locked targets/features, not exact APK SBOM",
              "root": "easytier-android-jni", "lockedWorkspacePackages": len(packages), "packages": records,
              "summary": {"packages": len(records), "collected": sum(r["status"] == "collected" for r in records),
                          "reviewNeeded": sum(r["status"] == "review-needed" for r in records),
                          "unavailable": sum(r["status"] == "unavailable" for r in records),
                          "noticeFiles": sum(len(r["noticeFiles"]) for r in records)}}
    (ROOT / "docs/NATIVE-DEPENDENCY-LICENSES.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["summary"]), flush=True)


if __name__ == "__main__":
    main()
