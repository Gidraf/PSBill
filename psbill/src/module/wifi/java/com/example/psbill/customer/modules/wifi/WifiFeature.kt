package com.example.psbill.customer.modules.wifi

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import com.example.psbill.customer.PSBillWifiView
import com.example.psbill.customer.PsbillTab
import com.example.psbill.customer.PsbillTabContext

object WifiFeature : PsbillTab() {
    override val slug = "wifi"
    override val key = "WIFI"
    override val label = "Wi-Fi"
    override val icon = Icons.Filled.Wifi
    override val order = 20

    @Composable
    override fun Content(ctx: PsbillTabContext) = PSBillWifiView(ctx.vouchers, onRefresh = ctx.onRefresh)
}
