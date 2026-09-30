package com.example.psbill

import android.app.Application
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter

class PSBillApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Capture stack trace
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                // Persist crash info so CrashRecoveryActivity can display it
                val prefs = getSharedPreferences("CrashPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putString("crash_message", throwable.message ?: throwable.javaClass.simpleName)
                    .putString("crash_trace", stackTrace.take(4000))
                    .putLong("crash_time", System.currentTimeMillis())
                    .apply()

                // Launch recovery screen (no back-stack, no animations)
                val intent = Intent(applicationContext, CrashRecoveryActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                startActivity(intent)
            } catch (_: Exception) {
                // If our recovery itself fails, hand off to the system
                defaultHandler?.uncaughtException(thread, throwable)
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
