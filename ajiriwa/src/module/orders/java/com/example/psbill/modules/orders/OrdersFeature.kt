package com.example.psbill.modules.orders

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import com.example.psbill.ui.screens.OrdersScreen

object OrdersFeature : FeatureModule() {
    override val slug = "orders"
    override val nav = NavModule("orders", "Orders", Icons.Filled.ShoppingCart, listOf("orders"), order = 10)

    @Composable
    override fun Content(ctx: ModuleContext) = OrdersScreen(ctx.server, ctx.headers)

    /** Delivery tracking needs location; ask once after sign-in. */
    @Composable
    override fun SessionEffects(ctx: ModuleContext) {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        LaunchedEffect(ctx.partnerId) {
            val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
        }
    }
}
