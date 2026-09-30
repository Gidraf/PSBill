package com.example.psbill.core

import android.content.Context
import androidx.compose.runtime.Composable
import okhttp3.Headers

/**
 * Everything a feature module needs from the app shell. Modules never reach
 * into MainActivity directly, so any of them can be left out of a build.
 */
class ModuleContext(
    /** API host, e.g. "api.ajiriwa.gidraf.dev" (no scheme). */
    val server: String,
    val headers: () -> Headers,
    val partnerId: String,
    val userJson: String,
    val isAdmin: Boolean,
    val canSendSms: Boolean,
    val printerIp: String,
    val onPrinterIpChange: (String) -> Unit,
    /** Switch the drawer to another module by its nav key. */
    val navigateTo: (String) -> Unit,
) {
    val baseUrl: String get() = "https://$server"
}

/**
 * A selectable, compile-time module. Each module lives in
 * `src/module/<slug>/` and exposes `com.example.psbill.modules.<slug>.<Slug>Feature`.
 * Which modules are compiled is decided by `modules.properties` at the repo
 * root; the build generates [CompiledModules] listing only those.
 */
abstract class FeatureModule {
    /** Module slug as used in modules.properties (e.g. "sms", "properties"). */
    abstract val slug: String
    abstract val nav: NavModule

    @Composable
    abstract fun Content(ctx: ModuleContext)

    /** Composed for as long as a user is signed in (start services, ask permissions). */
    @Composable
    open fun SessionEffects(ctx: ModuleContext) {}

    /** Optional button in the top app bar. */
    @Composable
    open fun TopBarAction(ctx: ModuleContext) {}

    /** Called when the user signs out. */
    open fun onLogout(context: Context) {}
}

/** Nav keys served by compiled feature modules. */
val CompiledModules.featureKeys: Set<String>
    get() = features.mapTo(mutableSetOf()) { it.nav.key }

/** The compiled feature module that owns a nav key, if any. */
fun CompiledModules.feature(navKey: String): FeatureModule? = features.firstOrNull { it.nav.key == navKey }
