package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.core.NativeConfigParser
import cc.ptoe.easytier.compose.core.NoTunCore
import cc.ptoe.easytier.compose.core.ProfileValidator
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.notun.NoTunForegroundSession
import cc.ptoe.easytier.compose.transport.notun.NoTunSessionGate
import cc.ptoe.easytier.compose.transport.notun.NoTunSessionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class NoTunLifecycleTest {
    private val profile = EasyTierProfile(id = "fixture", name = "Example", networkName = "example-only",
        networkSecret = "example-not-a-real-secret", virtualIpv4 = "192.0.2.3/24", dhcp = false,
        enableMagicDns = false, tunMode = TunMode.NO_TUN, hostname = "example-device")

    private class Core(private val events: MutableList<String>) : NoTunCore {
        var starts = 0
        var startResult = 0
        var releaseResult = 0
        var raw: String? = """{"map":{"fixture":{"running":true}}}"""
        override fun start(toml: String): Int { events += "core-start"; starts++; return startResult }
        override fun release(): Int { events += "core-release"; return releaseResult }
        override fun collect(): String? = raw
    }

    private class Foreground(private val events: MutableList<String>) : NoTunForegroundSession {
        override val gate = NoTunSessionGate()
        var ready = CompletableDeferred<Unit>().also { it.complete(Unit) }
        var fail = false
        override suspend fun enterForeground(snapshot: NoTunSessionSnapshot): Long {
            val token = gate.begin()
            ready.await()
            if (fail) error("foreground start denied")
            events += "foreground-ready"
            return token
        }
        override fun stop() { gate.invalidate(); events += "foreground-stop" }
        override fun stopIfOwned(token: Long) { if (gate.owns(token)) stop() }
        override fun foregroundReady(token: Long) = gate.owns(token)
        override fun foregroundFailed(token: Long, error: Throwable) = Unit
        override fun recover(): Pair<Long, NoTunSessionSnapshot>? = null
    }

    @Test fun `core waits for foreground and duplicate connect never restarts it`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events); val fg = Foreground(events)
        fg.ready = CompletableDeferred()
        val coordinator = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        val start = launch { coordinator.start(profile) }; runCurrent()
        assertEquals(0, core.starts)
        assertEquals(RuntimeState.STARTING, coordinator.status.value.state)
        fg.ready.complete(Unit); runCurrent(); start.join()
        assertTrue(events.indexOf("foreground-ready") < events.indexOf("core-start"))
        assertEquals(RuntimeState.RUNNING, coordinator.status.value.state)
        coordinator.start(profile); assertEquals(1, core.starts)
        coordinator.stop(); runCurrent()
        assertEquals(RuntimeState.STOPPED, coordinator.status.value.state)
        assertNull(fg.gate.current())
    }

    @Test fun `foreground denial never starts native core`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events); val fg = Foreground(events).also { it.fail = true }
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        assertEquals(RuntimeState.ERROR, c.start(profile).state)
        assertEquals(0, core.starts); assertNull(fg.gate.current()); c.stop()
    }

    @Test fun `JNI start failure removes foreground and cannot show running`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events).also { it.startResult = 1 }; val fg = Foreground(events)
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        assertEquals(RuntimeState.ERROR, c.start(profile).state)
        assertNull(fg.gate.current()); assertEquals("core-release", events[events.size - 2]); c.stop()
    }

    @Test fun `registered but never alive core times out and removes service`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events).also { it.raw = null }; val fg = Foreground(events)
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        c.start(profile); runCurrent(); assertEquals(RuntimeState.STARTING, c.status.value.state)
        advanceTimeBy(4_001); runCurrent()
        assertEquals(RuntimeState.ERROR, c.status.value.state); assertNull(fg.gate.current()); c.stop()
    }

    @Test fun `explicit native error tears down service even with running true`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events); val fg = Foreground(events)
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        c.start(profile); runCurrent()
        core.raw = """{"map":{"fixture":{"running":true,"error_msg":"fixture failure"}}}"""
        advanceTimeBy(5_001); runCurrent()
        assertEquals(RuntimeState.ERROR, c.status.value.state); assertNull(fg.gate.current())
        assertEquals(1, core.starts); c.stop()
    }

    @Test fun `adoption promotes foreground without starting a second native instance`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events); val fg = Foreground(events)
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        c.attachRunningInstance(GlobalSettings()) { profile }; runCurrent()
        assertEquals(RuntimeState.RUNNING, c.status.value.state); assertEquals(0, core.starts)
        assertTrue(events.contains("foreground-ready")); c.stop()
    }

    @Test fun `old destroy and stop actions cannot tear down replacement session`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events); val fg = Foreground(events)
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        c.start(profile); runCurrent(); val old = fg.gate.current()!!; c.stop()
        c.start(profile); runCurrent(); c.onNoTunServiceDestroyed(old); c.stopNoTunIfOwned(old); runCurrent()
        assertEquals(RuntimeState.RUNNING, c.status.value.state); assertEquals(2, core.starts); c.stop()
    }

    @Test fun `activity coroutine cancellation during handshake cannot orphan the core`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events); val fg = Foreground(events)
        fg.ready = CompletableDeferred()
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        val caller = launch { c.start(profile) }; runCurrent(); caller.cancel()
        fg.ready.complete(Unit); runCurrent()
        assertEquals(RuntimeState.RUNNING, c.status.value.state); assertNotNull(fg.gate.current()); c.stop()
    }

    @Test fun `unexpected service destruction releases core and reports error`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events); val fg = Foreground(events)
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        c.start(profile); runCurrent(); c.onNoTunServiceDestroyed(fg.gate.current()!!); runCurrent()
        assertEquals(RuntimeState.ERROR, c.status.value.state); assertNull(fg.gate.current()); c.stop()
    }

    @Test fun `failed release blocks another start until explicit stop succeeds`() = runTest {
        val events = mutableListOf<String>(); val core = Core(events); val fg = Foreground(events)
        val c = EasyTierRuntimeCoordinator(RuntimeEnvironment.getApplication(), core,
            ProfileValidator(NativeConfigParser { null }), fg, StandardTestDispatcher(testScheduler), backgroundScope)
        c.start(profile); runCurrent(); core.releaseResult = 1
        assertEquals(RuntimeState.ERROR, c.stop().state)
        assertEquals(RuntimeState.ERROR, c.start(profile).state); assertEquals(1, core.starts)
        core.releaseResult = 0; c.stop()
    }
}
