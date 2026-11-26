package com.example.psbill.core

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.provider.Telephony

/** Device admin for PhoneLock. Made device owner once over USB (see [PhoneLock.ADB_COMMAND]). */
class PhoneAdminReceiver : DeviceAdminReceiver()

/**
 * The business phone works like the TV app (owner 2026-11-26: "like a launcher ... control everything ... not the
 * default sms messenger, only this app"). As DEVICE OWNER Ajiriwa:
 *  - is the Home screen, pinned (addPersistentPreferredActivity: pressing Home always comes back here),
 *  - is the SMS app (setDefaultSmsApplication, Android 10+), other messengers are hidden,
 *  - cannot be uninstalled, factory reset / safe boot / new users are blocked,
 *  - optionally suspends every app the owner did not allow (the attendant opens allowed ones from Phone apps).
 * Without device owner it asks Android for the Home and SMS roles (the user taps Allow); nothing can be forced then.
 * Everything is undone by [release] (owner only, in Phone apps).
 */
object PhoneLock {
    const val ADB_COMMAND = "adb shell dpm set-device-owner com.example.psbill.mobile/com.example.psbill.core.PhoneAdminReceiver"
    private const val PREFS = "phone_lock"
    private const val HOME_ALIAS = "com.example.psbill.LauncherAlias"
    private val RESTRICTIONS = listOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_ADD_USER)

    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun admin(c: Context) = ComponentName(c, PhoneAdminReceiver::class.java)
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDeviceOwner(c: Context) = runCatching { dpm(c).isDeviceOwnerApp(c.packageName) }.getOrDefault(false)
    fun locked(c: Context) = prefs(c).getBoolean("locked", false) && isDeviceOwner(c)
    fun appsLocked(c: Context) = prefs(c).getBoolean("apps_locked", false) && isDeviceOwner(c)

    fun isDefaultSms(c: Context) = runCatching { Telephony.Sms.getDefaultSmsPackage(c) == c.packageName }.getOrDefault(false)
    fun isHome(c: Context): Boolean = runCatching {
        val r = c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        r?.activityInfo?.packageName == c.packageName
    }.getOrDefault(false)

    /** Apps the attendant may open (package names). Default: phone, WhatsApp, M-Pesa / Safaricom, camera. */
    fun allowed(c: Context): Set<String> = prefs(c).getStringSet("allowed", null) ?: defaultAllowed(c)
    fun setAllowed(c: Context, pkgs: Set<String>) {
        prefs(c).edit().putStringSet("allowed", pkgs).apply()
        if (appsLocked(c)) applyAppLock(c, true)
    }

    private fun defaultAllowed(c: Context): Set<String> {
        val pm = c.packageManager
        val dialer = runCatching { (c.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage }.getOrNull()
        val camera = pm.resolveActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), 0)?.activityInfo?.packageName
        return (listOfNotNull(dialer, camera) + listOf("com.whatsapp", "com.whatsapp.w4b", "com.safaricom.mysafaricom",
            "com.safaricom.mpesa.lifestyle")).filter { installed(c, it) }.toSet()
    }

    private fun installed(c: Context, pkg: String) = runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun launcherPackages(c: Context): Set<String> {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return c.packageManager.queryIntentActivities(main, 0).map { it.activityInfo.packageName }.toSet() - c.packageName
    }

    private fun smsApps(c: Context): Set<String> {
        @Suppress("DEPRECATION")
        return c.packageManager.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), 0)
            .map { it.activityInfo.packageName }.toSet() - c.packageName
    }

    /** Device owner: home + SMS + no uninstall + restrictions. Returns what could not be done. */
    fun lock(c: Context): List<String> {
        val problems = mutableListOf<String>()
        if (!isDeviceOwner(c)) return listOf("Ajiriwa is not the device owner yet")
        val d = dpm(c); val a = admin(c)
        c.packageManager.setComponentEnabledSetting(ComponentName(c, HOME_ALIAS), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP)
        runCatching {
            val f = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
            d.addPersistentPreferredActivity(a, f, ComponentName(c, HOME_ALIAS))
        }.onFailure { problems += "home: ${it.message}" }
        if (Build.VERSION.SDK_INT >= 29) runCatching { d.setDefaultSmsApplication(a, c.packageName) }.onFailure { problems += "sms: ${it.message}" }
        else problems += "SMS app: Android 10+ needed to force it (asked instead)"
        val hidden = mutableSetOf<String>()
        smsApps(c).forEach { p -> if (runCatching { d.setApplicationHidden(a, p, true) }.getOrDefault(false)) hidden += p }
        prefs(c).edit().putStringSet("hidden", hidden).putBoolean("locked", true).apply()
        runCatching { d.setUninstallBlocked(a, c.packageName, true) }
        RESTRICTIONS.forEach { r -> runCatching { d.addUserRestriction(a, r) } }
        ActivityLog.log("phone_locked", context = c)
        return problems
    }

    /** Suspend (grey out) every launcher app that is not allowed; Android 7+, device owner. */
    fun applyAppLock(c: Context, on: Boolean): List<String> {
        if (!isDeviceOwner(c) || Build.VERSION.SDK_INT < 24) return listOf("Needs device owner on Android 7+")
        val d = dpm(c); val a = admin(c)
        val all = launcherPackages(c) + (prefs(c).getStringSet("suspended", emptySet()) ?: emptySet())
        val allow = allowed(c)
        val suspend = if (on) (all - allow - c.packageName).toTypedArray() else emptyArray()
        val free = (all - suspend.toSet()).toTypedArray()
        val failed = runCatching { d.setPackagesSuspended(a, suspend, true).toList() + d.setPackagesSuspended(a, free, false).toList() }
            .getOrElse { return listOf(it.message ?: "could not change apps") }
        prefs(c).edit().putBoolean("apps_locked", on).putStringSet("suspended", suspend.toSet() - failed.toSet()).apply()
        ActivityLog.log(if (on) "phone_apps_locked" else "phone_apps_unlocked", context = c)
        return failed.map { "$it could not be changed" }
    }

    /** Undo everything (owner). Keeps device owner so it can be locked again; [giveUpOwner] removes that too. */
    fun release(c: Context, giveUpOwner: Boolean = false) {
        if (!isDeviceOwner(c)) return
        val d = dpm(c); val a = admin(c)
        applyAppLock(c, false)
        runCatching { d.clearPackagePersistentPreferredActivities(a, c.packageName) }
        (prefs(c).getStringSet("hidden", emptySet()) ?: emptySet()).forEach { p -> runCatching { d.setApplicationHidden(a, p, false) } }
        runCatching { d.setUninstallBlocked(a, c.packageName, false) }
        RESTRICTIONS.forEach { r -> runCatching { d.clearUserRestriction(a, r) } }
        prefs(c).edit().putBoolean("locked", false).putStringSet("hidden", emptySet()).apply()
        ActivityLog.log("phone_released", context = c)
        if (giveUpOwner) runCatching { @Suppress("DEPRECATION") d.clearDeviceOwnerApp(c.packageName) }
    }

    /** Without device owner: Android's own "make Ajiriwa the home / SMS app" prompts. */
    fun askHomeRole(c: Context): Intent? {
        c.packageManager.setComponentEnabledSetting(ComponentName(c, HOME_ALIAS), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP)
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = c.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }

    fun askSmsRole(c: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = c.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) return rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
            return null
        }
        @Suppress("DEPRECATION")
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, c.packageName)
    }

    /** Re-apply after an update / reboot (Android keeps most of it, this fills any gap). */
    fun reapply(c: Context) {
        if (locked(c)) runCatching { lock(c) }
        if (appsLocked(c)) runCatching { applyAppLock(c, true) }
    }
}
