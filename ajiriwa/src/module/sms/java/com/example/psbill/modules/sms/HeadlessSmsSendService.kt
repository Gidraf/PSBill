package com.example.psbill

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required for Default SMS App status.
 * Handles android.intent.action.RESPOND_VIA_MESSAGE
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
