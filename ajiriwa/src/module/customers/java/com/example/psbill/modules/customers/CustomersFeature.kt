package com.example.psbill.modules.customers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import com.example.psbill.ui.screens.CustomersScreen

object CustomersFeature : FeatureModule() {
    override val slug = "customers"
    override val nav = NavModule("customers", "Customers", Icons.Filled.Person, listOf("customers"), order = 70)

    @Composable
    override fun Content(ctx: ModuleContext) = CustomersScreen(ctx.server, ctx.headers)
}
