package com.example.psbill.modules.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import com.example.psbill.ui.screens.DashboardScreen

object DashboardFeature : FeatureModule() {
    override val slug = "dashboard"
    override val nav = NavModule("overview", "Dashboard", Icons.Filled.Home, order = 0)

    @Composable
    override fun Content(ctx: ModuleContext) = DashboardScreen(ctx.server, ctx.headers)
}
