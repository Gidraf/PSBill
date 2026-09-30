package com.example.psbill

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Required for Default SMS App status.
 * Handles android.intent.action.SENDTO with smsto:
 */
class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Redirect to main activity or just finish
        finish()
    }
}
