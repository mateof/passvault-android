package com.mateof.passvault.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Finding the other phone, and opening a socket to it.
 *
 * Discovery is mDNS through the platform's own `NsdManager`, advertising `_passvault._tcp`. Google's
 * Nearby Connections was rejected for this: it needs Play Services, and an application whose claim is
 * working with no server should not require Google's to pass a ticket across a table.
 *
 * Nothing here authenticates anything, and the naming is deliberately blunt about it. A service
 * record is a name somebody chose. On a café network any device can advertise itself as "Ana's
 * PassVault", and that is why what comes back from here is a [DiscoveredPeer] rather than a trusted
 * one: the six digits in [Transfer] are what decides.
 */
class PeerDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

    private var registration: NsdManager.RegistrationListener? = null

    private val _advertisedName = MutableStateFlow<String?>(null)
    val advertisedName: StateFlow<String?> = _advertisedName.asStateFlow()

    /**
     * Announces this phone on the local network.
     *
     * The name is a label for a human comparing two screens, not an identity. The system may append
     * a suffix if the name is taken, which is reported back so the interface shows what the other
     * phone will actually see rather than what was asked for.
     */
    fun advertise(port: Int, name: String) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "${TransferProtocol.SERVICE_TYPE}."
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                _advertisedName.value = registered.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _advertisedName.value = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _advertisedName.value = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        _advertisedName.value = null
    }

    /**
     * Everything advertising the service, as it comes and goes.
     *
     * A flow rather than a one-shot list: discovery is continuous, and a peer that walks out of the
     * room should disappear from the screen rather than stay there until somebody presses refresh.
     */
    fun discover(ownName: String?): Flow<List<DiscoveredPeer>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredPeer>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                // The phone sees its own advertisement. Filtering it out here rather than in the
                // interface, so no screen ever offers the user their own device to pair with.
                if (service.serviceName == ownName) return
                @Suppress("DEPRECATION")
                nsd.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = resolved.host ?: return
                            found[resolved.serviceName] = DiscoveredPeer(
                                name = resolved.serviceName,
                                host = host,
                                port = resolved.port,
                            )
                            trySend(found.values.toList())
                        }
                    },
                )
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
                trySend(found.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(TransferException(TransferError.PROTOCOL, "discovery would not start ($errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        // The answers to an mDNS query arrive as multicast, and Android drops multicast
        // unless somebody holds the lock. This is why the peer list stayed empty on most real
        // phones while working on every emulator: the emulator's network stack does not filter.
        val multicast = wifi.createMulticastLock("passvault-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }

        nsd.discoverServices(
            "${TransferProtocol.SERVICE_TYPE}.",
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
        trySend(emptyList())
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { multicast.release() }
        }
    }
}

data class DiscoveredPeer(val name: String, val host: InetAddress, val port: Int) {
    val address: String get() = host.hostAddress ?: host.hostName
}

/**
 * The listening half.
 *
 * One connection at a time, on purpose. Passing tickets is a thing two people do facing each other,
 * and a queue of simultaneous transfers would mean six digits on screen with no way to tell which
 * peer they belong to.
 */
class TransferServer(private val onConnection: (Socket) -> Unit) {

    private var socket: ServerSocket? = null

    @Volatile
    private var running = false

    /** Binds to a port the system chooses and returns it, so the advertisement can name it. */
    fun start(): Int {
        val server = ServerSocket(0)
        socket = server
        running = true
        Thread({
            while (running) {
                val accepted = try {
                    server.accept()
                } catch (_: Exception) {
                    // The ordinary way this loop ends is `close` from another thread, which throws
                    // here. Not an error worth reporting.
                    break
                }
                accepted.tcpNoDelay = true
                runCatching { onConnection(accepted) }
                runCatching { accepted.close() }
            }
        }, "passvault-transfer-server").start()
        return server.localPort
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }
}

/** The dialling half. */
object TransferClient {
    fun connect(peer: DiscoveredPeer, timeoutMillis: Int = 10_000): Socket =
        connect(peer.address, peer.port, timeoutMillis)

    /**
     * By address rather than by discovered peer.
     *
     * What a tap produces: the other phone said where it is, so there is no list to browse and no
     * name to pick out of two identical ones.
     */
    fun connect(host: String, port: Int, timeoutMillis: Int = 10_000): Socket =
        Socket().apply {
            tcpNoDelay = true
            soTimeout = timeoutMillis
            connect(java.net.InetSocketAddress(host, port), timeoutMillis)
        }
}
