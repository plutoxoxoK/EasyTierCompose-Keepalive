# 无 TUN 生命周期设计

## 服务与核心

`EasyTierNoTunService` 继承普通 `Service`，Manifest 声明 `specialUse` 及用途说明，不声明 `BIND_VPN_SERVICE`。选择它是因为持续的 overlay/SOCKS 传输需要由用户可见的持续服务承载，不使用有 Android 15 时长限制的 `dataSync` 类型。参见 [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types) 与 [系统变更](https://developer.android.com/develop/background-work/services/fgs/changes)。这不是应用商店审核批准；未来商店分发需按实际用途另行审查。

启动在 IO 调度器执行：验证无 TUN 配置 → 清理旧恢复意图 → 发布 STARTING → `startForegroundService` → Service 同步 `startForeground` → 进程内握手确认 → 私有快照落盘 → JNI 启动 → 状态轮询。通知未授权/渠道被禁用时拒绝连接并显示错误。握手等待有 8 秒上限；核心登记后约 4 秒内的三次连续未运行观察会被视为启动失败。运行后的单次缺失不直接判死，连续三次缺失才清理；显式核心错误或已活跃后的 `running=false` 会立即清理。

界面、开机接收器和服务共用一个协调器。Mutex 串行化启动、停止、原生轮询及状态发布；每个服务会话有递增 token，旧 `onDestroy`、晚到的启动指令和旧通知停止动作不能修改新会话。重复 Connect 返回当前状态，不重启已有会话。启动关键区不随 Activity/ViewModel 协程取消而半途遗留核心；操作仍有前台握手超时。已有进程内核心的接管先取得前台服务，确认原生 `running=true`，不再调用原生启动。

本地核心 RUNNING 仅说明原生状态接口报告运行，不说明节点可达、SOCKS 能处理连接或 HTTPS 可达。通知明确保留这一限制。应用整体被厂商冻结时，内部轮询也会一起停止；不能将应用内轮询称为独立的冻结检测器。

## 停止与失败

断开/通知停止先撤销 token、清除恢复文件并停止服务，再取消并等待轮询结束、释放 JNI，最后发布 STOPPED。启动失败、状态超时和核心错误也会释放 JNI/恢复意图/服务。若 JNI 释放返回失败，界面报告错误并保留阻止再次 Connect 的进程内保护，必须显式停止成功后才允许再启动；不能将“已调用释放”写成“已确认停止”。

服务意外销毁时，协调器核对 token 后释放核心并报告服务丢失。服务停止是有意的或属于旧会话时，回调无效。内存压力直接杀死整个进程时没有 `onDestroy` 保证，因此使用下一节的系统 sticky 恢复。

## 进程重建

`START_STICKY` 允许系统重建服务；系统不保证何时或一定重建。只有服务收到空 Intent 的系统重建路径才读取恢复文件，Activity 恢复不读取它来创建新连接。文件是应用内部 `noBackupFilesDir/no-tun-session.json` 的 AtomicFile，保存已合并的运行配置快照及全局设置，可能含网络凭据，因此不进入日志、Intent、通知、外部存储或发布材料。应用备份也禁用，并在备份规则中排除数据。

每次明确用户/已开启的开机启动只给一次自动恢复机会。恢复前先持久化 `recoveryConsumed=true`，然后重启真实核心、等状态确认；不会先显示 RUNNING。如果再次被杀、文件损坏或模式不是 NO_TUN，清除记录并停止服务，等待明确连接。原生异常退出不触发内部重启。这项限额防止崩溃/资源冲突后的重启循环，代价是反复低内存回收需要人工重连。

## MIUI 冻结、低内存与 Shizuku

已有诊断分别观察到厂商冻结（PID 与监听保留但不处理流量）和 LOW_MEMORY 进程终止。真正的前台服务提高进程的重要性，是针对原生无 TUN 缺少 Service 的修复；它不是 MIUI 冻结豁免的证明，也不能保证不被低内存终止、进入深度休眠或 OEM 策略限制。此候选没有常驻 WakeLock，不反复请求已允许的 Doze 白名单，不关闭全局 MIUI 优化，不停用电池管理。

Shizuku 不参与默认执行路径。已有 Shizuku 不会自动给本应用提供服务或冻结豁免；shell 权限也不等于 root/任意进程优先级控制。若日后评估包级豁免，只能先只读核对设备支持与权限，再独立审批具体变更；不把 force-stop/循环拉起 Activity 当作持续在线。参考 [Shizuku 官方说明](https://shizuku.rikka.app/zh-hans/introduction/) 和 [API/UserService](https://github.com/RikkaApps/Shizuku-API)。

验证必须分别记录服务/通知、本地核心、冻结标记、远端 peer、匿名严格 TLS 探针及真实客户端操作；本地测试与实机结果不互相替代。详见 VALIDATION.md。
