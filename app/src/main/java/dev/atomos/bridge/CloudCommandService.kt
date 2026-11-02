package dev.atomos.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CloudCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var api: AtomosApi
    private lateinit var store: SecureStore
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        api = AtomosApi()
        store = SecureStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val token = store.get("device_token")
        if (token.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Connecting securely to Atomos Cloud"))
        if (!running) {
            running = true
            scope.launch {
                while (isActive && running) {
                    try {
                        val response = api.call("poll_command", device = store.get("device_token"))
                        val command = response.optJSONObject("command")
                        if (command != null) execute(command)
                        else delay(2200)
                    } catch (_: Exception) {
                        updateNotification("Reconnecting to Atomos Cloud")
                        delay(5000)
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun execute(command: JSONObject) {
        val id = command.optString("id")
        val type = command.optString("command_type")
        val payload = command.optJSONObject("payload") ?: JSONObject()
        val result = withContext(Dispatchers.Main) {
            val service = BridgeState.service()
            if (service == null) JSONObject().put("ok", false).put("error", "accessibility_service_unavailable")
            else service.executeCloudCommand(type, payload)
        }
        runCatching {
            api.call("complete_command", device = store.get("device_token"),
                extra = JSONObject().put("command_id", id).put("success", result.optBoolean("ok"))
                    .put("result", result).put("error", result.optString("error")))
        }
        updateNotification("Connected • Last command: $type")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Atomos cloud control", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Atomos is connected")
            .setContentText(text).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.atomos.bridge.START_CLOUD"
        const val ACTION_STOP = "dev.atomos.bridge.STOP_CLOUD"
        private const val CHANNEL_ID = "atomos_cloud_control"
        private const val NOTIFICATION_ID = 4102
    }
}