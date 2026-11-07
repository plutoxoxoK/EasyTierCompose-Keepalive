"""Record explicit evidence/dispositions for upstream notice packaging omissions.

Preserves missing declarations and does not convert metadata into a compliance claim.
"""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import tomllib
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    inventory_path = ROOT / "docs/NATIVE-DEPENDENCY-LICENSES.json"
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    standard_dir = ROOT / "licenses/native-standard"
    standard_dir.mkdir(exist_ok=True)
    standards = []
    for license_id in ["MIT", "CC0-1.0", "WTFPL"]:
        target = standard_dir / (license_id + ".txt")
        url = f"https://raw.githubusercontent.com/spdx/license-list-data/main/text/{license_id}.txt"
        if not target.is_file():
            request = urllib.request.Request(url, headers={"User-Agent": "EasyTierCompose-Keepalive-notice-inventory/1.0"})
            with urllib.request.urlopen(request, timeout=30) as response:
                target.write_bytes(response.read(128 * 1024))
        standards.append({"license": license_id, "path": target.relative_to(ROOT).as_posix(),
                          "source": url, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                          "scope": "Canonical license text; placeholders are not invented package copyright attribution"})
    dispositions = []
    for record in inventory["packages"]:
        if record["status"] != "review-needed":
            continue
        entry = {"name": record["name"], "version": record["version"],
                 "declaredLicense": record["declaredLicense"], "declaredLicenseFile": record["declaredLicenseFile"]}
        if record["source"] == "pinned workspace":
            entry["disposition"] = "repository-license-observed; package manifest has no license declaration"
            entry["evidence"] = {"path": "EasyTier-Core/LICENSE",
                                  "sha256": hashlib.sha256((ROOT / "EasyTier-Core/LICENSE").read_bytes()).hexdigest(),
                                  "observedLicense": "LGPL-3.0"}
        elif record["source"].startswith("registry+"):
            path = ROOT / ".local/native-license-cache" / f"{record['name']}-{record['version']}.crate"
            data = path.read_bytes()
            assert hashlib.sha256(data).hexdigest() == record["archiveSHA256"]
            headers = []
            with tarfile.open(path, "r:gz") as archive:
                package = tomllib.loads(archive.extractfile(f"{record['name']}-{record['version']}/Cargo.toml").read().decode())["package"]
                entry["declaredAuthors"] = package.get("authors", [])
                entry["declaredRepository"] = package.get("repository")
                for member in archive.getmembers():
                    if not member.isfile() or not member.name.endswith(".rs"):
                        continue
                    content = archive.extractfile(member).read(8192).decode("utf-8", errors="replace")
                    lines = []
                    for line in content.splitlines():
                        if line.startswith("//") or not line.strip():
                            lines.append(line)
                        else:
                            break
                    text = "\n".join(lines).strip()
                    if re.search(r"(?i)copyright|public domain|licensed under", text):
                        headers.append("Upstream file: " + member.name + "\n" + text)
            if headers:
                target = ROOT / "licenses/native" / f"{record['name']}-{record['version']}__SOURCE-HEADERS.txt"
                target.write_text("\n\n".join(headers) + "\n", encoding="utf-8")
                notice = {"path": target.relative_to(ROOT).as_posix(),
                          "upstreamPath": "Original leading source comments from checksum-verified crate",
                          "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
                record["noticeFiles"] = [n for n in record["noticeFiles"] if n["path"] != notice["path"]] + [notice]
                entry["disposition"] = "source-header-notices-preserved; canonical license text supplied separately"
                entry["evidence"] = notice
            else:
                entry["disposition"] = "metadata-only; no package-specific notice text found in pinned sources"
        else:
            entry["disposition"] = "metadata-only; pinned Git repository has no separate license/notice file"
        record["reviewDisposition"] = entry["disposition"]
        dispositions.append(entry)
    inventory["summary"]["noticeFiles"] = sum(len(r["noticeFiles"]) for r in inventory["packages"])
    review = {"nativeCommit": inventory["nativeCommit"], "scope": inventory["scope"],
              "standardLicenseTexts": standards, "dispositions": dispositions,
              "unresolvedPackageSpecificNoticeTexts": [d["name"] for d in dispositions if d["disposition"].startswith("metadata-only")],
              "limitation": "Collection and explicit dispositions do not prove exact binary membership, reproducibility, or exhaustive distribution obligations"}
    inventory_path.write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")
    (ROOT / "docs/NATIVE-LICENSE-REVIEW.json").write_text(json.dumps(review, indent=2) + "\n", encoding="utf-8")
    referenced = {n["path"] for r in inventory["packages"] for n in r["noticeFiles"]}
    # Only remove obsolete generated text files inside this tool's dedicated output.
    notice_dir = (ROOT / "licenses/native").resolve()
    for path in notice_dir.glob("*.txt"):
        assert path.resolve().parent == notice_dir and not path.is_symlink()
        if path.relative_to(ROOT).as_posix() not in referenced:
            path.unlink()
    print(json.dumps({"inventory": inventory["summary"], "explicitDispositions": len(dispositions),
                      "metadataOnly": review["unresolvedPackageSpecificNoticeTexts"]}))


if __name__ == "__main__":
    main()
