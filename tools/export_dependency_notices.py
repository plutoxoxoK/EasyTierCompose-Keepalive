"""Export cached runtime POM license declarations and shipped NOTICE/LICENSE resources.

Run after producing docs/DEPENDENCIES.json. Does not fetch or invent missing metadata.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle-home", type=Path, required=True)
    args = parser.parse_args()
    cache = args.gradle_home / "caches/modules-2/files-2.1"
    dependencies = json.loads((ROOT / "docs/DEPENDENCIES.json").read_text(encoding="utf-8"))
    overrides = json.loads((ROOT / "docs/DEPENDENCY-LICENSE-OVERRIDES.json").read_text(encoding="utf-8"))
    output = ROOT / "licenses/dependencies"
    output.mkdir(parents=True, exist_ok=True)
    records = []

    def licenses(group, artifact, version, seen=None):
        seen = set() if seen is None else seen
        key = (group, artifact, version)
        if key in seen: return []
        seen.add(key)
        files = list((cache / group / artifact / version).glob("*/*.pom"))
        if not files: return []
        tree = ET.parse(files[0]).getroot()
        for element in tree.iter(): element.tag = element.tag.split("}")[-1]
        result = [{"name": e.findtext("name", ""), "url": e.findtext("url", "")}
                  for e in tree.findall("licenses/license")]
        parent = tree.find("parent")
        if not result and parent is not None:
            result = licenses(parent.findtext("groupId", ""), parent.findtext("artifactId", ""), parent.findtext("version", ""), seen)
        return result

    def text_notices(data, prefix):
        result = []
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for name in archive.namelist():
                    base = PurePosixPath(name).name.lower()
                    if name.endswith("/"): continue
                    if base.startswith(("license", "notice", "copyright")) and not base.endswith((".class", ".dex")):
                        blob = archive.read(name)
                        if len(blob) > 1024 * 1024: continue
                        try: blob.decode("utf-8")
                        except UnicodeDecodeError: continue
                        safe = re.sub(r"[^a-zA-Z0-9._-]", "_", prefix + "__" + name) + ".txt"
                        target = output / safe
                        target.write_bytes(blob)
                        result.append(target.relative_to(ROOT).as_posix())
                    elif name == "classes.jar":
                        result += text_notices(archive.read(name), prefix + "_classes")
        except zipfile.BadZipFile:
            pass
        return result

    for dependency in dependencies:
        group, artifact, version = dependency["coordinate"].split(":", 2)
        files = list((cache / group / artifact / version).glob("*/" + dependency["file"]))
        notices = []
        for path in files:
            data = path.read_bytes()
            if hashlib.sha256(data).hexdigest() == dependency["sha256"]:
                notices = text_notices(data, group + "_" + artifact + "_" + version)
                break
        declared = licenses(group, artifact, version)
        override = overrides.get(dependency["coordinate"]) if not declared else None
        if override:
            declared = override["declaredLicenses"]
            notices.append(override["licenseText"])
        records.append({"coordinate": dependency["coordinate"], "declaredLicenses": declared,
                        "metadataSource": override["reason"] if override else "Cached Maven POM, including parent when declared",
                        "noticeFiles": notices})
    (ROOT / "docs/DEPENDENCY-LICENSES.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    missing = [r["coordinate"] for r in records if not r["declaredLicenses"]]
    print(json.dumps({"runtimeEntries": len(records), "embeddedNoticeFiles": len(list(output.iterdir())), "missingLicenseDeclarations": missing}))


if __name__ == "__main__":
    main()
