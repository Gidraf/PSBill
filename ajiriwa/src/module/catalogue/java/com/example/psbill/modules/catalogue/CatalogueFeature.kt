package com.example.psbill.modules.catalogue

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.Composable
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import com.example.psbill.ui.screens.CatalogueScreen

object CatalogueFeature : FeatureModule() {
    override val slug = "catalogue"
    override val nav = NavModule("catalogue", "Catalogue", Icons.Filled.List, order = 80)

    @Composable
    override fun Content(ctx: ModuleContext) = CatalogueScreen(ctx.server, ctx.headers)
}
