package com.ada.messenger.desktop.core

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Off-Grid P2P Radio and Multicast Engine for Windows / Desktop.
 *
 * Provides peer discovery, beacon broadcast, and compact frame transmission
 * over local radio/network links without any internet connectivity.
 * Implements Phase 4 of the Off-Grid Radio Mesh Integration Plan.
 */
class DesktopP2pRadioEngine(
    private val core: DesktopAdaCore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        const val TRANSPORT_LAN_MULTICAST = 4
        private const val MULTICAST_GROUP = "239.255.42.99"
        private const val MULTICAST_PORT = 45824
        private const val BEACON_INTERVAL_MS = 10_000L
    }

    private var socket: MulticastSocket? = null
    private var groupAddress: InetAddress? = null
    private var isRunning = false
    private var beaconJob: Job? = null
    private var listenJob: Job? = null

    // Known peers in direct radio / multicast reach: Base64 PeerId -> IP address
    private val directPeerRoutes = ConcurrentHashMap<String, InetAddress>()

    val peerCount: Int get() = directPeerRoutes.size
    val isEngineRunning: Boolean get() = isRunning

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            val group = InetAddress.getByName(MULTICAST_GROUP)
            groupAddress = group
            val s = MulticastSocket(MULTICAST_PORT).apply {
                timeToLive = 2
                joinGroup(group)
                broadcast = true
            }
            socket = s

            startListener(s)
            startBeaconBroadcast()
        } catch (e: Exception) {
            System.err.println("[DesktopP2pRadioEngine] Failed to start multicast socket: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        beaconJob?.cancel()
        beaconJob = null
        listenJob?.cancel()
        listenJob = null

        try {
            groupAddress?.let { socket?.leaveGroup(it) }
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        directPeerRoutes.clear()
    }

    private fun startBeaconBroadcast() {
        beaconJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val beacon = core.meshRadioGenerateBeacon()
                    if (beacon != null && beacon.isNotEmpty()) {
                        broadcastFrame(beacon)
                    }
                } catch (e: Exception) {
                    System.err.println("[DesktopP2pRadioEngine] Beacon broadcast error: ${e.message}")
                }
                delay(BEACON_INTERVAL_MS)
            }
        }
    }

    private fun startListener(socket: MulticastSocket) {
        listenJob = scope.launch {
            val buffer = ByteArray(2048)
            while (isActive && isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val receivedBytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val senderIp = packet.address.hostAddress ?: "unknown"

                    // Process frame through the Rust mesh radio router
                    val ack = core.meshRadioProcessFrame(
                        receivedBytes,
                        TRANSPORT_LAN_MULTICAST,
                        senderIp,
                        -50 // Local LAN/Wi-Fi Direct estimated RSSI
                    )

                    // If immediate ACK is required, send it back directly
                    if (ack != null && ack.isNotEmpty()) {
                        sendDirect(packet.address, packet.port, ack)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        System.err.println("[DesktopP2pRadioEngine] Receive error: ${e.message}")
                    }
                }
            }
        }
    }

    fun sendBytes(peerB64: String, wireBytes: ByteArray): Boolean {
        val frames = core.meshRadioPrepareFrames(
            peerB64,
            wireBytes,
            transportKind = TRANSPORT_LAN_MULTICAST,
            requiresAck = true
        )

        val targetAddr = directPeerRoutes[peerB64]
        return if (targetAddr != null) {
            for (f in frames) {
                sendDirect(targetAddr, MULTICAST_PORT, f)
            }
            true
        } else {
            // Broadcast frames across the local off-grid mesh
            for (f in frames) {
                broadcastFrame(f)
            }
            true
        }
    }

    private fun broadcastFrame(frame: ByteArray) {
        val s = socket ?: return
        val group = groupAddress ?: return
        try {
            val packet = DatagramPacket(frame, frame.size, group, MULTICAST_PORT)
            s.send(packet)
        } catch (e: Exception) {
            System.err.println("[DesktopP2pRadioEngine] Broadcast error: ${e.message}")
        }
    }

    private fun sendDirect(dest: InetAddress, port: Int, frame: ByteArray) {
        val s = socket ?: return
        try {
            val packet = DatagramPacket(frame, frame.size, dest, port)
            s.send(packet)
        } catch (e: Exception) {
            System.err.println("[DesktopP2pRadioEngine] Direct send error: ${e.message}")
        }
    }
}
