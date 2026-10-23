package com.syncednotes.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.syncednotes.core.sync.Discovery
import com.syncednotes.core.sync.FoundDevice
import com.syncednotes.core.sync.SyncService
import java.util.concurrent.Executors

/**
 * Finding the Mac with Android's Network Service Discovery: multicast DNS on the local
 * network, answered by the devices on it, never by a server. The phone announces itself as
 * `_syncednotes-phone._tcp` and looks for `_syncednotes._tcp` (Docs/Sync.md).
 */
class NsdDiscovery(context: Context) : Discovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    private var browsing: NsdManager.DiscoveryListener? = null
    /** One resolve at a time: older Android versions fail resolves that overlap. */
    private val resolver = Executors.newSingleThreadExecutor()

    @Synchronized
    override fun advertise(port: Int, readyToPair: Boolean, name: String?) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = SyncService.SERVICE_NAME
            serviceType = SyncService.PHONE_SERVICE_TYPE
            this.port = port
            setAttribute("pairing", if (readyToPair) "1" else "0")
            if (name != null) setAttribute("name", name)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        if (runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) registration = listener
    }

    @Synchronized
    override fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    @Synchronized
    override fun browse(found: (FoundDevice) -> Unit, lost: (String) -> Unit) {
        stopBrowsing()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.execute { resolve(info)?.let(found) }
            }
            override fun onServiceLost(info: NsdServiceInfo) = lost(info.serviceName)
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        if (runCatching { nsd.discoverServices(SyncService.MAC_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) {
            browsing = listener
        }
    }

    @Synchronized
    override fun stopBrowsing() {
        browsing?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        browsing = null
    }

    @Suppress("DEPRECATION")  // resolveService still works; its replacement needs API 34
    private fun resolve(info: NsdServiceInfo): FoundDevice? {
        val done = java.util.concurrent.CompletableFuture<FoundDevice?>()
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done.complete(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    // Keyed by the name it was found under, which is what a loss reports.
                    done.complete(host?.let { FoundDevice(info.serviceName, it, serviceInfo.port) })
                }
            })
        }.onFailure { return null }
        return runCatching { done.get(10, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
    }
}
