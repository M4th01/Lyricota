package com.example.lrcfetcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notificaciones de los lotes: una en curso con el porcentaje (servicio en primer plano, para
 * que Android no cierre la app mientras trabaja en segundo plano) y otra al terminar.
 */
class BatchNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_PROGRESS = "batch_progress"
        const val CHANNEL_DONE = "batch_done"
        const val ID_PROGRESS = 1001
        const val ID_DONE = 1002

        /** Lo pone el ViewModel: el botón "Cancelar" de la notificación lo llama. */
        @Volatile var onCancel: (() -> Unit)? = null

        /** Última notificación de progreso, para que el servicio arranque con ella. */
        @Volatile internal var latest: Notification? = null

        fun canPost(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private val manager = NotificationManagerCompat.from(context)
    private var lastShown: Pair<Int, String>? = null

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, context.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** Se llama con cada cambio del estado del lote. */
    fun onBatch(old: BatchState, new: BatchState) {
        when {
            new.running && !old.running -> {
                manager.cancel(ID_DONE)
                lastShown = null
                latest = progress(new)
                runCatching { ContextCompat.startForegroundService(context, Intent(context, BatchService::class.java)) }
                    .onFailure { show(ID_PROGRESS, latest!!) }
            }
            new.running -> {
                val key = new.done to new.current
                if (key == lastShown) return
                lastShown = key
                latest = progress(new)
                show(ID_PROGRESS, latest!!)
            }
            old.running -> finished(new)
        }
    }

    /** El lote terminó (o se canceló / se cerró la app a mitad). */
    fun finished(state: BatchState) {
        latest = null
        context.stopService(Intent(context, BatchService::class.java))
        manager.cancel(ID_PROGRESS)
        show(ID_DONE, done(state))
    }

    private fun kindTitle(kind: BatchKind) = context.getString(
        if (kind == BatchKind.LYRICS) R.string.notif_lyrics_title else R.string.notif_meta_title,
    )

    private fun percent(s: BatchState) = if (s.total == 0) 0 else s.done * 100 / s.total

    private fun progress(s: BatchState): Notification {
        val text = context.getString(R.string.notif_progress, percent(s), s.done, s.total)
        val cancel = PendingIntent.getService(
            context, 1, Intent(context, BatchService::class.java).setAction(BatchService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_lyricota)
            .setContentTitle(kindTitle(s.kind))
            .setContentText(if (s.current.isBlank()) text else "$text · ${s.current}")
            .setSubText("${percent(s)}%")
            .setProgress(s.total.coerceAtLeast(1), s.done, s.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.cancel), cancel)
            .build()
    }

    private fun done(s: BatchState): Notification {
        val complete = s.done >= s.total
        val summary = when (s.kind) {
            BatchKind.LYRICS ->
                if (s.failed > 0) context.getString(R.string.batch_summary_errors, s.found, s.notFound, s.failed)
                else context.getString(R.string.batch_summary, s.found, s.notFound)
            BatchKind.METADATA -> context.getString(R.string.batch_meta_summary, s.found, s.review)
        }
        val title = context.getString(
            if (complete) R.string.notif_done_title else R.string.notif_cancelled_title,
            kindTitle(s.kind), percent(s),
        )
        return NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_lyricota)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp())
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("MissingPermission")
    private fun show(id: Int, n: Notification) {
        if (canPost(context)) runCatching { manager.notify(id, n) }
    }
}

/** Mantiene vivo el proceso mientras corre un lote; la notificación la actualiza [BatchNotifier]. */
class BatchService : Service() {
    companion object {
        const val ACTION_CANCEL = "com.example.lrcfetcher.CANCEL_BATCH"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BatchNotifier.onCancel?.invoke()
            return START_NOT_STICKY
        }
        val n = BatchNotifier.latest
        if (n == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(BatchNotifier.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(BatchNotifier.ID_PROGRESS, n)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }
}
