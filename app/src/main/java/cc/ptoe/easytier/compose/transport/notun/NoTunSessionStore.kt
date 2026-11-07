package cc.ptoe.easytier.compose.transport.notun

import android.content.Context
import android.util.AtomicFile
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.TunMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class NoTunSessionSnapshot(
    val profile: EasyTierProfile,
    val settings: GlobalSettings,
    val recoveryConsumed: Boolean = false,
) {
    fun recovery(): NoTunSessionSnapshot? =
        takeIf { profile.tunMode == TunMode.NO_TUN && !recoveryConsumed }
            ?.copy(recoveryConsumed = true)
}

/** Exact active settings, private to this package, excluded from Android backups.
 * Never put TOML/credentials in an Intent, notification, log or external storage.
 */
internal class NoTunSessionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "no-tun-session.json"))
    private val json = Json { ignoreUnknownKeys = true }

    fun save(snapshot: NoTunSessionSnapshot) {
        require(snapshot.profile.tunMode == TunMode.NO_TUN)
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(NoTunSessionSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    fun consumeRecovery(): NoTunSessionSnapshot? {
        val snapshot = runCatching {
            file.openRead().use { json.decodeFromString(NoTunSessionSnapshot.serializer(), it.readBytes().toString(Charsets.UTF_8)) }
        }.getOrNull()?.recovery()
        if (snapshot == null) clear() else save(snapshot)
        return snapshot
    }

    fun clear() = file.delete()
}
