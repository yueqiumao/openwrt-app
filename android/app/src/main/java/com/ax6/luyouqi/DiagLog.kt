package com.ax6.luyouqi

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量诊断日志：只为定位「打开即消失」这类问题，不参与业务。
 *
 * - trace.log：滚动记录关键生命周期与退出计时事件（最多保留 32KB），
 *   进程被系统 SIGKILL 时日志会戛然而止，正好能看出死在哪一步；
 * - crash.log：未捕获异常栈，每次崩溃覆盖写入，下次启动展示。
 */
object DiagLog {

    private const val TRACE = "trace.log"
    private const val CRASH = "crash.log"
    private const val MAX_TRACE_CHARS = 32 * 1024
    private const val REPORT_TRACE_LINES = 40

    @Volatile private var appContext: Context? = null

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext
        installCrashHandler()
        log("app.start pid=${Process.myPid()} elapsed=${SystemClock.elapsedRealtime()}")
    }

    fun log(message: String) {
        val ctx = appContext ?: return
        runCatching {
            synchronized(this) {
                val file = File(ctx.filesDir, TRACE)
                val line = "${stamp.format(Date())}  $message\n"
                file.appendText(line)
                if (file.length() > MAX_TRACE_CHARS) {
                    val text = file.readText()
                    file.writeText(text.takeLast(MAX_TRACE_CHARS / 2))
                }
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(thread: Thread, error: Throwable) {
        val ctx = appContext ?: return
        synchronized(this) {
            val file = File(ctx.filesDir, CRASH)
            val head = "${stamp.format(Date())}  thread=${thread.name}  pid=${Process.myPid()}\n"
            file.writeText(head + android.util.Log.getStackTraceString(error))
            log("crash captured: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** 设置页展示用：崩溃栈（若有）+ 最近事件时间线。 */
    fun report(context: Context): String {
        val crash = File(context.filesDir, CRASH)
        val trace = File(context.filesDir, TRACE)
        val crashText = if (crash.exists()) crash.readText().trim() else ""
        val traceText = if (trace.exists()) {
            trace.readText().trim().lines().takeLast(REPORT_TRACE_LINES).joinToString("\n")
        } else ""
        val parts = buildList {
            if (crashText.isNotEmpty()) add("【最近一次崩溃】\n$crashText")
            if (traceText.isNotEmpty()) add("【最近事件】\n$traceText")
        }
        return parts.joinToString("\n\n").takeLast(6000)
    }

}
