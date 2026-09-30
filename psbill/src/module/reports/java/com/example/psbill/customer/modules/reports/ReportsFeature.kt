package com.example.psbill.customer.modules.reports

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.runtime.Composable
import com.example.psbill.customer.PSBillTelemetryReportsView
import com.example.psbill.customer.PsbillTab
import com.example.psbill.customer.PsbillTabContext

object ReportsFeature : PsbillTab() {
    override val slug = "reports"
    override val key = "REPORTS"
    override val label = "Reports"
    override val icon = Icons.Filled.BarChart
    override val order = 40

    @Composable
    override fun Content(ctx: PsbillTabContext) = PSBillTelemetryReportsView(
        nodes = ctx.nodes,
        reportData = ctx.reportData,
        reportRange = ctx.reportRange,
        onRangeChange = ctx.onRangeChange,
        onRefresh = ctx.onRefreshReports,
    )
}
