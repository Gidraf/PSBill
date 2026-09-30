package com.example.psbill.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure role / permission / module helpers — extracted from MainActivity so the
 * whole app can reason about "what can this user see?" in one place, and so it
 * mirrors the web app's gating exactly:
 *   - account_type: admin | partner (| agent for arcade attendants)
 *   - allowed modules come from GET /api/v1/partners/<id>/modules
 *   - a nav item shows if its module is allowed (unmapped items always show)
 *
 * No Android/Compose imports here on purpose — this is plain, unit-testable Kotlin.
 */
object Permissions {

    private fun userObj(userJson: String?): JSONObject? =
        if (userJson.isNullOrBlank()) null else runCatching { JSONObject(userJson) }.getOrNull()

    fun accountType(userJson: String?): String =
        userObj(userJson)?.optString("account_type", "partner")?.ifBlank { "partner" } ?: "partner"

    fun isAdmin(userJson: String?): Boolean {
        val user = userObj(userJson) ?: return false
        val single = user.optString("role", "")
        if (single.equals("owner", true) || single.equals("admin", true) || single.equals("super_admin", true)) return true
        if (user.optBoolean("is_super_admin", false)) return true
        if (accountType(userJson).equals("admin", true)) return true
        val roles = user.optJSONArray("roles") ?: return false
        for (i in 0 until roles.length()) {
            val name = roles.optJSONObject(i)?.optString("name") ?: continue
            if (name.equals("Admin", true) || name.equals("Owner", true)) return true
        }
        return false
    }

    /** An arcade attendant: sees ONLY the arcade experience, not the shop modules. */
    fun isArcadeAgent(userJson: String?): Boolean {
        if (isAdmin(userJson)) return false
        val user = userObj(userJson) ?: return false
        if (accountType(userJson).equals("agent", true)) return true
        val roles = user.optJSONArray("roles") ?: JSONArray()
        for (i in 0 until roles.length()) {
            val name = roles.optJSONObject(i)?.optString("name")?.lowercase() ?: continue
            if (name.contains("agent") || name.contains("attend") || name.contains("arcade")) return true
        }
        return false
    }

    /** A shop owner / partner (or admin) — sees the module-gated dashboard. */
    fun isShopOwner(userJson: String?): Boolean =
        isAdmin(userJson) || (!isArcadeAgent(userJson) && accountType(userJson).equals("partner", true))

    fun hasPermission(userJson: String?, permission: String): Boolean {
        val user = userObj(userJson) ?: return false
        if (isAdmin(userJson)) return true
        // flat permissions array of strings
        user.optJSONArray("permissions")?.let { perms ->
            for (i in 0 until perms.length()) if (perms.optString(i).equals(permission, true)) return true
        }
        // permissions nested on roles
        val roles = user.optJSONArray("roles") ?: return false
        for (i in 0 until roles.length()) {
            val perms = roles.optJSONObject(i)?.optJSONArray("permissions") ?: continue
            for (j in 0 until perms.length()) {
                val p = perms.opt(j)
                val name = if (p is JSONObject) p.optString("name") else p?.toString()
                if (name.equals(permission, true)) return true
            }
        }
        return false
    }

    fun prettyLabel(raw: String): String {
        val value = raw.trim().replace('_', ' ').replace('-', ' ')
        if (value.isBlank()) return "Ajiriwa"
        return value.split(Regex("\\s+")).joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    fun displayName(userJson: String?, fallback: String): String {
        userObj(userJson)?.let { user ->
            for (k in listOf("business_name", "partner_name", "arcade_name", "company_name", "display_name", "full_name", "name")) {
                val c = user.optString(k, "").trim()
                if (c.isNotEmpty()) return c
            }
        }
        return prettyLabel(fallback)
    }

    /**
     * The modules the partner may use. Reads the same shape the web stores under
     * localStorage "permissions": { modules: [...] }. `billing` and
     * `configurations` are universal (always present), matching the web.
     */
    fun allowedModules(permissionsJson: String?): Set<String> {
        val base = mutableSetOf("billing", "configurations")
        val obj = if (permissionsJson.isNullOrBlank()) null else runCatching { JSONObject(permissionsJson) }.getOrNull()
        val arr = obj?.optJSONArray("modules") ?: return base
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { base.add(it) }
        return base
    }

    /** Parse the /partners/<id>/modules response into a set of slugs. */
    fun parseModulesResponse(body: String?): Set<String> {
        val out = mutableSetOf<String>()
        if (body.isNullOrBlank()) return out
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return out
        val list = obj.optJSONArray("enabled_slugs")
            ?: obj.optJSONArray("enabled")
            ?: obj.optJSONArray("data")
            ?: JSONArray()
        for (i in 0 until list.length()) {
            when (val m = list.opt(i)) {
                is String -> if (m.isNotBlank()) out.add(m)
                is JSONObject -> m.optString("slug").takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }
}
