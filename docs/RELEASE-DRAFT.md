# 1.0.10-keepalive.1 — experimental no-TUN keepalive

这是基于 originalFactor/EasyTierCompose v1.0.10 的**非官方实验性预发布**，仅支持 NO_TUN，提供 ARM64 调试签名 APK。它不申请 Android VPN，不依赖 root/Shizuku，也不改变用户的服务地址或自动配置其他代理客户端。

## 主要变化

- 启动/接管核心前，先建立普通前台服务与持续通知；核心真实报告运行后才显示 RUNNING。
- 用户停止、启动失败、原生异常及确认掉线时，清理核心、服务和恢复意图；拒绝旧会话回调影响新连接。
- 系统 sticky 重建可从应用私有、排除备份的快照恢复一次；不周期强停重启，也不在打开界面时自动连接。
- 使用独立包名 cc.ptoe.easytier.compose.keepalive，可保留原应用。相同节点身份/IP/端口的实例不能同时运行。

## 验证范围

2026-11-06：91 项 JVM/Robolectric 测试通过，Lint 0 错误/44 警告。2026-11-07：APK v2 签名、Manifest、原生库摘要、对应源码的逐 Git blob 核验及已知敏感格式/私有标记扫描通过；运行代码与已保存候选一致。

使用者于 2026-11-07 反馈手机长时间保活和锁屏测试成功。具体 APK 版本、持续时长及网络未由本任务独立回读，尚未形成标准化完整设备矩阵；详见源码中的 docs/DEVICE-FEEDBACK.md。真实 LOW_MEMORY 恢复、切网、重启和代理节点故障对照仍待验，不能宣传全部 MIUI 已修复或永久保活。

## 使用前

minSdk 24 / targetSdk 36，仅 ARM64；连接前允许通知与“无 TUN 连接”渠道。NO_TUN 提供本机 SOCKS，需要现有 VPN/代理客户端或支持 SOCKS 的应用把目标流量交给它。是否经过远程代理取决于实际分流规则，没有自动直连/代理故障切换功能。

先停止使用相同身份/IP/端口的原 EasyTier 会话，再启动候选。回退时停止候选并确认释放核心/端口，再手动启动保留的原应用。force-stop、系统“停止”和厂商策略可能阻止恢复。完整说明见源码中的 README.md、docs/VALIDATION.md 和 docs/KEEPALIVE-DESIGN.md。

## 下载与签名

APK：29,366,982 字节，SHA-256 9384db5630776a491e70a24bf5d05c06abc09d637ddc440ccfc8ba9567e23aa1。

Android Debug 证书 SHA-256：8ff570dd4e82dcec45d0883f5231351cc8a880c27afd4f059fee46e8c7be4a8b。这是测试身份；长期稳定签名与后续更新兼容性尚未承诺。本次没有重新签名或重编 APK。

附件包括 APK、完整应用/固定原生构建源码 ZIP、DELIVERY-MANIFEST.json、APK-SIGNATURE.txt、SHA256SUMS 和本发布说明。以 SHA256SUMS 核对文件；GitHub 自动生成的 Source code.zip 不包含完整子模块源码，不能代替附加的完整 source.zip。

## 来源与归属

GPL-3.0 / LGPL-3.0、上游归属、96 项 Android 运行依赖及内嵌 notice 随源码提供。另采集 688 包的保守 JNI 依赖图及 1284 份原生 notice；15 项异常分别记录源包版权头、仓库许可证证据或只有元数据的情况，其中 5 项未找到包专属 notice，见 docs/NATIVE-LICENSE-REVIEW.md。采集清单不等于精确 APK SBOM 或全部分发义务证明。

JNI 库沿用固定上游字节，没有重编，逐位源码/二进制可重现未建立。源码包明确排除上游自带的两个 GUI 签名文件，构建源码及排除说明保留在 SOURCE-MANIFEST.json。上游版本、固定核心提交与全部摘要见 UPSTREAM.md 和附件清单；没有上游官方背书。
