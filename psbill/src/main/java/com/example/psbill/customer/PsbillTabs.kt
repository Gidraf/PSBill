package com.example.psbill.customer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Shared state handed to every bottom-bar tab. */
class PsbillTabContext(
    val nodes: List<JSONObject>,
    val games: List<JSONObject>,
    val isLoading: Boolean,
    val vouchers: List<JSONObject>,
    val reportData: JSONObject?,
    val reportRange: String,
    val onRangeChange: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onRefreshReports: () -> Unit,
    val serverHost: String,
    val token: String,
    val client: OkHttpClient,
    val onSelectScreen: (JSONObject) -> Unit,
    val onTokenExpired: () -> Unit,
)

/**
 * A selectable, compile-time tab (src/module/<slug>/). Sessions is the core
 * and always present; the rest are listed in ../../modules.properties.
 */
abstract class PsbillTab {
    abstract val slug: String
    /** Key used by the bottom bar / selectedTab. */
    abstract val key: String
    abstract val label: String
    abstract val icon: ImageVector
    abstract val order: Int

    @Composable
    abstract fun Content(ctx: PsbillTabContext)
}
