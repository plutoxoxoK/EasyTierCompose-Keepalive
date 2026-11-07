package cc.ptoe.easytier.compose.transport.notun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cc.ptoe.easytier.compose.MainActivity
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.TunMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ordinary FGS: never obtains VPN consent, creates a TUN or changes another VPN. */
class EasyTierNoTunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator by lazy { EasyTierRuntimeCoordinator.getInstance(applicationContext) }
    private var token: Long? = null
    private var observation: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.no_tun_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra(EXTRA_TOKEN, -1L)?.takeIf { it >= 0 }
        if (intent?.action == ACTION_STOP) {
            requested?.let { scope.launch { coordinator.stopNoTunIfOwned(it) } }
            return if (token != null) START_STICKY else START_NOT_STICKY.also { stopSelfResult(startId) }
        }
        // Promote synchronously, before disk access, validation or any JNI work.
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION, notification(R.string.no_tun_starting, requested), type)
            if (Build.VERSION.SDK_INT >= 33) {
                check(ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            }
            check(NotificationManagerCompat.from(this).areNotificationsEnabled())
            if (Build.VERSION.SDK_INT >= 26) {
                check(getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE)
            }
        } catch (error: Throwable) {
            requested?.let { coordinator.noTunService.foregroundFailed(it, error) }
            // A sticky recreation must not leave a dead recovery record behind either.
            if (requested == null) coordinator.noTunService.stop()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (intent == null) {
            val recovery = runCatching { coordinator.noTunService.recover() }.getOrNull()
            if (recovery == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            token = recovery.first
            observe(recovery.first, startId)
            scope.launch {
                coordinator.restoreNoTun(recovery.first, recovery.second)
            }
        } else if (requested != null && coordinator.noTunService.foregroundReady(requested)) {
            token = requested
            observe(requested, startId)
        } else {
            // A delayed start command cannot revive a stopped or replaced session.
            if (token == null || !coordinator.noTunService.gate.owns(token!!)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun observe(session: Long, startId: Int) {
        observation?.cancel()
        observation = scope.launch {
            coordinator.status.collect { status ->
                if (!coordinator.noTunService.gate.owns(session)) return@collect
                if (status.tunMode == TunMode.NO_TUN) {
                    when (status.state) {
                        RuntimeState.STARTING, RuntimeState.RUNNING -> {
                            val text = if (status.state == RuntimeState.RUNNING) R.string.no_tun_running else R.string.no_tun_starting
                            // No addresses, profile names, peers, secrets or native errors on the lock screen.
                            if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@EasyTierNoTunService,
                                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                NotificationManagerCompat.from(this@EasyTierNoTunService).notify(NOTIFICATION, notification(text, session))
                            } else {
                                coordinator.stopNoTunIfOwned(session)
                            }
                        }
                        RuntimeState.ERROR, RuntimeState.STOPPED -> {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(startId)
                        }
                        RuntimeState.STOPPING -> Unit
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        token?.let { coordinator.onNoTunServiceDestroyed(it) }
        super.onDestroy()
    }

    private fun notification(text: Int, session: Long?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_no_tun_service)
            .setContentTitle(getString(R.string.no_tun_title))
            .setContentText(getString(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (session != null) {
            val stop = PendingIntent.getService(this, session.toInt(),
                Intent(this, EasyTierNoTunService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_TOKEN, session),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, getString(R.string.no_tun_stop), stop)
        }
        return builder.build()
    }

    companion object {
        const val EXTRA_TOKEN = "no_tun_session_token"
        private const val ACTION_STOP = "cc.ptoe.easytier.compose.keepalive.STOP_NO_TUN"
        private const val CHANNEL = "easytier_no_tun"
        private const val NOTIFICATION = 1002
    }
}
