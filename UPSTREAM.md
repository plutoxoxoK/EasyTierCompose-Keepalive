# Provenance

Checked on 2026-11-06 (Asia/Hong_Kong).

| Material | Pinned source |
|---|---|
| Application | originalFactor/EasyTierCompose, tag v1.0.10, tag object `7eb4d69298aa3b2ff1e684cd52cf0e1ffa70455c`, commit `d3c013b9db714271cebc20e5df0499e5fbb79a7a` |
| Compared main | `8cc3fea6e0f3ad90b5f382bdecec3a1108cb92fd`; diff ignoring end-of-line whitespace was empty |
| EasyTier core submodule | EasyTier/EasyTier, `81487c363a56779e745eae927e130a813535c71e` |
| JNI binaries | Unmodified files from the application baseline under app/src/main/jniLibs/; SHA-256 list in docs/NATIVE-SHA256.json |
| Native build recipe | EasyTier-Core/easytier-contrib/easytier-android-jni/build.sh plus the upstream application's update-easytier-jni.ps1 |

Both compared application revisions run NO_TUN in the app process without a dedicated foreground Service. The candidate fixes application lifecycle management, not Rust packet processing or VPN routing.

The baseline versionCode on the observed upstream release is 41. Candidate versionCode is deliberately fixed and independent of Git commit count, so clean source archives and shallow clones retain the same identity.

The upstream AGENTS file describes older Gradle/AGP versions in places. Actual baseline build files pin Gradle 9.7.1, AGP 9.4.1, Kotlin 2.2.10, compileSdk 37, targetSdk 36, NDK 30.0.16248370 and CMake 4.1.2. The no-TUN-only candidate removes externalNativeBuild and the NDK pin; its testable scope does not include Root/VPN.

Core source is checked out at the exact submodule commit. The supplied native binaries were not recompiled in this task; a bit-for-bit source/binary reproducibility claim has not been established. A public binary release should include the corresponding pinned native source bundle and its build recipes alongside the modified application source. Do not substitute current main or a different native release.

The source exporter omits two signing files already tracked in the pinned upstream GUI project: `easytier-gui/src-tauri/gen/android/app/upload-keystore.jks` and `easytier-gui/src-tauri/gen/android/keystore.properties`. They are not JNI build sources and must not be redistributed. Their paths and exclusion reasons are recorded in SOURCE-MANIFEST.json. All other application/native Git blobs are included and verified byte-for-byte; this explicit omission does not claim a byte-identical export of the entire upstream repository. GUI distributors must supply their own signing identity.
