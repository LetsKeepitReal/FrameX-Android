package com.framex.app.overlay

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.framex.app.gaming.GamingModeEngine
import com.framex.app.utils.FrameXLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles taps on the FrameX overlay notification's action buttons.
 *
 * Manifest-declared (not runtime-registered) so delivery does not depend on our process
 * already being alive when the system dispatches the [android.app.PendingIntent]. Not
 * exported: only our own PendingIntents target this receiver.
 *
 * All actions are debounced against rapid double-taps, since a system tray button can be
 * tapped faster than a rebuild-and-renotify cycle completes.
 */
@AndroidEntryPoint
class OverlayNotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var overlayManager: OverlayManager

    @Inject
    lateinit var gamingModeEngine: GamingModeEngine

    override fun onReceive(context: Context, intent: Intent) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastActionElapsedMs < DEBOUNCE_WINDOW_MS) {
            FrameXLog.d("Ignoring notification action tap within debounce window")
            return
        }
        lastActionElapsedMs = now

        when (intent.action) {
            ACTION_START_OVERLAY -> {
                overlayManager.showOverlay()
                renotify(context)
            }
            ACTION_STOP_OVERLAY -> {
                overlayManager.hideOverlay()
                renotify(context)
            }
            ACTION_OPEN_APP -> {
                openApp(context)
            }
            ACTION_EXIT_APP -> {
                exitApp(context)
            }
            else -> FrameXLog.w("Unknown overlay notification action: ${intent.action}")
        }
    }

    private fun renotify(context: Context) {
        val notification = OverlayNotificationBuilder.build(
            context = context,
            isOverlayVisible = overlayManager.isOverlayVisible.value
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(OverlayService.NOTIFICATION_ID, notification)
    }

    private fun openApp(context: Context) {
        val launchIntent = Intent(context, com.framex.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(launchIntent)
    }

    /**
     * Fully tears down FrameX: deactivates Gaming Mode first (restoring suspended apps and
     * DND) so we never leave the device in a degraded state, then stops the overlay service
     * and cancels the notification.
     *
     * Uses a receiver-scoped coroutine rather than blocking, since Gaming Mode teardown does
     * Shizuku IPC and must never run on the main thread (see GamingModeEngine.disableGamingMode).
     */
    private fun exitApp(context: Context) {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        receiverScope.launch {
            try {
                if (GamingModeEngine.isActive.value) {
                    gamingModeEngine.disableGamingMode()
                }
            } catch (e: Exception) {
                FrameXLog.e("Failed to deactivate Gaming Mode during Exit App", e)
            }

            overlayManager.hideOverlay()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(OverlayService.NOTIFICATION_ID)

            val stopIntent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP
            }
            context.startService(stopIntent)
        }
    }

    companion object {
        const val ACTION_START_OVERLAY = "com.framex.app.ACTION_NOTIF_START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.framex.app.ACTION_NOTIF_STOP_OVERLAY"
        const val ACTION_OPEN_APP = "com.framex.app.ACTION_NOTIF_OPEN_APP"
        const val ACTION_EXIT_APP = "com.framex.app.ACTION_NOTIF_EXIT_APP"

        private const val DEBOUNCE_WINDOW_MS = 500L
        @Volatile private var lastActionElapsedMs = 0L
    }
}
