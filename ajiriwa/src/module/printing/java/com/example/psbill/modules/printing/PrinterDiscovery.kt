package com.example.psbill

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

class PrinterDiscovery(context: Context, private val onPrinterFound: (String, String) -> Unit) {
    private val TAG = "PrinterDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val serviceTypes = listOf("_pdl-datastream._tcp.", "_printer._tcp.", "_ipp._tcp.")

    private val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun startDiscovery() {
        stopDiscovery()
        
        // Acquire multicast lock
        try {
            multicastLock = wifiManager.createMulticastLock("PrinterDiscoveryLock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not acquire multicast lock: ${e.message}")
        }

        serviceTypes.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "Discovery started for $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${service.serviceName} ($type)")
                    resolve(service)
                }

                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Start failed for $serviceType: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            discoveryListeners.add(listener)
            nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    /** Host address of a found printer (Android 14+: service-info callback; older: resolveService). */
    private fun resolve(service: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode")
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    serviceInfo.hostAddresses.firstOrNull()?.hostAddress?.let { onPrinterFound(serviceInfo.serviceName, it) }
                    try { nsdManager.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                }

                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            try {
                nsdManager.registerServiceInfoCallback(service, Executors.newSingleThreadExecutor(), callback)
            } catch (e: Exception) {
                Log.e(TAG, "Resolve failed: ${e.message}")
            }
        } else {
            resolveLegacy(service)
        }
    }

    @Suppress("DEPRECATION") // the only resolve API before Android 14
    private fun resolveLegacy(service: NsdServiceInfo) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                serviceInfo.host?.hostAddress?.let { onPrinterFound(serviceInfo.serviceName, it) }
            }
        })
    }

    fun stopDiscovery() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {}
        multicastLock = null

        discoveryListeners.forEach {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) {}
        }
        discoveryListeners.clear()
    }
}
