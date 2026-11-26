package com.ax6.luyouqi

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference

/**
 * 后台超时自动退出（前台服务计时）。
 *
 * 计时口径：从 onPause（切到后台或息屏）开始算，默认退出 30 秒、手动退出 4 分钟。
 * 为什么用前台服务：进程进 cached 会被冻结，进程内定时器停摆，到点也无法执行；
 * 前台服务让进程保持非 cached 状态，计时才真正靠得住。代价是一条常驻通知。
 *
 * 关闭动作：停轮询 → finishAndRemoveTask() → 杀进程，确保内存真正释放。
 * 杀之前两道复核，避免用户恰好切回前台时被误杀。
 */
object ExitGate {

    const val PREFS = "ax6_exit"
    const val KEY_LEAVE_ELAPSED = "leave_elapsed"
    const val KEY_LEAVE_WALL = "leave_wall"
    const val KEY_LIMIT_MS = "limit_ms"

    private const val DEFAULT_LIMIT_MS = 30L * 1000
    private const val MANUAL_LIMIT_MS = 4L * 60 * 1000
    /** 刚创建的 Activity 有 2 秒豁免期，挡住恢复过程与计时器同拍触发的窗口。 */
    private const val ATTACH_GRACE_MS = 2_000L

    private var activityRef: WeakReference<MainActivity>? = null
    private var attachElapsed = 0L

    fun limitFor(manual: Boolean): Long = if (manual) MANUAL_LIMIT_MS else DEFAULT_LIMIT_MS

    fun attach(activity: MainActivity) {
        activityRef = WeakReference(activity)
        attachElapsed = SystemClock.elapsedRealtime()
    }

    /** 回到前台：撤掉计时服务与通知。 */
    fun onForeground(context: Context) {
        DiagLog.log("exit.fgs.cancel")
        runCatching { context.stopService(Intent(context, ExitService::class.java)) }
    }

    /** 进入后台：记时间戳并拉起前台服务开始计时。 */
    fun onBackground(context: Context, manual: Boolean) {
        val limit = limitFor(manual)
        prefs(context).edit()
            .putLong(KEY_LEAVE_ELAPSED, SystemClock.elapsedRealtime())
            .putLong(KEY_LEAVE_WALL, System.currentTimeMillis())
            .putLong(KEY_LIMIT_MS, limit)
            .apply()
        val intent = Intent(context, ExitService::class.java)
            .putExtra(ExitService.EXTRA_MANUAL, manual)
            .putExtra(ExitService.EXTRA_LIMIT_MS, limit)
        runCatching { context.startForegroundService(intent) }
            .onSuccess { DiagLog.log("exit.fgs.start limit=$limit") }
            .onFailure { DiagLog.log("exit.fgs.fail ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 用户点「确认退出」：立即关闭并释放进程。 */
    fun closeNow(activity: MainActivity) {
        DiagLog.log("exit.confirm")
        activityRef = WeakReference(activity)
        activity.stopForExit()
        activity.finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({ killSelf() }, 200L)
    }

    /** 前台服务到点调用：停轮询 → 移除任务 → 杀进程。 */
    fun closeFromBackground(context: Context) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            if (isForegroundNow()) {
                DiagLog.log("exit.abort foreground")
                runCatching { context.stopService(Intent(context, ExitService::class.java)) }
                return@post
            }
            DiagLog.log("exit.close")
            activityRef?.get()?.let { activity ->
                runCatching { activity.stopForExit() }
                runCatching { activity.finishAndRemoveTask() }
            }
            main.postDelayed({
                if (!isForegroundNow()) killSelf()
                else DiagLog.log("exit.abort at kill")
            }, 200L)
        }
    }

    private fun killSelf() {
        DiagLog.log("exit.kill")
        runCatching { Process.killProcess(Process.myPid()) }
    }

    /** Activity 真实可见（STARTED / RESUMED）才算前台；刚创建 2 秒内也按前台处理。 */
    private fun isForegroundNow(): Boolean {
        val activity = activityRef?.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (SystemClock.elapsedRealtime() - attachElapsed < ATTACH_GRACE_MS) return true
        return activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun limitOf(context: Context): Long =
        prefs(context).getLong(KEY_LIMIT_MS, DEFAULT_LIMIT_MS)

    /** 冻结期间 elapsedRealtime 照常走；若设备重启过（当前 elapsed 比记录小）则退回墙钟。 */
    fun elapsedSinceLeave(context: Context): Long {
        val p = prefs(context)
        val leaveElapsed = p.getLong(KEY_LEAVE_ELAPSED, 0L)
        val leaveWall = p.getLong(KEY_LEAVE_WALL, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        return when {
            leaveElapsed > 0L && nowElapsed >= leaveElapsed -> nowElapsed - leaveElapsed
            leaveWall > 0L -> System.currentTimeMillis() - leaveWall
            else -> 0L
        }
    }
}
