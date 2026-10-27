package com.glassremote.data.network

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.glassremote.domain.model.TvDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Finds Google TV / Android TV devices advertising the Remote v2 service over mDNS. */
@Singleton
class TvDiscovery @Inject constructor(private val nsd: NsdManager) {

    fun discover(): Flow<List<TvDevice>> = callbackFlow {
        val found = ConcurrentHashMap<String, TvDevice>()
        val resolveLock = Mutex() // NsdManager (pre-API 34) allows one resolve at a time

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                launch {
                    resolveLock.withLock {
                        resolve(info)?.let { found[it.id] = it; trySend(found.values.sortedBy { d -> d.name }) }
                    }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                trySend(found.values.sortedBy { it.name })
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close(IllegalStateException("mDNS start failed ($errorCode)")) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): TvDevice? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress
                    if (host == null) { if (cont.isActive) cont.resume(null); return }
                    if (cont.isActive) cont.resume(TvDevice(id = si.serviceName, name = si.serviceName, host = host, port = si.port.takeIf { it > 0 } ?: 6466))
                }
            })
        }

    companion object { const val SERVICE_TYPE = "_androidtvremote2._tcp." }
}
