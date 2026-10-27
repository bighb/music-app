package com.personal.musicapp.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: String,
    val level: String, // "D", "I", "W", "E"
    val tag: String,
    val message: String,
    val errorDetail: String? = null
)

object AppLogger {
    private const val MAX_LOGS = 120
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val logList = ArrayList<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val _hasErrorFlow = MutableStateFlow(false)
    val hasErrorFlow: StateFlow<Boolean> = _hasErrorFlow.asStateFlow()

    @Synchronized
    private fun addEntry(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val errorDetail = throwable?.let { t ->
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sw.toString()
        }

        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message,
            errorDetail = errorDetail
        )

        if (level == "E" || level == "W") {
            _hasErrorFlow.value = true
        }

        logList.add(0, entry)
        if (logList.size > MAX_LOGS) {
            logList.removeAt(logList.lastIndex)
        }
        _logsFlow.value = ArrayList(logList)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addEntry("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addEntry("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        addEntry("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        addEntry("E", tag, message, throwable)
    }

    @Synchronized
    fun clearLogs() {
        logList.clear()
        _logsFlow.value = emptyList()
        _hasErrorFlow.value = false
    }

    fun markErrorRead() {
        _hasErrorFlow.value = false
    }

    fun copyToClipboard(context: Context): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val content = generateMarkdownLog(context)
            val clip = ClipData.newPlainText("MusicApp_Debug_Log", content)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun generateMarkdownLog(context: Context? = null): String {
        val sb = StringBuilder()
        sb.append("### MusicApp Debug Logs (真机诊断信息)\n")
        if (context != null) {
            try {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val verName = pkgInfo.versionName
                val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
                sb.append("- App 版本: v$verName (Build $verCode)\n")
            } catch (_: Exception) {}
        }
        sb.append("- 设备型号: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
        sb.append("- 导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("- 日志条数: ${logList.size}\n\n")
        sb.append("```text\n")

        val currentList = ArrayList(logList).reversed()
        for (item in currentList) {
            sb.append("[${item.timestamp}] [${item.level}] [${item.tag}] ${item.message}\n")
            if (!item.errorDetail.isNullOrBlank()) {
                sb.append("  ↳ 堆栈: ${item.errorDetail.trim()}\n")
            }
        }
        sb.append("```\n")
        return sb.toString()
    }
}
