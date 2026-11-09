# GitHub 发布预案

## 2026-11-09 身份与发布状态更新

GitHub 账号已由 `plutoxoxoK` 更名为 `Axlyro`；当前仓库地址为 [Axlyro/EasyTierCompose-Keepalive](https://github.com/Axlyro/EasyTierCompose-Keepalive)。该仓库与 `v1.0.10-keepalive.1` 实验性预发布已于 2026-11-07 公开，现有附件与校验和未变。下方 2026-11-07 的本地准备计划及“尚未建仓、上传、推送或发布”的表述保留为当时的历史状态，其中旧账号路径不再代表当前目标。

2026-11-07。仓库名已由使用者选择：`EasyTierCompose-Keepalive`；改名完成后已重新回读 GitHub 身份，账号为 `plutoxoxoK`。目标为 `plutoxoxoK/EasyTierCompose-Keepalive`。当前只做本地准备，没有建仓、上传、推送或发布。

| 项目 | 准备值 |
|---|---|
| 仓库 | plutoxoxoK/EasyTierCompose-Keepalive |
| 可见性 | 拟公开，实际操作前确认 |
| 默认分支 | main |
| 描述 | Unofficial experimental NO_TUN foreground-service fork of EasyTierCompose v1.0.10 |
| 标签 | v1.0.10-keepalive.1 |
| Release | 实验性预发布，先建 draft 审阅 |
| APK | 当前 ARM64 debug 签名候选，原文件不重签、不重编 |
| 长期签名 | 尚未建立；本次不生成或上传密钥 |

## 本地源码快照

开发仓库是浅克隆，保留在原位。发布暂存另建单根提交，不携带开发 refs、远程凭据、本机环境或运维目录；应用文件内容、文件模式与固定原生子模块 gitlink 必须与审阅后的开发提交 tree 完全一致。上游来源、许可证、作者归属和修改说明均保留，这不是宣称原始创作或官方 fork 背书。

固定 EasyTier 子模块仍从上游获取；不要直接上传本机原生 checkout，因为上游已跟踪两个 GUI 签名文件。完整构建源码附件使用 tools/package_source.py 的显式排除版本，精确排除路径见 UPSTREAM.md。GitHub 自动生成的 Source code.zip 不包含子模块完整源码，不能代替额外附上的完整 source.zip。

本地暂存、附件白名单、快照 commit/tree、开发来源 commit、原生 pin、签名、摘要和检查结果写入忽略目录下的发布预案。没有执行其中的外部写入步骤。

## 发布顺序

1. 确认最终仓库归属、公开范围、标签、预发布正文、调试签名及原生来源/许可证限制。
2. 只从独立暂存仓库推送 main 与指定标签；不使用 `--mirror`、`--all`，不推开发目录/父目录，也不把子模块仓库另行推送。
3. 显式指定已准备标签与提交创建 draft / prerelease；正文从文件读取，只上传校验清单内的附件。GitHub CLI 支持 `--draft`、`--prerelease`、`--verify-tag` 和 `--notes-file`，具体说明见 [官方命令文档](https://cli.github.com/manual/gh_release_create)。
4. 回读仓库树、标签目标、Release 状态、附件文件名/大小；下载附件后核对 SHA-256，再考虑将 draft 公开。对外发布应覆盖清晰的非官方标记、签名范围、设备反馈与未验证项。

## 已知边界

APK 来自固定上游 JNI 字节，没有重新编译 Rust 核心；对应源码的逐位重建未建立。原生依赖清单按锁定 JNI 依赖图保守收集，含其他目标/特性的依赖，不是从最终 APK 提取的精确 SBOM，也不能凭 metadata/notice 齐备就宣称所有分发义务已经审查完成。

使用者报告长时间保活及锁屏成功，具体设备运行证据边界见 [DEVICE-FEEDBACK.md](DEVICE-FEEDBACK.md)。保留 force-stop、一次 sticky 恢复预算及厂商策略限制，不宣传“永久保活”。
