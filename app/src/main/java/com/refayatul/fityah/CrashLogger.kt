package com.refayatul.fityah

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.refayatul.fityah.ui.activity.CrashLogActivity
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashLogger(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val logFile = File(context.filesDir, "crash_log.txt")
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e("Crash", throwable.printStackTrace().toString())
            val timeStamp =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val writer = FileWriter(logFile, true) // Append mode
            writer.append("\n--- Crash at $timeStamp ---\n")
            writer.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
            val printWriter = PrintWriter(writer)
            throwable.printStackTrace(printWriter)
            printWriter.flush()
            printWriter.close()

            val intent = Intent(context, CrashLogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Kill the current process to prevent the system's "app has crashed" dialog
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(10)
    }
    fun logNonFatalError(exception: Exception) {
        try {
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val writer = FileWriter(logFile, true) // Append mode
            writer.append("\n--- Non-Fatal Error (Caught) at $timeStamp ---\n")
            val printWriter = PrintWriter(writer)
            exception.printStackTrace(printWriter)
            printWriter.flush()
            printWriter.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
