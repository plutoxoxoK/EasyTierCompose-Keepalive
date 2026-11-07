package cc.ptoe.easytier.compose.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.data.GlobalSettingsRepository
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.mergeInto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Boot is opt-in per selected profile. Activity resume never starts a new connection. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext
                withTimeoutOrNull(8_000) {
                    val repo = ProfileRepository(app)
                    val profiles = repo.profiles.first()
                    val selected = repo.selectedProfileId.first()
                    val profile = profiles.firstOrNull { it.id == selected } ?: profiles.firstOrNull() ?: return@withTimeoutOrNull
                    val settings = GlobalSettingsRepository(app).settings.first()
                    val effective = settings.mergeInto(profile)
                    if (effective.tunMode == TunMode.NO_TUN && effective.startOnBoot) {
                        EasyTierRuntimeCoordinator.getInstance(app).startDetached(profile, settings).join()
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}
