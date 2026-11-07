package cc.ptoe.easytier.compose.transport.notun

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Same-process handshake. Core startup is allowed only AFTER startForeground succeeds. */
internal class NoTunServiceBridge(private val context: Context) : NoTunForegroundSession {
    override val gate = NoTunSessionGate()
    private val store = NoTunSessionStore(context)
    private var ready: CompletableDeferred<Unit>? = null

    override suspend fun enterForeground(snapshot: NoTunSessionSnapshot): Long {
        val completion = CompletableDeferred<Unit>()
        val token = synchronized(this) {
            ready?.completeExceptionally(IllegalStateException("Session replaced"))
            ready = completion
            gate.begin()
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, EasyTierNoTunService::class.java)
                .putExtra(EasyTierNoTunService.EXTRA_TOKEN, token))
            withTimeout(8_000) { completion.await() }
            synchronized(this) {
                check(gate.owns(token))
                store.save(snapshot)
            }
            return token
        } catch (error: Throwable) {
            stopIfOwned(token)
            throw error
        }
    }

    @Synchronized override fun foregroundReady(token: Long): Boolean {
        if (!gate.owns(token)) return false
        ready?.complete(Unit)
        return true
    }

    @Synchronized override fun foregroundFailed(token: Long, error: Throwable) {
        if (gate.owns(token)) ready?.completeExceptionally(error)
    }

    /** One automatic process recovery per explicit user/boot start; never a restart loop. */
    @Synchronized override fun recover(): Pair<Long, NoTunSessionSnapshot>? {
        if (gate.current() != null) return null
        val snapshot = store.consumeRecovery() ?: return null
        return gate.begin() to snapshot
    }

    @Synchronized override fun stopIfOwned(token: Long) {
        if (gate.owns(token)) stop()
    }

    @Synchronized override fun stop() {
        gate.invalidate()
        ready?.completeExceptionally(IllegalStateException("Session stopped"))
        ready = null
        store.clear()
        context.stopService(Intent(context, EasyTierNoTunService::class.java))
    }
}
