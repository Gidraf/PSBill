package com.example.psbill.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.psbill.ui.components.EmptyState

/**
 * Placeholder for web modules being ported to the app. Each becomes its own real
 * screen file under ui/screens/ as it's built — this keeps the drawer complete
 * (every allowed module is navigable) without a half-built screen blocking the app.
 */
@Composable
fun ComingSoonScreen(moduleTitle: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Filled.Build,
            title = "$moduleTitle is on the way",
            message = "This module is available on the web and is being brought to the app. " +
                "It will appear here automatically once ready.",
        )
    }
}
