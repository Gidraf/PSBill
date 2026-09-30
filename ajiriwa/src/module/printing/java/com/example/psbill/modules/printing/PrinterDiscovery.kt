package com.example.psbill

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

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
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host.hostAddress
                            if (host != null) {
                                onPrinterFound(serviceInfo.serviceName, host)
                            }
                        }
                    })
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
