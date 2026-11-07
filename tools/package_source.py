"""Export committed application + exact native submodule source, never the parent workspace.

Python standard library only. Run after committing the reviewable candidate. No upload.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PINNED_CORE = "81487c363a56779e745eae927e130a813535c71e"
# These tracked upstream GUI signing files are not native-library build sources.
# Never redistribute a keystore or its password properties, even from upstream.
NATIVE_SIGNING_EXCLUSIONS = {
    "easytier-gui/src-tauri/gen/android/app/upload-keystore.jks",
    "easytier-gui/src-tauri/gen/android/keystore.properties",
}


def git(*args: str, cwd: Path = ROOT) -> bytes:
    return subprocess.check_output(["git", "-C", str(cwd), *args])


def main() -> None:
    if git("status", "--porcelain").strip():
        raise SystemExit("Commit or resolve source changes before packaging.")
    core = ROOT / "EasyTier-Core"
    if git("rev-parse", "HEAD", cwd=core).decode().strip() != PINNED_CORE:
        raise SystemExit("Native source is not the pinned commit; initialize the submodule first.")
    if git("status", "--porcelain", cwd=core).strip():
        raise SystemExit("Native source has local changes.")
    commit = git("rev-parse", "HEAD").decode().strip()
    version = git("show", "HEAD:version.txt").decode().strip()
    # Export Git bytes consistently on Windows regardless of local CRLF settings.
    app_archive = git("-c", "core.autocrlf=false", "archive", "--format=zip", "HEAD")
    native_archive = git("-c", "core.autocrlf=false", "archive", "--format=zip", PINNED_CORE, cwd=core)
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    output = dist / f"EasyTier-keepalive-{version}-source.zip"
    manifest = []
    omissions = []
    excluded = {".git", ".local", ".gradle", "dist"}
    # Rust's committed build/*.rs files are source, not generated build output.
    # Keep the app-only build exclusion out of the pinned native source tree.
    with tempfile.NamedTemporaryFile(dir=dist, suffix=".source.zip.tmp", delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(temporary_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for prefix, source in [("", app_archive), ("EasyTier-Core/", native_archive)]:
                with zipfile.ZipFile(io.BytesIO(source)) as z:
                    for entry in z.infolist():
                        if entry.is_dir():
                            continue
                        relative = PurePosixPath(entry.filename)
                        if prefix and entry.filename in NATIVE_SIGNING_EXCLUSIONS:
                            omissions.append({"path": prefix + entry.filename,
                                              "reason": "Upstream GUI signing material; not required by JNI build"})
                            continue
                        if (relative.is_absolute() or ".." in relative.parts
                                or any(p in excluded for p in relative.parts)
                                or (not prefix and "build" in relative.parts)):
                            raise SystemExit("Refusing an unsafe or generated archive member: " + entry.filename)
                        if (relative.name in {"local.properties", "keystore.properties"}
                                or relative.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx", ".apk", ".log"}):
                            raise SystemExit("Refusing local material: " + entry.filename)
                        data = z.read(entry)
                        info = zipfile.ZipInfo("EasyTier-keepalive/" + prefix + entry.filename, entry.date_time)
                        info.external_attr = entry.external_attr
                        info.compress_type = zipfile.ZIP_DEFLATED
                        archive.writestr(info, data)
                        manifest.append({"path": prefix + entry.filename, "size": len(data),
                                         "sha256": hashlib.sha256(data).hexdigest()})
            provenance = {"applicationCommit": commit, "nativeCommit": PINNED_CORE, "version": version,
                          "excludedUpstreamSigningMaterial": omissions, "files": manifest}
            archive.writestr("EasyTier-keepalive/SOURCE-MANIFEST.json", json.dumps(provenance, indent=2) + "\n")
        os.replace(temporary_path, output)
    finally:
        temporary_path.unlink(missing_ok=True)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (dist / "SOURCE-SHA256SUMS").write_text(digest + "  " + output.name + "\n", encoding="utf-8")
    print(json.dumps({"source": output.name, "files": len(manifest), "sha256": digest, "applicationCommit": commit}))


if __name__ == "__main__":
    main()
