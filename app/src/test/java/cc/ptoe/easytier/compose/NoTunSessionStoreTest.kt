package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.notun.NoTunSessionGate
import cc.ptoe.easytier.compose.transport.notun.NoTunSessionSnapshot
import cc.ptoe.easytier.compose.transport.notun.NoTunSessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AtomicFileReplaceShadow::class])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class NoTunSessionStoreTest {
    private val fixture = NoTunSessionSnapshot(EasyTierProfile(id = "fixture", name = "Example",
        networkName = "example-only", networkSecret = "example-not-a-real-secret", virtualIpv4 = "192.0.2.3/24",
        dhcp = false, enableMagicDns = false, tunMode = TunMode.NO_TUN), GlobalSettings())

    @Test fun `exact settings survive once and recovery never resets its own budget`() {
        val context = RuntimeEnvironment.getApplication()
        val store = NoTunSessionStore(context)
        store.save(fixture)
        val recovered = NoTunSessionStore(context).consumeRecovery()!!
        assertEquals(fixture.profile, recovered.profile)
        assertEquals(fixture.settings, recovered.settings)
        assertTrue(recovered.recoveryConsumed)
        assertNull(NoTunSessionStore(context).consumeRecovery())
        assertFalse(File(context.noBackupFilesDir, "no-tun-session.json").exists())
    }

    @Test fun `stop clears persisted intent and a fresh explicit start restores budget`() {
        val store = NoTunSessionStore(RuntimeEnvironment.getApplication())
        store.save(fixture); store.consumeRecovery(); store.clear()
        assertNull(store.consumeRecovery())
        store.save(fixture); assertNotNull(store.consumeRecovery()); store.clear()
    }

    @Test fun `corrupt record is discarded without attempting recovery`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.noBackupFilesDir, "no-tun-session.json")
        file.writeText("not a session")
        assertNull(NoTunSessionStore(context).consumeRecovery()); assertFalse(file.exists())
    }

    @Test fun `non no-tun records never become a recovery session`() {
        assertNull(fixture.copy(profile = fixture.profile.copy(tunMode = TunMode.VPN_SERVICE)).recovery())
        assertNull(fixture.copy(profile = fixture.profile.copy(tunMode = TunMode.ROOT_TUN)).recovery())
    }

    @Test fun `late service acknowledgements cannot reclaim an invalidated token`() {
        val gate = NoTunSessionGate()
        val first = gate.begin(); gate.invalidate(); val replacement = gate.begin()
        assertFalse(gate.owns(first)); assertTrue(gate.owns(replacement)); assertNotEquals(first, replacement)
    }
}
