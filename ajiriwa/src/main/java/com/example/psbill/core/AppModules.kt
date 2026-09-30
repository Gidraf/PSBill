package com.example.psbill.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Ajiriwa Client — Navigation Registry
 *
 * The drawer is built from the modules compiled into this APK (see
 * modules.properties / [CompiledModules]) and then filtered by the partner's
 * enabled modules from the API, exactly like the web sidebar.
 */
data class NavModule(
    val key: String,
    val title: String,
    val icon: ImageVector,
    /** server module slugs that gate this item; empty = always visible. */
    val modules: List<String> = emptyList(),
    /** true for the arcade/playgate sub-feature */
    val isArcade: Boolean = false,
    /** drawer position (lower first) */
    val order: Int = 100,
)

object AppModules {

    /** Modules whose UI still lives inside MainActivity: nav entry + compile flag. */
    private val builtInNav: List<Pair<String, NavModule>> = listOf(
        "wifi" to NavModule("wifi", "WiFi Billing", Icons.Filled.Refresh, listOf("wifi", "wifi_kiosk"), order = 40),
        "arcade" to NavModule("kiosk", "Playgate Control", Icons.Filled.Star, listOf("kiosk", "gaming_kiosk"), isArcade = true, order = 60),
    )

    /** Every nav item compiled into this build, in drawer order. */
    val partnerNav: List<NavModule>
        get() = (CompiledModules.features.map { it.nav } +
            builtInNav.filter { CompiledModules.has(it.first) }.map { it.second })
            .sortedBy { it.order }

    /** Super-admin drawer: everything compiled, no partner gating. */
    val adminNav: List<NavModule>
        get() = partnerNav.map { it.copy(modules = emptyList()) }

    /**
     * Filter to modules visible for this partner/role combo.
     */
    fun partnerVisible(allowed: Set<String>, canSendSms: Boolean): List<NavModule> {
        return partnerNav.filter { nav ->
            nav.modules.isEmpty() || nav.modules.any { m -> allowed.contains(m) }
        }
    }

    /** True when the Gaming Arcade (Playgate) module is compiled in AND enabled for this partner. */
    fun arcadeEnabled(allowed: Set<String>): Boolean =
        partnerNav.firstOrNull { it.key == "kiosk" }?.modules?.any { allowed.contains(it) } == true

    fun iconForTitle(title: String): ImageVector = when {
        title.contains("Screen", true) || title.contains("Arcade", true) || title.contains("Playgate", true) -> Icons.Filled.Star
        title.contains("Activity", true) -> Icons.Filled.Info
        title.contains("SMS", true) -> Icons.Filled.Email
        title.contains("Catalogue", true) || title.contains("Inventory", true) -> Icons.Filled.List
        title.contains("Order", true) -> Icons.Filled.ShoppingCart
        title.contains("Customer", true) || title.contains("User", true) -> Icons.Filled.Person
        title.contains("WiFi", true) -> Icons.Filled.Refresh
        title.contains("Print", true) -> Icons.Filled.Build
        title.contains("Dashboard", true) -> Icons.Filled.Home
        title.contains("Voucher", true) -> Icons.Filled.DateRange
        title.contains("Marketing", true) -> Icons.Filled.Send
        title.contains("WhatsApp", true) -> Icons.Filled.Phone
        title.contains("Integration", true) -> Icons.Filled.Build
        else -> Icons.Filled.Home
    }
}
