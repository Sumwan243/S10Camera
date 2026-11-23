package com.samsung.android.scan3d

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class S10CameraApp : Application() {

    companion object {
        private const val TAG = "S10CameraApp"
        var lastCrashLog: String? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashHandler()
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            throwable.printStackTrace(printWriter)
            val stackTrace = stringWriter.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logContent = """
                ═══════════════════════════════════════
                S10 Pro Camera Crash Report
                Timestamp: $timestamp
                Thread: ${thread.name}
                ═══════════════════════════════════════
                Error: ${throwable.javaClass.name}: ${throwable.message}

                Stack Trace:
                $stackTrace
            """.trimIndent()

            lastCrashLog = logContent
            Log.e(TAG, logContent)

            // 1. Save to internal app files
            try {
                val internalFile = File(filesDir, "latest_crash.txt")
                internalFile.writeText(logContent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save internal crash log", e)
            }

            // 2. Save to public Download folder so the user can easily open it on the phone
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadDir != null && downloadDir.exists()) {
                    val publicFile = File(downloadDir, "s10_camera_crash.txt")
                    publicFile.writeText(logContent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save public crash log", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
