package cc.ptoe.easytier.compose.transport.notun

/** Platform boundary, also used to exercise core/service ordering without native libraries. */
internal interface NoTunForegroundSession {
    val gate: NoTunSessionGate
    suspend fun enterForeground(snapshot: NoTunSessionSnapshot): Long
    fun foregroundReady(token: Long): Boolean
    fun foregroundFailed(token: Long, error: Throwable)
    fun recover(): Pair<Long, NoTunSessionSnapshot>?
    fun stopIfOwned(token: Long)
    fun stop()
}
