package cc.ptoe.easytier.compose.core

import android.content.Context
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.mergeInto
import cc.ptoe.easytier.compose.transport.notun.NoTunServiceBridge
import cc.ptoe.easytier.compose.transport.notun.NoTunForegroundSession
import cc.ptoe.easytier.compose.transport.notun.NoTunSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** No-TUN-only candidate. The ordinary foreground Service owns the lifetime of this core.
 * All native mutations and poll publications are serialized, including late Service callbacks.
 */
class EasyTierRuntimeCoordinator internal constructor(
    private val context: Context,
    private val native: NoTunCore = JniNoTunCore,
    private val validator: ProfileValidator = ProfileValidator(),
    internal val noTunService: NoTunForegroundSession = NoTunServiceBridge(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
) {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private var activeProfile: EasyTierProfile? = null
    private var pollJob: Job? = null

    companion object {
        @Volatile private var instance: EasyTierRuntimeCoordinator? = null

        @Synchronized
        fun getInstance(context: Context): EasyTierRuntimeCoordinator = instance
            ?: EasyTierRuntimeCoordinator(context.applicationContext).also { instance = it }
    }

    suspend fun start(profile: EasyTierProfile, settings: GlobalSettings = GlobalSettings()): RuntimeStatus =
        withContext(dispatcher) {
            mutex.withLock {
                // A duplicate Connect must not periodically restart a healthy session.
                if (activeProfile != null) return@withLock mutableStatus.value
                withContext(NonCancellable) {
                    val effective = settings.mergeInto(profile.withDeviceHostnameIfBlank(context))
                    if (effective.tunMode != TunMode.NO_TUN) return@withContext failure(effective, R.string.no_tun_only)
                    val errors = try {
                        validator.validate(effective, settings)
                    } catch (error: Throwable) {
                        return@withContext failure(effective, R.string.no_tun_service_failed)
                    }
                    if (errors.isNotEmpty()) {
                        return@withContext RuntimeStatus(RuntimeState.ERROR, effective.id, TunMode.NO_TUN,
                            null, null, errors.values.first().resolve(context)).also { mutableStatus.value = it }
                    }
                    val stopped = stopLocked()
                    if (stopped.state == RuntimeState.ERROR) return@withContext stopped
                    activeProfile = effective
                    mutableStatus.value = starting(effective)
                    try {
                        val token = noTunService.enterForeground(NoTunSessionSnapshot(effective, settings))
                        require(native.start(TomlConfigBuilder.build(effective, settings)) == 0)
                        pollNoTun(effective, token)
                        mutableStatus.value
                    } catch (error: Throwable) {
                        failAndRelease(effective, R.string.no_tun_service_failed)
                    }
                }
            }
        }

    fun startDetached(profile: EasyTierProfile, settings: GlobalSettings = GlobalSettings()): Job =
        scope.launch { start(profile, settings) }

    suspend fun stop(): RuntimeStatus = withContext(dispatcher) {
        mutex.withLock { withContext(NonCancellable) { stopLocked() } }
    }

    /** Adopt an already live in-process session without calling runNetworkInstance again. */
    suspend fun attachRunningInstance(
        settings: GlobalSettings,
        profileLookup: suspend (String) -> EasyTierProfile?,
    ) = withContext(dispatcher) {
        mutex.withLock {
            if (activeProfile != null) return@withLock
            val raw = runCatching { native.collect() }.getOrNull() ?: return@withLock
            val ids = runCatching { Json.parseToJsonElement(raw).jsonObject["map"]?.jsonObject?.keys }
                .getOrNull().orEmpty()
            if (ids.isEmpty()) return@withLock
            val id = ids.first()
            val profile = profileLookup(id)?.let { settings.mergeInto(it) }
            val info = runCatching { raw.networkInfo(id) }.getOrNull()
            if (profile == null || profile.tunMode != TunMode.NO_TUN || info?.running != true || !info.error.isNullOrBlank()) {
                runCatching { native.release() }
                return@withLock
            }
            withContext(NonCancellable) {
                activeProfile = profile
                mutableStatus.value = starting(profile)
                try {
                    val token = noTunService.enterForeground(NoTunSessionSnapshot(profile, settings))
                    publish(profile, info)
                    pollNoTun(profile, token)
                } catch (error: Throwable) {
                    failAndRelease(profile, R.string.no_tun_service_failed)
                }
            }
        }
    }

    /** Called only by a successfully promoted, system-recreated sticky Service. */
    internal suspend fun restoreNoTun(token: Long, snapshot: NoTunSessionSnapshot) = withContext(dispatcher) { mutex.withLock {
        if (!noTunService.gate.owns(token)) return@withLock
        if (activeProfile != null) return@withLock
        val profile = snapshot.profile
        try {
            check(profile.tunMode == TunMode.NO_TUN)
            check(validator.validate(profile, snapshot.settings).isEmpty())
            activeProfile = profile
            mutableStatus.value = starting(profile)
            require(native.start(TomlConfigBuilder.build(profile, snapshot.settings)) == 0)
            pollNoTun(profile, token)
        } catch (error: Throwable) {
            failAndRelease(profile, R.string.no_tun_service_failed)
        }
    } }

    internal suspend fun stopNoTunIfOwned(token: Long) = withContext(dispatcher) { mutex.withLock {
        if (noTunService.gate.owns(token)) withContext(NonCancellable) { stopLocked() }
    } }

    internal fun onNoTunServiceDestroyed(token: Long) {
        scope.launch {
            mutex.withLock {
                if (!noTunService.gate.owns(token)) return@withLock
                val profile = activeProfile
                stopLocked()
                if (profile != null) failure(profile, R.string.no_tun_service_lost)
            }
        }
    }

    private suspend fun stopLocked(): RuntimeStatus {
        // Invalidate first: queued starts, notification actions and old onDestroy cannot revive it.
        noTunService.stop()
        pollJob?.cancelAndJoin()
        pollJob = null
        val profile = activeProfile
        mutableStatus.value = mutableStatus.value.copy(state = RuntimeState.STOPPING)
        val released = runCatching { native.release() == 0 }.getOrDefault(false)
        activeProfile = if (released) null else profile
        return if (!released && profile != null) failure(profile, R.string.no_tun_stop_failed)
        else RuntimeStatus.Stopped.also { mutableStatus.value = it }
    }

    private fun pollNoTun(profile: EasyTierProfile, token: Long) {
        pollJob = scope.launch {
            val detector = CoreExitDetector()
            while (true) {
                val keepGoing = mutex.withLock {
                    if (!noTunService.gate.owns(token) || activeProfile?.id != profile.id) return@withLock false
                    val info = runCatching { native.collect()?.networkInfo(profile.id) }.getOrNull()
                    if (!info?.error.isNullOrBlank() || detector.onPoll(info)) {
                        failAndRelease(profile, R.string.error_core_stopped)
                        false
                    } else {
                        if (info?.running == true) publish(profile, info)
                        true
                    }
                }
                if (!keepGoing) break
                delay(if (mutableStatus.value.state == RuntimeState.RUNNING) 5_000 else 2_000)
            }
        }
    }

    private fun publish(profile: EasyTierProfile, info: NetworkInfo) {
        mutableStatus.value = RuntimeStatus(RuntimeState.RUNNING, profile.id, TunMode.NO_TUN,
            info.virtualIpv4, null, null, info.peers, info.hostname, info.natType)
        if (profile.vpnPortal != null) {
            runCatching { WireGuardPortalClient.fetch(profile.id) }.getOrNull()?.let {
                mutableStatus.value = mutableStatus.value.copy(wireguardPortal = it)
            }
        }
    }

    private fun starting(profile: EasyTierProfile) =
        RuntimeStatus(RuntimeState.STARTING, profile.id, TunMode.NO_TUN, null, null, null)

    private fun failAndRelease(profile: EasyTierProfile, message: Int): RuntimeStatus {
        val released = runCatching { native.release() == 0 }.getOrDefault(false)
        activeProfile = if (released) null else profile
        noTunService.stop()
        return failure(profile, message)
    }

    private fun failure(profile: EasyTierProfile, message: Int): RuntimeStatus =
        RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, context.getString(message))
            .also { mutableStatus.value = it }
}
