"""Supplement missing notice files from the exact VCS commits recorded by crates.

Does not infer a missing commit from current main, change declared licenses, or run code.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import threading
import tomllib
import urllib.parse
import urllib.request
from export_native_notices import is_notice

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".local/native-license-cache"
OUTPUT = ROOT / "licenses/native"
NOTICE_NAME = re.compile(r"(?i)^(license|licence|notice|copying|copyright|authors|unlicense)")


def main():
    inventory = ROOT / "docs/NATIVE-DEPENDENCY-LICENSES.json"
    result = json.loads(inventory.read_text(encoding="utf-8"))
    locks = {}
    lock_guard = threading.Lock()

    def supplement(record):
        if record["status"] != "review-needed" or not record["source"].startswith("registry+"):
            return record
        try:
            name, version = record["name"], record["version"]
            path = CACHE / f"{name}-{version}.crate"
            data = path.read_bytes()
            if hashlib.sha256(data).hexdigest() != record["archiveSHA256"]:
                raise ValueError("Registry cache checksum changed")
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                prefix = f"{name}-{version}/"
                manifest = tomllib.loads(archive.extractfile(prefix + "Cargo.toml").read().decode())
                try:
                    vcs = json.loads(archive.extractfile(prefix + ".cargo_vcs_info.json").read())
                except KeyError:
                    record["supplementStatus"] = "no-pinned-vcs-commit"
                    return record
            commit = vcs.get("git", {}).get("sha1", "")
            repo_url = urllib.parse.urlsplit(manifest["package"].get("repository", ""))
            if repo_url.hostname != "github.com" or not re.fullmatch(r"[0-9a-f]{40}", commit):
                record["supplementStatus"] = "no-supported-pinned-github-source"
                return record
            repo = "/".join(repo_url.path.strip("/").split("/")[:2]).removesuffix(".git")
            archive_url = f"https://codeload.github.com/{repo}/tar.gz/{commit}"
            cached = CACHE / (repo.replace("/", "_") + "_" + commit + ".tar.gz")
            with lock_guard:
                download_lock = locks.setdefault(str(cached), threading.Lock())
            with download_lock:
                if cached.is_file():
                    data = cached.read_bytes()
                else:
                    request = urllib.request.Request(archive_url, headers={"User-Agent": "EasyTierCompose-Keepalive-notice-inventory/1.0"})
                    with urllib.request.urlopen(request, timeout=40) as response:
                        data = response.read(80 * 1024 * 1024 + 1)
                    if len(data) > 80 * 1024 * 1024:
                        raise ValueError("Supplement archive exceeds limit")
                    cached.write_bytes(data)
            package_path = PurePosixPath(vcs.get("path_in_vcs", ""))
            ancestors = {package_path, *package_path.parents, PurePosixPath(".")}
            notices = []
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                for member in archive.getmembers():
                    path = PurePosixPath(*PurePosixPath(member.name).parts[1:])
                    if (not member.isfile() or member.size > 2 * 1024 * 1024
                            or not is_notice(path.as_posix())):
                        continue
                    if path.parent not in ancestors and not path.is_relative_to(package_path):
                        continue
                    blob = archive.extractfile(member).read()
                    try:
                        blob.decode("utf-8")
                    except UnicodeDecodeError:
                        continue
                    suffix = hashlib.sha256(path.as_posix().encode()).hexdigest()[:12]
                    target = OUTPUT / f"{name}-{version}__vcs_{suffix}__{path.name}.txt"
                    target.write_bytes(blob)
                    notices.append({"path": target.relative_to(ROOT).as_posix(), "upstreamPath": path.as_posix(),
                                    "sha256": hashlib.sha256(blob).hexdigest()})
            record["noticeFiles"] = notices
            record["supplementalSource"] = {"archiveURL": archive_url, "archiveSHA256": hashlib.sha256(data).hexdigest(),
                                            "gitCommit": commit, "crateVcsMetadata": vcs}
            record["supplementStatus"] = "pinned-vcs-notices-collected" if notices else "no-notice-file-at-pinned-vcs"
            if notices and (record["declaredLicense"] or record["declaredLicenseFile"]):
                record["status"] = "collected"
        except Exception as error:
            record["supplementStatus"] = "unavailable"
            record["supplementErrorType"] = type(error).__name__
        return record

    with ThreadPoolExecutor(max_workers=6) as pool:
        result["packages"] = list(pool.map(supplement, result["packages"]))
    records = result["packages"]
    result["summary"] = {"packages": len(records), "collected": sum(r["status"] == "collected" for r in records),
                         "reviewNeeded": sum(r["status"] == "review-needed" for r in records),
                         "unavailable": sum(r["status"] == "unavailable" for r in records),
                         "noticeFiles": sum(len(r["noticeFiles"]) for r in records)}
    inventory.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["summary"]))


if __name__ == "__main__":
    main()
