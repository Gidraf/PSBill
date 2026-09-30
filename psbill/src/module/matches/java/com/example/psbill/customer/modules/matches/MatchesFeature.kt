package com.example.psbill.customer.modules.matches

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import com.example.psbill.customer.PSBillMatchesView
import com.example.psbill.customer.PsbillTab
import com.example.psbill.customer.PsbillTabContext

object MatchesFeature : PsbillTab() {
    override val slug = "matches"
    override val key = "MATCHES"
    override val label = "Matches"
    override val icon = Icons.Filled.SportsEsports
    override val order = 10

    @Composable
    override fun Content(ctx: PsbillTabContext) = PSBillMatchesView(ctx.nodes, ctx.games)
}
