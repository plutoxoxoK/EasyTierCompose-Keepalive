package cc.ptoe.easytier.compose

import android.app.Application
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.core.NativeConfigParser
import cc.ptoe.easytier.compose.core.NoTunCore
import cc.ptoe.easytier.compose.core.ProfileValidator
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.notun.EasyTierNoTunService
import cc.ptoe.easytier.compose.transport.notun.NoTunServiceBridge
import cc.ptoe.easytier.compose.transport.notun.NoTunSessionSnapshot
import cc.ptoe.easytier.compose.transport.notun.NoTunSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android Service/AtomicFile checks in a JVM model, not a claim about OEM behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AtomicFileReplaceShadow::class])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class NoTunAndroidServiceTest {
    private val profile = EasyTierProfile(id = "fixture", name = "Example", networkName = "example-only",
        networkSecret = "example-not-a-real-secret", virtualIpv4 = "192.0.2.3/24", dhcp = false,
        enableMagicDns = false, tunMode = TunMode.NO_TUN, hostname = "example-device")

    private class Core : NoTunCore {
        var starts = 0
        override fun start(toml: String): Int { starts++; return 0 }
        override fun release() = 0
        override fun collect() = """{"map":{"fixture":{"running":true}}}"""
    }

    private fun install(c: EasyTierRuntimeCoordinator?) {
        EasyTierRuntimeCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, c)
    }

    @Test fun `real service foreground acknowledgement precedes native start`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication(); val core = Core()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val coordinator = EasyTierRuntimeCoordinator(app, core, ProfileValidator(NativeConfigParser { null }),
            NoTunServiceBridge(app), StandardTestDispatcher(testScheduler), backgroundScope)
        install(coordinator)
        val service = Robolectric.buildService(EasyTierNoTunService::class.java).create()
        try {
            val job = launch { coordinator.start(profile) }; runCurrent()
            assertEquals(0, core.starts)
            val command = shadowOf(app).nextStartedService!!
            assertEquals(android.app.Service.START_STICKY, service.get().onStartCommand(command, 0, 1))
            runCurrent(); job.join()
            assertNotNull(shadowOf(service.get()).lastForegroundNotification)
            assertEquals(1, core.starts); assertEquals(RuntimeState.RUNNING, coordinator.status.value.state)
            coordinator.stop(); runCurrent()
        } finally {
            coordinator.stop(); service.destroy(); runCurrent(); NoTunSessionStore(app).clear(); install(null); Dispatchers.resetMain()
        }
    }

    @Test fun `sticky recreation restores a real core once and exhausted record restores none`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication(); val core = Core()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val coordinator = EasyTierRuntimeCoordinator(app, core, ProfileValidator(NativeConfigParser { null }),
            NoTunServiceBridge(app), StandardTestDispatcher(testScheduler), backgroundScope)
        install(coordinator)
        val snapshot = NoTunSessionSnapshot(profile, GlobalSettings())
        NoTunSessionStore(app).save(snapshot)
        val first = Robolectric.buildService(EasyTierNoTunService::class.java).create()
        try {
            assertEquals(android.app.Service.START_STICKY, first.get().onStartCommand(null, 0, 1))
            runCurrent(); assertEquals(1, core.starts)
            assertEquals(RuntimeState.RUNNING, coordinator.status.value.state)
            coordinator.stop(); runCurrent(); first.destroy(); runCurrent()
            // Recreate process state with an already consumed recovery record.
            NoTunSessionStore(app).save(snapshot.copy(recoveryConsumed = true))
            val secondCore = Core()
            val secondCoordinator = EasyTierRuntimeCoordinator(app, secondCore, ProfileValidator(NativeConfigParser { null }),
                NoTunServiceBridge(app), StandardTestDispatcher(testScheduler), backgroundScope)
            install(secondCoordinator)
            val second = Robolectric.buildService(EasyTierNoTunService::class.java).create()
            assertEquals(android.app.Service.START_NOT_STICKY, second.get().onStartCommand(null, 0, 2))
            runCurrent(); assertEquals(0, secondCore.starts)
            assertEquals(RuntimeState.STOPPED, secondCoordinator.status.value.state)
            second.destroy(); secondCoordinator.stop(); runCurrent()
        } finally {
            coordinator.stop(); runCurrent(); NoTunSessionStore(app).clear(); install(null); Dispatchers.resetMain()
        }
    }
}
