package cc.ptoe.easytier.compose.core

/** App-owned JNI boundary. Tests substitute a core that records lifecycle operations. */
internal interface NoTunCore {
    fun start(toml: String): Int
    fun release(): Int
    fun collect(): String?
}

internal object JniNoTunCore : NoTunCore {
    override fun start(toml: String) = EasyTierJni.runNetworkInstance(toml)
    override fun release() = EasyTierJni.retainNetworkInstance(null)
    override fun collect() = EasyTierJni.collectNetworkInfos(1)
}
