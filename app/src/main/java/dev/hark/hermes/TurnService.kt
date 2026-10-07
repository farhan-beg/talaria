package dev.hark.hermes

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.hark.hermes.data.ChatItem
import dev.hark.hermes.data.fmtDur
import dev.hark.hermes.data.liveActivity
import dev.hark.hermes.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample

/**
 * Keeps a running turn alive and visible while you're in another app: one ongoing notification that
 * says what Hermes is doing right now, how long it's been, and a Stop button.
 */
class TurnService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch(Dispatchers.IO) { app.gateway.interrupt() }
            return START_NOT_STICKY
        }
        ensureChannels(this)
        val n = build("Working…", "", 0)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(LIVE_ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (watching == null) watching = scope.launch {
            val g = app.gateway
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            combine(g.items, g.status, g.busy, g.title) { items, status, busy, title -> Snap(items, status, busy, title) }
                .sample(800)
                .collect { s ->
                    if (!s.busy) { finish(); return@collect }
                    val start = g.turnStart.takeIf { it > 0 } ?: System.currentTimeMillis()
                    val steps = s.items.takeLastWhile { it !is ChatItem.User }.count { it is ChatItem.Tool }
                    val sub = buildString {
                        append(fmtDur(System.currentTimeMillis() - start))
                        if (steps > 0) append(" · step ").append(steps)
                    }
                    NotificationManagerCompat.from(this@TurnService).runCatching { notify(LIVE_ID, build(liveActivity(s.items, s.status), "${s.title} · $sub", steps)) }
                }
        }
        return START_NOT_STICKY
    }

    private data class Snap(val items: List<ChatItem>, val status: String, val busy: Boolean, val title: String)

    private fun finish() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun build(text: String, sub: String, steps: Int): Notification =
        NotificationCompat.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(text)
            .setContentText(sub)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .setContentIntent(openApp(this))
            .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, TurnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CH_LIVE = "live_turn"
        const val CH_DONE = "replies"
        const val LIVE_ID = 41
        const val DONE_ID = 42
        const val ACTION_STOP = "dev.hark.hermes.STOP_TURN"

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Live progress", NotificationManager.IMPORTANCE_LOW).apply { description = "What Hermes is doing while a reply is in progress"; setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Replies", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When a reply is ready" })
        }

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, TurnService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        /** "Reply ready" — only when you're not already looking at the app. */
        fun replyReady(ctx: Context, title: String, status: String, text: String) {
            ensureChannels(ctx)
            val (head, body) = when (status) {
                "error" -> "Hermes hit a problem" to "Open the chat to see what went wrong."
                "interrupted" -> return
                else -> "Reply ready · $title" to text.replace(Regex("[#*`>_]+"), "").trim().take(400).ifBlank { "Done." }
            }
            val n = NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(head)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(DONE_ID, n) }
        }
    }
}
