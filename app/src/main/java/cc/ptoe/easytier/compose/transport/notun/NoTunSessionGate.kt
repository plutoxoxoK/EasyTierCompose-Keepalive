package cc.ptoe.easytier.compose.transport.notun

/** Rejects late service callbacks from a stopped/replaced session. No Android or JNI state. */
internal class NoTunSessionGate {
    private var generation = 0L
    private var current: Long? = null

    @Synchronized fun begin(): Long = (++generation).also { current = it }
    @Synchronized fun owns(token: Long): Boolean = current == token
    @Synchronized fun invalidate(): Long? = current.also { current = null }
    @Synchronized fun current(): Long? = current
}
