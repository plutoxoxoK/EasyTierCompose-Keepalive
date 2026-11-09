# EasyTier Compose · No-TUN Keepalive Candidate

基于 [originalFactor/EasyTierCompose v1.0.10](https://github.com/originalFactor/EasyTierCompose/tree/d3c013b9db714271cebc20e5df0499e5fbb79a7a) 的非官方实验候选，给无 TUN 模式补齐普通 Android 前台服务。保留上游 GPL-3.0 许可证与归属；不代表上游发布或背书。

保活分支维护者：[plutoxoxo](https://github.com/plutoxoxoK)。本分支修改版权：Copyright (c) 2026 plutoxoxo；上游与第三方归属见 [NOTICE.md](NOTICE.md)。

包名 `cc.ptoe.easytier.compose.keepalive`，版本 `1.0.10-keepalive.1` / `1001001`。可与原版共存，应用数据独立。此分支只支持 **NO_TUN**，Manifest 不声明 VPN/Root 服务，不请求 VPN 同意，不替换其他 VPN。默认构建 ARM64；原生 EasyTier JNI 库保持上游字节不变。已有使用者反馈长时间保活和锁屏成功，具体版本/时长/网络没有独立回读，见 [设备反馈](docs/DEVICE-FEEDBACK.md)；未宣称解决所有 MIUI 策略或完成全部设备矩阵。

连接前先启用应用通知与“无 TUN 连接”通知渠道。前台服务成功后才允许启动核心；JNI 注册成功先显示“正在启动”，真实 `running=true` 后才显示核心运行。通知只说明本地核心状态，不将它等同于远端网络可达。

- 已有进程内会话可直接接管，不重复启动核心。
- 用户断开、启动失败、连续缺失状态或核心错误会清理服务与恢复记录；通知提供停止按钮。
- 系统回收进程后，sticky 服务可用应用私有、排除备份的会话快照恢复 **一次**；第二次回收不会自动反复重启，需用户明确连接。
- 打开界面不会新建连接；只有明确连接、一次性 sticky 恢复或用户已开启的开机启动会运行核心。Force-stop、Android 活跃应用“停止”、厂商后台限制可能禁止恢复。

请先读 [设计与限制](docs/KEEPALIVE-DESIGN.md)、[构建说明](docs/BUILD.md)、[验收与回退](docs/VALIDATION.md)。测试时不要让原应用和候选同时使用相同节点身份/IP 或 SOCKS 端口；并存安装不等于可同时运行。

```powershell
# 已具备 JDK 21、SDK platform 37.0 / Build Tools 37.0.0 时：
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

本候选不需要 NDK/CMake；不会自动下载 SDK/JDK。首次解析 Gradle/Maven 依赖需要联网。调试 APK 仅用于候选测试，使用本机生成的调试签名；正式发布签名需另行管理，源码包不包含密钥。

上游资料见 [保留的 README](README.upstream.md)，精确来源见 [UPSTREAM.md](UPSTREAM.md)，修改见 [CHANGELOG.md](CHANGELOG.md)，许可证及依赖归属见 [NOTICE.md](NOTICE.md)。`docs/BUILD-RESULTS.md` 记录本候选的实际检查结果。GitHub 工作流仅手动检查并上传工作流产物，没有发布、推送代码或发送消息步骤。

2026-11-07 本地交付检查已完成：[候选交付清单](docs/CANDIDATE-DELIVERY.md)。APK/源码 ZIP、精确提交、签名和文件摘要在独立 dist 目录中提供；固定原生源码排除两个上游 GUI 签名文件并明确记录。设备反馈与本地模型检查分别记录。

2026-11-07 已公开发布：[下载 v1.0.10-keepalive.1 实验性预发布](https://github.com/plutoxoxoK/EasyTierCompose-Keepalive/releases/tag/v1.0.10-keepalive.1)。六个附件已下载回读核对 SHA-256；APK 使用调试签名。发布前的准备记录见 [发布预案](docs/GITHUB-PUBLISH.md)。原生依赖声明、notice 来源与缺项说明见 [原生归属采集](docs/NATIVE-LICENSE-REVIEW.md)。
