package com.example.psbill.modules.printing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.Composable
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import com.example.psbill.ui.screens.PrintingScreen

object PrintingFeature : FeatureModule() {
    override val slug = "printing"
    override val nav = NavModule("printing", "Printing", Icons.Filled.Build, order = 50)

    @Composable
    override fun Content(ctx: ModuleContext) = PrintingScreen(
        server = ctx.server,
        printerIp = ctx.printerIp,
        onPrinterIpChange = ctx.onPrinterIpChange,
    )
}
