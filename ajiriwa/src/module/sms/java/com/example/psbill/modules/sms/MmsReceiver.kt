package com.example.psbill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required for Default SMS App status.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MMS support not implemented yet
    }
}
