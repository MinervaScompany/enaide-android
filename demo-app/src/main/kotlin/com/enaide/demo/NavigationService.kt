package com.enaide.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Testo da mostrare nella notifica di navigazione, esposto come [StateFlow] in un
 * holder di processo. Il [NavViewModel] lo aggiorna durante la guida; il
 * [NavigationService] lo osserva. Disaccoppia il service dal ViewModel senza
 * binder né singleton complessi.
 */
internal object NavNotificationState {
    data class Content(val title: String, val text: String)

    private val _content = MutableStateFlow(Content("Navigazione", ""))
    val content: StateFlow<Content> = _content

    fun update(title: String, text: String) {
        _content.value = Content(title, text)
    }
}

/**
 * Foreground service che tiene viva la navigazione quando l'app è in background.
 *
 * Mostra una notifica persistente (tipo `location`) con la prossima manovra/ETA,
 * aggiornata osservando [NavNotificationState]. La logica di navigazione resta nel
 * ViewModel/SDK; il service serve solo a dichiarare al sistema che stiamo usando
 * la posizione in foreground e a non farci uccidere il processo.
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground può lanciare SecurityException su API 34+ se il permesso
        // location non è concesso (tipo "location"): in quel caso (es. modalità
        // simulata senza GPS) restiamo un service normale senza crashare.
        runCatching { startForeground(NOTIF_ID, buildNotification(NavNotificationState.content.value)) }
            .onFailure {
                runCatching {
                    notificationManager().notify(NOTIF_ID, buildNotification(NavNotificationState.content.value))
                }
            }
        // Aggiorna la notifica quando cambia il contenuto.
        scope.launch {
            NavNotificationState.content.collect { c ->
                notificationManager().notify(NOTIF_ID, buildNotification(c))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(c: NavNotificationState.Content): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Navigazione", NotificationManager.IMPORTANCE_LOW)
            notificationManager().createNotificationChannel(ch)
        }
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "enaide_navigation"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.enaide.demo.STOP_NAV"

        fun start(context: Context) {
            val i = Intent(context, NavigationService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
