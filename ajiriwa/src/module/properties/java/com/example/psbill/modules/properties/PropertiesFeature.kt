package com.example.psbill.modules.properties

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.*
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import org.json.JSONObject

/**
 * Properties & houses: listings with full Kenyan-market specs, photos (camera or
 * gallery), maker–checker approval, map + turn-by-turn directions from the shop,
 * sharing by SMS/email and a full-screen shop-screen mode.
 */
object PropertiesFeature : FeatureModule() {
    override val slug = "properties"
    override val nav = NavModule("properties", "Properties & Houses", Icons.Filled.Home, listOf("properties"), order = 20)

    @Composable
    override fun Content(ctx: ModuleContext) {
        val api = remember(ctx.server, ctx.partnerId) { PropertiesApi(ctx) }
        var context by remember { mutableStateOf<JSONObject?>(null) }
        var schema by remember { mutableStateOf<JSONObject?>(null) }
        var screen by remember { mutableStateOf<Screen>(Screen.List) }
        var refreshKey by remember { mutableIntStateOf(0) }

        LaunchedEffect(api) {
            runCatching { schema = api.schema() }
            runCatching { context = api.me() }
        }
        BackHandler(enabled = screen != Screen.List) {
            screen = when (val s = screen) {
                is Screen.Form -> if (s.listing != null) Screen.Detail(s.listing.getString("id")) else Screen.List
                else -> Screen.List
            }
        }

        when (val s = screen) {
            Screen.List -> PropertyListScreen(
                api = api, context = context, refreshKey = refreshKey,
                onOpen = { screen = Screen.Detail(it) },
                onAdd = { screen = Screen.Form(null) },
                onShopScreen = { screen = Screen.Display },
            )
            is Screen.Detail -> PropertyDetailScreen(
                api = api, id = s.id, context = context,
                onBack = { refreshKey++; screen = Screen.List },
                onEdit = { screen = Screen.Form(it) },
            )
            is Screen.Form -> PropertyFormScreen(
                api = api, schema = schema, context = context, listing = s.listing,
                onCancel = { screen = if (s.listing != null) Screen.Detail(s.listing.getString("id")) else Screen.List },
                onSaved = { refreshKey++; screen = Screen.Detail(it) },
            )
            Screen.Display -> ShopScreenMode(api = api, business = context?.optString("business_name").orEmpty(), onExit = { screen = Screen.List })
        }
    }

    private sealed interface Screen {
        data object List : Screen
        data class Detail(val id: String) : Screen
        data class Form(val listing: JSONObject?) : Screen
        data object Display : Screen
    }
}
