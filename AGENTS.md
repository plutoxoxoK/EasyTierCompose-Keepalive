# Candidate development rules

This is an independent, unofficial NO_TUN-only fork of EasyTierCompose v1.0.10.
Read README.md, UPSTREAM.md and docs/KEEPALIVE-DESIGN.md before changing runtime code.
Original upstream guidance is preserved in AGENTS.upstream.md for historical architecture context;
its VPN/Root and toolchain descriptions do not describe this candidate.

- Keep the applicationId distinct: cc.ptoe.easytier.compose.keepalive. Never install, switch a user's running session, or publish without explicit authorization.
- No VPN/TUN/Root/Shizuku service in this candidate. No global MIUI policy changes, automatic force-stop, periodic core restart, or new network listeners.
- The ordinary foreground Service must acknowledge successful promotion before JNI startup/adoption. A notification is not proof of network health.
- Keep one process-wide coordinator. Serialize start/stop/poll mutations and reject callbacks from older sessions. Activity resume must not auto-connect.
- JNI access stays behind core/EasyTierJni.kt. NoTunCore is a testable app-owned boundary over that facade.
- Store recovery material only in app-private noBackupFilesDir. Never log profile/global settings, TOML, keys, full peer data or native error payloads. Do not import real configurations into this repository.
- Preserve LICENSE, notices and upstream attribution. Original JNI .so bytes and pinned EasyTier-Core sources must remain traceable.
- Use JDK 21, Gradle 9.7.1, AGP 9.4.1, platform 37.0 and Build Tools 37.0.0. No NDK/CMake is required. SDK/JDK automatic provisioning is disabled.
- Run :app:testDebugUnitTest, :app:lintDebug and :app:assembleDebug after lifecycle changes. Include meaningful ordering, teardown and process-recovery tests. Device results must be recorded separately from JVM/model checks.
- .local/, build/, dist/, local.properties, signing keys and production data are excluded from source publication. Use tools/package_source.py for source bundles, never archive a parent directory.

Code comments and commit messages should be in English. User-facing documentation may be Chinese.
