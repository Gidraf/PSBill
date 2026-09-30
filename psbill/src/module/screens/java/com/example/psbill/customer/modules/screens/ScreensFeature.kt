package com.example.psbill.customer.modules.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import com.example.psbill.customer.PSBillScreensConfigView
import com.example.psbill.customer.PsbillTab
import com.example.psbill.customer.PsbillTabContext

object ScreensFeature : PsbillTab() {
    override val slug = "screens"
    override val key = "SCREENS"
    override val label = "Screens"
    override val icon = Icons.Filled.Tv
    override val order = 30

    @Composable
    override fun Content(ctx: PsbillTabContext) = PSBillScreensConfigView(
        nodes = ctx.nodes,
        games = ctx.games,
        isLoading = ctx.isLoading,
        serverHost = ctx.serverHost,
        token = ctx.token,
        client = ctx.client,
        onRefresh = ctx.onRefresh,
        onSelectScreen = ctx.onSelectScreen,
        onTokenExpired = ctx.onTokenExpired,
    )
}
