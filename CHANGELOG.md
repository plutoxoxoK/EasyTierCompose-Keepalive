# 1.0.10-keepalive.1 — 2026-11-06

Unofficial experimental no-TUN-only candidate based on EasyTierCompose v1.0.10.

- Add a real ordinary foreground Service, low-importance ongoing notification and a session-scoped Stop action. Promote before starting/adopting the core.
- Keep STARTING until the core actually reports running. Release the core, service and recovery intent on startup failure, native errors, confirmed disappearance or user stop.
- Serialize native mutations and status publication. Ignore stale service callbacks/actions; repeated Connect does not restart a live core. Activity cancellation during startup cannot leave an unowned core.
- Support one bounded system sticky recovery from an exact private, backup-excluded snapshot. No periodic restart, automatic force-stop or Activity-resume reconnect.
- Give the candidate a distinct package/name/version. Limit supported mode to NO_TUN, remove VPN/Root service declarations and unused NDK/CMake build requirements. Existing VPN applications retain their role.
- Remove global-settings value logging, disable app data backup, and keep lock-screen notifications free of profile/peer/credential details.
- Add lifecycle/recovery tests, a manual read-only CI workflow, provenance, build/validation/rollback guides and clean-source packaging.

No phone installation or production configuration change was performed. MIUI freeze resistance, low-memory recovery and long-duration connectivity remain device acceptance work. Shizuku is assessed as an optional external diagnostic capability; it is not a runtime dependency or implemented recovery mechanism in this candidate.

2026-11-07 distribution preparation: verify the unchanged APK signature, manifest and native bytes; export pinned Git source bytes independent of Windows line endings, preserve committed Rust build scripts, explicitly omit upstream GUI signing material, and record file-level provenance. Packaging writes atomically so a rejected export does not replace an existing complete bundle. No runtime changes were added in this follow-up.
