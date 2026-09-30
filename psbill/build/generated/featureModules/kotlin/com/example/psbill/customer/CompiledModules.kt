// GENERATED from modules.properties by gradle/feature-modules.gradle.kts — do not edit.
package com.example.psbill.customer

object CompiledModules {
    /** Every module compiled into this build. */
    val keys: Set<String> = setOf("matches", "wifi", "screens", "reports")

    /** Feature modules (in src/module/<slug>) compiled into this build. */
    val features: List<PsbillTab> = listOf(
        com.example.psbill.customer.modules.matches.MatchesFeature,
        com.example.psbill.customer.modules.wifi.WifiFeature,
        com.example.psbill.customer.modules.screens.ScreensFeature,
        com.example.psbill.customer.modules.reports.ReportsFeature,
    )

    fun has(module: String): Boolean = module in keys
}
