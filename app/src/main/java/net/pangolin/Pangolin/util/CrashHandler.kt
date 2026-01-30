package net.pangolin.Pangolin.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom uncaught exception handler that logs crashes to the same log file used by the Go backend.
 * This ensures that app crashes are captured and can be included in log exports for debugging.
 */
class CrashHandler private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val LOG_FILE_NAME = "pangolin_go.log"
        
        @Volatile
        private var instance: CrashHandler? = null
        
        /**
         * Initialize the crash handler. This should be called once in the Application's onCreate.
         */
        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
                        instance = CrashHandler(context.applicationContext, defaultHandler)
                        Thread.setDefaultUncaughtExceptionHandler(instance)
                        Log.d(TAG, "CrashHandler initialized")
                    }
                }
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            logCrashToFile(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash to file", e)
        } finally {
            // Call the default handler to let the system handle the crash normally
            // (this will show the crash dialog and terminate the app)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logCrashToFile(thread: Thread, throwable: Throwable) {
        val logFile = File(context.filesDir, LOG_FILE_NAME)
        
        try {
            // Append crash information to the log file
            logFile.appendText(buildCrashLog(thread, throwable))
            Log.d(TAG, "Crash logged to file: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing crash log", e)
        }
    }

    private fun buildCrashLog(thread: Thread, throwable: Throwable): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val separator = "=" * 80
        
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine(separator)
        sb.appendLine("FATAL EXCEPTION - APP CRASH")
        sb.appendLine(separator)
        sb.appendLine("Timestamp: $timestamp")
        sb.appendLine("Thread: ${thread.name} (ID: ${thread.id})")
        sb.appendLine()
        
        // Device and app information
        sb.appendLine("Device Information:")
        sb.appendLine("  Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("  Model: ${Build.MODEL}")
        sb.appendLine("  Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("  Brand: ${Build.BRAND}")
        sb.appendLine("  Device: ${Build.DEVICE}")
        sb.appendLine()
        
        // App information
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("App Information:")
            sb.appendLine("  Package: ${context.packageName}")
            sb.appendLine("  Version: ${packageInfo.versionName} (${packageInfo.versionCode})")
            sb.appendLine()
        } catch (e: Exception) {
            Log.w(TAG, "Could not get package info", e)
        }
        
        // Exception information
        sb.appendLine("Exception: ${throwable.javaClass.name}")
        sb.appendLine("Message: ${throwable.message ?: "(no message)"}")
        sb.appendLine()
        
        // Stack trace
        sb.appendLine("Stack Trace:")
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        sb.appendLine(stringWriter.toString())
        
        // Caused by chain
        var cause = throwable.cause
        while (cause != null) {
            sb.appendLine()
            sb.appendLine("Caused by: ${cause.javaClass.name}")
            sb.appendLine("Message: ${cause.message ?: "(no message)"}")
            val causeStringWriter = StringWriter()
            val causePrintWriter = PrintWriter(causeStringWriter)
            cause.printStackTrace(causePrintWriter)
            sb.appendLine(causeStringWriter.toString())
            cause = cause.cause
        }
        
        sb.appendLine(separator)
        sb.appendLine()
        
        return sb.toString()
    }

    private operator fun String.times(count: Int): String = this.repeat(count)
}