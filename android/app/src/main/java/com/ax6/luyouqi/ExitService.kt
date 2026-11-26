package com.ax6.luyouqi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台退出计时的前台服务。
 *
 * 只用一条低优先级通知，作用有两个：让进程保持非 cached 状态（不被冻结，计时才准），
 * 以及在 Android 12+ 上满足「后台起前台服务」的合规要求。
 * 回到前台时由 ExitGate.onForeground 直接 stopService 撤掉。
 */
class ExitService : Service() {

    companion object {
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_LIMIT_MS = "limit_ms"
        private const val CHANNEL_ID = "ax6_exit"
        private const val NOTIF_ID = 1001
        private const val CHECK_MS = 1_000L
    }

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val limit = intent?.getLongExtra(EXTRA_LIMIT_MS, ExitGate.limitOf(this))
            ?: ExitGate.limitOf(this)
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        startForeground(NOTIF_ID, buildNotification(limit, manual))
        holdCpu(limit)
        job?.cancel()
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (isActive) {
                delay(CHECK_MS)
                if (ExitGate.elapsedSinceLeave(this@ExitService) >= ExitGate.limitOf(this@ExitService)) {
                    DiagLog.log("exit.fgs.reached")
                    ExitGate.closeFromBackground(this@ExitService)
                    return@launch
                }
            }
        }
        DiagLog.log("exit.fgs.armed limit=$limit")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        releaseCpu()
        DiagLog.log("exit.fgs.stop")
        super.onDestroy()
    }

    /** 只在这几分钟计时窗口内持有 partial wakelock，避免息屏后 CPU 休眠导致计时漂移。 */
    private fun holdCpu(limit: Long) {
        val pm = getSystemService(PowerManager::class.java) ?: return
        runCatching {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ax6:exit-timer").apply {
                setReferenceCounted(false)
                acquire(limit + 15_000L)
            }
        }
    }

    private fun releaseCpu() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台退出计时", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }

    private fun buildNotification(limit: Long, manual: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val seconds = (limit / 1000).coerceAtLeast(1)
        val text = if (manual) "手动退出：${seconds} 秒后自动关闭，返回应用可取消"
        else "默认退出：${seconds} 秒后自动关闭，返回应用可取消"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("AX6助手")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}
