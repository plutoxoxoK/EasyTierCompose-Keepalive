# 1.0.10-keepalive.1 候选交付

2026-11-07（香港时间）。本地可审阅材料已准备完成；本任务没有执行手机安装或公开发布。使用者随后反馈长时间保活及锁屏成功，具体版本/时长/网络未独立核验，见 [设备反馈](DEVICE-FEEDBACK.md)。真实低内存恢复与完整设备矩阵仍待验。

## 分发文件

这些文件由独立应用目录的 dist 提供；dist 不进入 Git，也不要打包父运维工作区。

| 文件 | 用途 |
|---|---|
| EasyTier-1.0.10-keepalive.1-arm64-debug.apk | 独立包名、ARM64、调试签名的候选应用 |
| EasyTier-keepalive-1.0.10-keepalive.1-source.zip | 已提交应用与固定原生构建源码，内含逐文件 SOURCE-MANIFEST.json |
| DELIVERY-MANIFEST.json | 精确提交、大小、SHA-256、签名、全部 APK 原生库摘要和扫描计数 |
| APK-SIGNATURE.txt | apksigner 对该 APK 的验证结果和公开证书指纹 |
| SHA256SUMS | 全部交付附件的 SHA-256；不包含自身 |
| RELEASE-NOTES.md | 可审阅的 GitHub 发布正文草稿 |

APK：29,366,982 字节，SHA-256 `9384db5630776a491e70a24bf5d05c06abc09d637ddc440ccfc8ba9567e23aa1`。

Android Debug 证书 SHA-256：`8ff570dd4e82dcec45d0883f5231351cc8a880c27afd4f059fee46e8c7be4a8b`。包名 `cc.ptoe.easytier.compose.keepalive`，versionCode `1001001`，minSdk 24 / targetSdk 36。最终源码 ZIP 摘要由附件清单给出，避免将包含自身的源码摘要写回源码。

## 源码与许可证范围

应用基线：`d3c013b9db714271cebc20e5df0499e5fbb79a7a`；固定核心：`81487c363a56779e745eae927e130a813535c71e`。APK 对应的运行代码与保存提交 `57887108b718cf436c3764b4126d553dbb94694e` 一致；后续提交只完善打包与文档。最终导出提交在 DELIVERY-MANIFEST.json / SOURCE-MANIFEST.json。

ZIP 逐文件核对固定 Git blob，保留 JNI 构建脚本、Rust 构建源码与依赖锁文件；排除固定上游自带的两个 GUI 签名文件（详见 [UPSTREAM.md](../UPSTREAM.md) 与压缩包排除清单），不影响 JNI 构建源码。没有包含 `.local`、本机环境回退、私有配置、签名密钥或运维目录。原生库沿用上游字节，尚未证明固定源码能逐位重建这些二进制。

GPL-3.0、LGPL-3.0、Apache-2.0、归属说明、96 项运行依赖记录及 60 份内嵌 notice 随源码提供，入口为 [NOTICE.md](../NOTICE.md)。两个 libsu POM 许可证元数据缺项已明确补录。另采集 688 包的保守 JNI 依赖图及 1284 份 notice，15 项异常均有显式说明，其中 5 项没有找到包专属 notice。具体声明、固定来源和缺项见 [原生归属记录](NATIVE-LICENSE-REVIEW.md)；不把它当作精确 APK SBOM 或全部分发义务证明。

## 检查和后续验收

最终 APK 签名、Manifest、全部原生库来源/摘要与 ZIP 对齐检查通过；第一方源码、APK 和完整源码 ZIP 的已知敏感格式/私有部署标记扫描无命中。源包逐文件 Git 对象验证通过。扫描不保证识别所有秘密格式。

2026-11-06 的 91 项 JVM/Robolectric 测试通过，Lint 0 错误/44 警告；本轮重新核对保存报告，未为打包/文档重复运行生命周期检查。实际结果见 [BUILD-RESULTS.md](BUILD-RESULTS.md)。使用者成功反馈与 agent 独立设备验证分别记录，不能扩展成全部厂商/低内存策略已验。

安装前单独确认停止原 EasyTier 会话、安装独立候选及切换窗口，避免相同身份/IP/回环端口争用；FlClash VPN 与服务地址保持原配置。验收按 [VALIDATION.md](VALIDATION.md) 记录后台、锁屏、网络切换、真实 LOW_MEMORY 与实际客户端同步。回退先停止候选并确认释放，再手动启动保留的原应用。

GitHub 建仓/推送/Release 仍需单独确认。发布草稿在 [RELEASE-DRAFT.md](RELEASE-DRAFT.md)；目标仓库为 plutoxoxoK/EasyTierCompose-Keepalive，拟先作为实验性预发布。独立发布快照、附件白名单及回读步骤见 [GitHub预案](GITHUB-PUBLISH.md)；调试签名不作长期更新身份承诺。
