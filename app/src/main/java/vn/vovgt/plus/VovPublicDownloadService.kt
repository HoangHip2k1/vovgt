package vn.vovgt.plus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground download service used only by the public VOV archive tab.
 * It is independent from VOV Media downloads and authentication.
 */
class VovPublicDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: kotlinx.coroutines.Job? = null
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (downloadJob?.isActive == true) {
            updateNotification("Đang có một lượt tải VOV khác chạy.", ongoing = true)
            return START_NOT_STICKY
        }

        val mediaUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Chương trình VOV" }
        val pageUrl = intent.getStringExtra(EXTRA_PAGE).orEmpty().ifBlank {
            "https://vov.gov.vn/nghe-va-xem-lai"
        }
        if (mediaUrl.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startAsForeground("Đang chuẩn bị tải: $title")
        downloadJob = serviceScope.launch {
            try {
                val result = VovPublicMediaDownloader.download(
                    context = applicationContext,
                    mediaUrl = mediaUrl,
                    title = title,
                    pageUrl = pageUrl,
                    onProgress = { message ->
                        updateNotification(message, ongoing = true)
                    }
                )
                updateNotification(
                    "Đã tải xong: ${result.displayName} • ${result.location}",
                    ongoing = false,
                    completed = true
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateNotification(
                    "Tải VOV thất bại: ${error.message ?: "Lỗi không xác định"}",
                    ongoing = false,
                    failed = true
                )
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(message: String) {
        val notification = buildNotification(message, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        message: String,
        ongoing: Boolean,
        completed: Boolean = false,
        failed: Boolean = false
    ) {
        val notification = buildNotification(message, ongoing, completed, failed)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        message: String,
        ongoing: Boolean,
        completed: Boolean = false,
        failed: Boolean = false
    ): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            completed -> "VOV • Tải hoàn tất"
            failed -> "VOV • Tải thất bại"
            else -> "VOV • Đang tải nội dung"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(title)
            .setContentText(message.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setProgress(0, 0, ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tải nội dung VOV",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị tiến độ tải nội dung công khai từ VOV"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "vn.vovgt.plus.action.START_PUBLIC_VOV_DOWNLOAD"
        const val EXTRA_URL = "public_vov_url"
        const val EXTRA_TITLE = "public_vov_title"
        const val EXTRA_PAGE = "public_vov_page"
        private const val CHANNEL_ID = "vov_public_downloads"
        private const val NOTIFICATION_ID = 7412
    }
}
