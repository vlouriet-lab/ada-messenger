package com.ada.messenger.core

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.aware.*
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Pure BLE (GATT + Coded PHY L2CAP CoC), Wi-Fi Aware (NAN),
 * and Wi-Fi Direct connections for Zero-Infrastructure Off-Grid Radio Mesh transport.
 * Bridged with Rust (`ada_receive_mesh_bytes` / `meshRadioProcessFrame`).
 */
@SuppressLint("MissingPermission")
class LocalMeshManager(private val context: Context, private val core: AdaCore?) {

    companion object {
        private const val TAG = "LocalMeshManager"
        private const val MAX_PENDING_PAYLOADS_PER_ROUTE = 32
        private const val DEFAULT_BLE_MTU = 23
        private const val REQUESTED_BLE_MTU = 517
        private const val GATT_WRITE_OVERHEAD = 3

        const val TRANSPORT_BLE_CODED = 1
        const val TRANSPORT_WIFI_AWARE = 2
        const val TRANSPORT_CELLULAR_SIDELINK = 3
        const val TRANSPORT_LAN_MULTICAST = 4
        const val NAN_SERVICE_NAME = "ada_mesh_nan_v1"
        
        @Volatile
        private var instance: LocalMeshManager? = null

        fun getInstance(context: Context, core: AdaCore?): LocalMeshManager {
            instance?.updateCore(core)
            return instance ?: synchronized(this) {
                instance?.also {
                    it.updateCore(core)
                } ?: LocalMeshManager(context.applicationContext, core).also { instance = it }
            }
        }
        
        // Characteristic UUID for ADA Mesh
        val SERVICE_UUID: java.util.UUID = java.util.UUID.fromString("A0B1C2D3-E4F5-4A6B-8C9D-0123456789AB")
        val WRITE_CHAR_UUID: java.util.UUID = java.util.UUID.fromString("A0B1C2D3-E4F5-4A6B-8C9D-0123456789AC")
    }

    @Volatile
    private var coreRef: AdaCore? = core

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    
    // Connected client devices we can send to
    private val connectedClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val bleMtuByRoute = ConcurrentHashMap<String, Int>()
    private val bleWriteLock = Any()
    private val bleWriteQueues = HashMap<String, java.util.ArrayDeque<ByteArray>>()
    private val bleWriteInFlight = HashSet<String>()

    // --- BLE Coded PHY + L2CAP CoC ---
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var allocatedPsm: Int = 0
    private val l2capSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val l2capWriteLocks = ConcurrentHashMap<String, Any>()
    @Volatile
    var isCodedPhyActive: Boolean = false
        private set

    // --- Wi-Fi Aware (NAN) ---
    private val wifiAwareManager: WifiAwareManager? by lazy {
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }
    private var wifiAwareSession: WifiAwareSession? = null
    private var nanPublishSession: PublishDiscoverySession? = null
    private var nanSubscribeSession: SubscribeDiscoverySession? = null
    private val nanPeersByRoute = ConcurrentHashMap<String, PeerHandle>()

    // --- 5G Sidelink (PC5) ---
    private var sidelinkMonitorJob: Job? = null
    @Volatile
    var detectedSidelinkInterface: String? = null
        private set

    // Maps a peer's Base64 ID to their Bluetooth MAC or IP Address
    private val peerRouteMap = ConcurrentHashMap<String, String>()
    private val routeStateLock = Any()
    private val pendingRoutePayloads = HashMap<String, java.util.ArrayDeque<ByteArray>>()
    private val routeDecoders = HashMap<String, MeshFrameCodec.Decoder>()
    private val socketWriteLocks = ConcurrentHashMap<Socket, Any>()

    private var isRunning = false

    /** True only when Wi-Fi Direct P2P channel successfully initialised (no hotspot conflict). */
    val isWifiDirectActive: Boolean get() = wifiP2pChannel != null

    // --- Wi-Fi Direct Variables ---
    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var serverSocket: ServerSocket? = null
    private val activeSockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val p2pScope = CoroutineScope(Dispatchers.IO)

    // --- Radio Mesh Preferences & Status ---
    private val meshPrefs = context.getSharedPreferences("ada_mesh", Context.MODE_PRIVATE)
    @Volatile
    var isCodedPhyEnabled: Boolean = meshPrefs.getBoolean("mesh_coded_phy_enabled", true)
        private set
    @Volatile
    var isWifiAwareEnabled: Boolean = meshPrefs.getBoolean("mesh_wifi_aware_enabled", true)
        private set
    @Volatile
    var isDtnRelayEnabled: Boolean = meshPrefs.getBoolean("mesh_dtn_relay_enabled", true)
        private set

    data class MeshRadioCapabilities(
        val isBleSupported: Boolean,
        val isBleCodedPhySupported: Boolean,
        val isL2capCoCSupported: Boolean,
        val isWifiAwareSupported: Boolean,
        val isWifiDirectSupported: Boolean,
        val detectedSidelinkInterface: String?,
    )

    data class MeshRadioStatus(
        val isMeshRunning: Boolean,
        val isCodedPhyActive: Boolean,
        val isWifiAwareActive: Boolean,
        val isWifiDirectActive: Boolean,
        val blePeerCount: Int,
        val wifiAwarePeerCount: Int,
        val wifiDirectPeerCount: Int,
        val totalPeerCount: Int,
        val capabilities: MeshRadioCapabilities,
    )

    fun setCodedPhyEnabled(enabled: Boolean) {
        if (isCodedPhyEnabled == enabled) return
        isCodedPhyEnabled = enabled
        meshPrefs.edit().putBoolean("mesh_coded_phy_enabled", enabled).apply()
        if (isRunning) {
            if (enabled) startBleCodedPhy() else stopBleCodedPhy()
        }
    }

    fun setWifiAwareEnabled(enabled: Boolean) {
        if (isWifiAwareEnabled == enabled) return
        isWifiAwareEnabled = enabled
        meshPrefs.edit().putBoolean("mesh_wifi_aware_enabled", enabled).apply()
        if (isRunning) {
            if (enabled) startWifiAwareNan() else stopWifiAwareNan()
        }
    }

    fun setDtnRelayEnabled(enabled: Boolean) {
        isDtnRelayEnabled = enabled
        meshPrefs.edit().putBoolean("mesh_dtn_relay_enabled", enabled).apply()
    }

    fun getCapabilities(): MeshRadioCapabilities {
        val adapter = bluetoothAdapter
        val pm = context.packageManager
        val isBleSupported = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)
        val isBleCoded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (adapter?.isLeCodedPhySupported == true)
        val isL2capCoC = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val isWifiAware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_AWARE) &&
                wifiAwareManager?.isAvailable == true
        val isWifiDirect = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)
        return MeshRadioCapabilities(
            isBleSupported = isBleSupported,
            isBleCodedPhySupported = isBleCoded,
            isL2capCoCSupported = isL2capCoC,
            isWifiAwareSupported = isWifiAware,
            isWifiDirectSupported = isWifiDirect,
            detectedSidelinkInterface = detectedSidelinkInterface,
        )
    }

    fun getStatus(): MeshRadioStatus {
        val blePeers = connectedClients.size + l2capSockets.size
        val nanPeers = nanPeersByRoute.size
        val p2pPeers = activeSockets.size
        return MeshRadioStatus(
            isMeshRunning = isRunning,
            isCodedPhyActive = isCodedPhyActive,
            isWifiAwareActive = wifiAwareSession != null,
            isWifiDirectActive = isWifiDirectActive,
            blePeerCount = blePeers,
            wifiAwarePeerCount = nanPeers,
            wifiDirectPeerCount = p2pPeers,
            totalPeerCount = peerRouteMap.size,
            capabilities = getCapabilities(),
        )
    }

    private fun updateCore(core: AdaCore?) {
        coreRef = core
    }

    private fun activeCore(): AdaCore? = AdaCoreHolder.instance ?: coreRef

    fun start() {
        if (isRunning) return
        Log.d(TAG, "Starting LocalMeshManager (BLE Coded PHY, Wi-Fi Aware NAN, Wi-Fi Direct)")
        isRunning = true
        
        try {
            if (isCodedPhyEnabled) {
                startBleCodedPhy()
            }
            startGattServer()
            startAdvertising()
            startScanning()
            if (isWifiAwareEnabled) {
                startWifiAwareNan()
            }
            startWifiDirect()
            startSidelinkMonitoring()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth/Wifi permissions", e)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping LocalMeshManager")
        isRunning = false
        try {
            stopScanning()
            stopAdvertising()
            stopGattServer()
            stopBleCodedPhy()
            stopWifiAwareNan()
            stopWifiDirect()
            stopSidelinkMonitoring()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping LocalMeshManager", e)
        }
        clearAllRouteState()
    }

    private fun bufferPendingPayload(routeId: String, payload: ByteArray) {
        synchronized(routeStateLock) {
            val queue = pendingRoutePayloads.getOrPut(routeId) { java.util.ArrayDeque() }
            if (queue.size >= MAX_PENDING_PAYLOADS_PER_ROUTE) {
                queue.removeFirst()
                Log.w(TAG, "Dropping oldest buffered mesh payload for route $routeId")
            }
            queue.addLast(payload.copyOf())
        }
    }

    private fun registerPeerRoute(peerId: String, routeId: String): List<ByteArray> {
        synchronized(routeStateLock) {
            val previousPeer = peerRouteMap.entries.find { it.value == routeId }?.key
            if (previousPeer != null && previousPeer != peerId) {
                peerRouteMap.remove(previousPeer)
            }
            peerRouteMap[peerId] = routeId
            return pendingRoutePayloads.remove(routeId)?.toList().orEmpty()
        }
    }

    private fun findPeerForRoute(routeId: String): String? = synchronized(routeStateLock) {
        peerRouteMap.entries.find { it.value == routeId }?.key
    }

    private fun clearRoute(routeId: String): String? {
        synchronized(routeStateLock) {
            pendingRoutePayloads.remove(routeId)
            routeDecoders.remove(routeId)
            val disconnectedPeer = peerRouteMap.entries.find { it.value == routeId }?.key
            if (disconnectedPeer != null) {
                peerRouteMap.remove(disconnectedPeer)
            }
            bleMtuByRoute.remove(routeId)
            synchronized(bleWriteLock) {
                bleWriteQueues.remove(routeId)
                bleWriteInFlight.remove(routeId)
            }
            return disconnectedPeer
        }
    }

    private fun clearAllRouteState() {
        synchronized(routeStateLock) {
            pendingRoutePayloads.clear()
            routeDecoders.clear()
            peerRouteMap.clear()
        }
        bleMtuByRoute.clear()
        synchronized(bleWriteLock) {
            bleWriteQueues.clear()
            bleWriteInFlight.clear()
        }
    }

    private fun decodeTransportFrames(routeId: String, bytes: ByteArray): List<MeshFrameCodec.MeshFrame> {
        synchronized(routeStateLock) {
            return routeDecoders.getOrPut(routeId) { MeshFrameCodec.Decoder() }.append(bytes)
        }
    }

    private fun routeBleChunkSize(routeId: String): Int =
        ((bleMtuByRoute[routeId] ?: DEFAULT_BLE_MTU) - GATT_WRITE_OVERHEAD).coerceAtLeast(1)

    private fun enqueueBleFrame(gatt: BluetoothGatt, frame: ByteArray) {
        val routeId = gatt.device.address
        val chunks = MeshFrameCodec.chunk(frame, routeBleChunkSize(routeId))
        val shouldKick = synchronized(bleWriteLock) {
            val queue = bleWriteQueues.getOrPut(routeId) { java.util.ArrayDeque() }
            chunks.forEach(queue::addLast)
            bleWriteInFlight.add(routeId)
        }
        if (shouldKick) {
            writeNextBleChunk(gatt)
        }
    }

    private fun writeNextBleChunk(gatt: BluetoothGatt) {
        val routeId = gatt.device.address
        val chunk = synchronized(bleWriteLock) {
            val queue = bleWriteQueues[routeId]
            if (queue == null || queue.isEmpty()) {
                bleWriteInFlight.remove(routeId)
                null
            } else {
                queue.removeFirst()
            }
        } ?: return

        try {
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(WRITE_CHAR_UUID)
            if (characteristic == null) {
                failBleWrites(routeId, "BLE write characteristic unavailable")
                return
            }

            val started = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            if (!started) {
                failBleWrites(routeId, "BLE writeCharacteristic returned false")
            }
        } catch (e: SecurityException) {
            failBleWrites(routeId, "BLE write failed for $routeId", e)
        }
    }

    private fun failBleWrites(routeId: String, message: String, error: Throwable? = null) {
        if (error != null) {
            Log.e(TAG, message, error)
        } else {
            Log.e(TAG, message)
        }
        synchronized(bleWriteLock) {
            bleWriteQueues.remove(routeId)
            bleWriteInFlight.remove(routeId)
        }
    }

    private fun writeTcpFrame(socket: Socket, frame: ByteArray, label: String) {
        val lock = socketWriteLocks.getOrPut(socket) { Any() }
        synchronized(lock) {
            val out = socket.getOutputStream()
            out.write(frame)
            out.flush()
        }
        Log.d(TAG, "Sent $label to ${socket.inetAddress.hostAddress}")
    }

    private fun handleHandshakeFrame(routeId: String, payload: ByteArray) {
        val remotePeerId = MeshFrameCodec.decodeHandshakePeerId(payload).trim()
        if (remotePeerId.isBlank()) {
            Log.w(TAG, "Ignoring empty mesh handshake on route $routeId")
            return
        }

        Log.d(TAG, "Handshake received! Mapping $remotePeerId to route $routeId")
        val bufferedPayloads = registerPeerRoute(remotePeerId, routeId)
        activeCore()?.receiveMeshBytes(remotePeerId, ByteArray(0))
        bufferedPayloads.forEach { bufferedPayload ->
            Log.d(TAG, "Draining buffered mesh payload (${bufferedPayload.size} bytes) for $remotePeerId")
            activeCore()?.receiveMeshBytes(remotePeerId, bufferedPayload)
        }
    }

    private fun handlePayloadFrame(routeId: String, payload: ByteArray) {
        val foundPeer = findPeerForRoute(routeId)
        if (foundPeer == null) {
            Log.w(TAG, "Received ${payload.size} bytes on route $routeId before handshake; buffering")
            bufferPendingPayload(routeId, payload)
            return
        }
        Log.d(TAG, "Received ${payload.size} bytes from $foundPeer (Route: $routeId). Passing to Rust.")
        activeCore()?.receiveMeshBytes(foundPeer, payload)
    }

    // =========================================================================
    // 1. BLE CODED PHY (Long Range S=8) + L2CAP Connection-Oriented Channels
    // =========================================================================

    private fun startBleCodedPhy() {
        val adapter = bluetoothAdapter ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !adapter.isLeCodedPhySupported) {
            Log.d(TAG, "BLE Coded PHY / L2CAP not supported on this device. Using legacy GATT fallback.")
            return
        }

        try {
            val server = adapter.listenUsingInsecureL2capChannel()
            l2capServerSocket = server
            allocatedPsm = server.psm
            isCodedPhyActive = true
            Log.i(TAG, "BLE L2CAP CoC Server started on PSM=0x${Integer.toHexString(allocatedPsm)}")

            p2pScope.launch {
                while (isRunning) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    handleL2capSocket(socket)
                }
            }

            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser != null) {
                val params = AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setConnectable(true)
                    .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .build()

                val psmBytes = ByteBuffer.allocate(2).putShort(allocatedPsm.toShort()).array()
                val advData = AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .addServiceData(ParcelUuid(SERVICE_UUID), psmBytes)
                    .setIncludeDeviceName(false)
                    .build()

                advertiser.startAdvertisingSet(params, advData, null, null, null, object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                        Log.d(TAG, "BLE Coded PHY Extended Advertising started with status=$status")
                    }
                })
            }

            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
                val scanSettings = ScanSettings.Builder()
                    .setLegacy(false)
                    .setPhy(BluetoothDevice.PHY_LE_CODED)
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

                scanner.startScan(listOf(scanFilter), scanSettings, object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                        if (serviceData.size >= 2) {
                            val remotePsm = ByteBuffer.wrap(serviceData).short.toInt() and 0xFFFF
                            connectBleCodedL2cap(result.device, remotePsm, result.rssi)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed starting BLE Coded PHY / L2CAP CoC: ${e.message}")
        }
    }

    private fun stopBleCodedPhy() {
        isCodedPhyActive = false
        try {
            l2capServerSocket?.close()
        } catch (e: Exception) {}
        l2capServerSocket = null
        l2capSockets.values.forEach { runCatching { it.close() } }
        l2capSockets.clear()
        l2capWriteLocks.clear()
    }

    private fun connectBleCodedL2cap(device: BluetoothDevice, psm: Int, rssi: Int) {
        val routeId = device.address
        if (l2capSockets.containsKey(routeId)) return

        p2pScope.launch {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.connectGatt(context, false, object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                gatt.setPreferredPhy(
                                    BluetoothDevice.PHY_LE_CODED_MASK,
                                    BluetoothDevice.PHY_LE_CODED_MASK,
                                    BluetoothDevice.PHY_OPTION_S8
                                )
                            }
                        }
                    }, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_CODED_MASK)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val socket = device.createInsecureL2capChannel(psm)
                    socket.connect()
                    handleL2capSocket(socket, rssi.toByte())
                }
            }.onFailure {
                Log.d(TAG, "L2CAP connection failed for $routeId: ${it.message}")
            }
        }
    }

    private fun handleL2capSocket(socket: BluetoothSocket, initialRssi: Byte = -85) {
        val routeId = socket.remoteDevice.address
        l2capSockets[routeId] = socket
        l2capWriteLocks[routeId] = Any()
        Log.i(TAG, "Active L2CAP CoC channel established with $routeId")

        val beacon = activeCore()?.meshRadioGenerateBeacon()
        if (beacon != null) {
            writeL2capFrame(routeId, beacon)
        }

        p2pScope.launch {
            val input = socket.inputStream
            val buffer = ByteArray(2048)
            try {
                while (isRunning && socket.isConnected) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val packet = buffer.copyOfRange(0, read)
                    onReceivedRadioBytes(TRANSPORT_BLE_CODED, routeId, initialRssi.toInt(), packet)
                }
            } catch (e: Exception) {
                Log.d(TAG, "L2CAP read loop terminated for $routeId: ${e.message}")
            } finally {
                l2capSockets.remove(routeId)
                l2capWriteLocks.remove(routeId)
                val peer = clearRoute(routeId)
                if (peer != null) {
                    activeCore()?.meshPeerDisconnected(peer)
                }
                runCatching { socket.close() }
            }
        }
    }

    private fun writeL2capFrame(routeId: String, frame: ByteArray): Boolean {
        val socket = l2capSockets[routeId] ?: return false
        val lock = l2capWriteLocks[routeId] ?: return false
        return try {
            synchronized(lock) {
                val out = socket.outputStream
                out.write(frame)
                out.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing L2CAP frame to $routeId: ${e.message}")
            false
        }
    }

    // =========================================================================
    // 2. WI-FI AWARE (NAN) — P2P Mesh Channel without Group Owner Conflicts
    // =========================================================================

    private fun startWifiAwareNan() {
        val manager = wifiAwareManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !manager.isAvailable) {
            Log.d(TAG, "Wi-Fi Aware (NAN) is not available on this device")
            return
        }

        try {
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    wifiAwareSession = session
                    Log.i(TAG, "Wi-Fi Aware NAN session attached successfully")

                    val pubConfig = PublishConfig.Builder()
                        .setServiceName(NAN_SERVICE_NAME)
                        .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                        .build()

                    session.publish(pubConfig, object : DiscoverySessionCallback() {
                        override fun onPublishStarted(publishSession: PublishDiscoverySession) {
                            nanPublishSession = publishSession
                            Log.d(TAG, "Wi-Fi Aware Publish started: $NAN_SERVICE_NAME")
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            val routeId = "nan_${peerHandle.hashCode()}"
                            nanPeersByRoute[routeId] = peerHandle
                            onReceivedRadioBytes(TRANSPORT_WIFI_AWARE, routeId, -65, message)
                        }
                    }, null)

                    val subConfig = SubscribeConfig.Builder()
                        .setServiceName(NAN_SERVICE_NAME)
                        .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                        .build()

                    session.subscribe(subConfig, object : DiscoverySessionCallback() {
                        override fun onSubscribeStarted(subscribeSession: SubscribeDiscoverySession) {
                            nanSubscribeSession = subscribeSession
                            Log.d(TAG, "Wi-Fi Aware Subscribe started: $NAN_SERVICE_NAME")
                        }

                        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                            val routeId = "nan_${peerHandle.hashCode()}"
                            nanPeersByRoute[routeId] = peerHandle
                            Log.d(TAG, "Wi-Fi Aware Peer discovered: $routeId")

                            val beacon = activeCore()?.meshRadioGenerateBeacon()
                            if (beacon != null && nanSubscribeSession != null) {
                                nanSubscribeSession?.sendMessage(peerHandle, 1, beacon)
                            }
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            val routeId = "nan_${peerHandle.hashCode()}"
                            nanPeersByRoute[routeId] = peerHandle
                            onReceivedRadioBytes(TRANSPORT_WIFI_AWARE, routeId, -65, message)
                        }
                    }, null)
                }

                override fun onAttachFailed() {
                    Log.w(TAG, "Wi-Fi Aware attach failed")
                }
            }, null)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi Aware startup error: ${e.message}")
        }
    }

    private fun stopWifiAwareNan() {
        try {
            nanPublishSession?.close()
            nanSubscribeSession?.close()
            wifiAwareSession?.close()
        } catch (e: Exception) {}
        nanPublishSession = null
        nanSubscribeSession = null
        wifiAwareSession = null
        nanPeersByRoute.clear()
    }

    // =========================================================================
    // 3. 5G SIDELINK (PC5 / ProSe) DIRECT MODEM MONITORING
    // =========================================================================

    private fun startSidelinkMonitoring() {
        sidelinkMonitorJob = p2pScope.launch {
            val sidelinkPrefixes = listOf("rmnet_data_pc5", "cv2x", "prose", "sl0")
            while (isActive && isRunning) {
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    for (netIf in interfaces) {
                        if (netIf.isUp && sidelinkPrefixes.any { netIf.name.startsWith(it) }) {
                            val ipv6 = netIf.inetAddresses.toList()
                                .filterIsInstance<Inet6Address>()
                                .firstOrNull { it.isLinkLocalAddress }
                            if (ipv6 != null) {
                                val host = ipv6.hostAddress ?: ""
                                detectedSidelinkInterface = "${netIf.name}:$host"
                                Log.i(TAG, "5G Sidelink PC5 direct interface detected: ${netIf.name} ($host)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Sidelink monitor tick: ${e.message}")
                }
                delay(15_000)
            }
        }
    }

    private fun stopSidelinkMonitoring() {
        sidelinkMonitorJob?.cancel()
        sidelinkMonitorJob = null
    }

    fun onReceivedRadioBytes(transportKind: Int, routeId: String, rssi: Int, bytes: ByteArray) {
        val core = activeCore()
        if (core != null) {
            val ackBytes = core.meshRadioProcessFrame(bytes, transportKind, routeId, rssi)
            if (ackBytes != null && ackBytes.isNotEmpty()) {
                when (transportKind) {
                    TRANSPORT_BLE_CODED -> writeL2capFrame(routeId, ackBytes)
                    TRANSPORT_WIFI_AWARE -> {
                        val peerHandle = nanPeersByRoute[routeId]
                        if (peerHandle != null) {
                            nanPublishSession?.sendMessage(peerHandle, 0, ackBytes)
                                ?: nanSubscribeSession?.sendMessage(peerHandle, 0, ackBytes)
                        }
                    }
                }
            }
        }
        onReceivedBytes(routeId, bytes)
    }

    @Throws(SecurityException::class)
    private fun startGattServer() {
        if (bluetoothAdapter == null) return
        Log.d(TAG, "Starting GATT Server")
        
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writeChar = BluetoothGattCharacteristic(
            WRITE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(writeChar)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(TAG, "Server Connection State MAC=${device.address} newState=$newState status=$status")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic.uuid == WRITE_CHAR_UUID && value != null) {
                Log.d(TAG, "GATT Server received ${value.size} bytes from ${device.address}")

                onReceivedBytes(device.address, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    @Throws(SecurityException::class)
    private fun stopGattServer() {
        gattServer?.close()
        gattServer = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertise Started successfully")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertise failed with error $errorCode")
        }
    }

    @Throws(SecurityException::class)
    private fun startAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        Log.d(TAG, "Starting BLE Advertising")
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
            
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
            
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @Throws(SecurityException::class)
    private fun stopAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        Log.d(TAG, "Stopping BLE Advertising")
        advertiser.stopAdvertising(advertiseCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            if (!connectedClients.containsKey(device.address)) {
                Log.d(TAG, "Discovered ADA Mesh peer: ${device.address}. Connecting...")
                try {
                    val gatt = device.connectGatt(context, false, gattClientCallback)
                    connectedClients[device.address] = gatt
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied connecting to ${device.address}", e)
                }
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Client Connected to ${gatt.device.address}. Discovering services...")
                bleMtuByRoute[gatt.device.address] = DEFAULT_BLE_MTU
                try {
                    gatt.requestMtu(REQUESTED_BLE_MTU)
                } catch (e: SecurityException) {
                    Log.w(TAG, "BLE MTU request failed for ${gatt.device.address}", e)
                }
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) { }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Client Disconnected from ${gatt.device.address}.")
                connectedClients.remove(gatt.device.address)
                val disconnectedPeer = clearRoute(gatt.device.address)
                if (disconnectedPeer != null) {
                    activeCore()?.meshPeerDisconnected(disconnectedPeer)
                }
                try { gatt.close() } catch (e: SecurityException) {}
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bleMtuByRoute[gatt.device.address] = mtu
                Log.d(TAG, "Negotiated BLE MTU=$mtu for ${gatt.device.address}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered for ${gatt.device.address}")
                sendHandshakeViaBle(gatt)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (characteristic.uuid != WRITE_CHAR_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeNextBleChunk(gatt)
            } else {
                failBleWrites(gatt.device.address, "BLE chunk write failed with status=$status")
            }
        }
    }

    private fun sendHandshakeViaBle(gatt: BluetoothGatt) {
        val myPeerId = activeCore()?.getPeerId() ?: return
        val frame = MeshFrameCodec.encodeHandshake(myPeerId)
        try {
            enqueueBleFrame(gatt, frame)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE handshake failed", e)
        }
    }

    private fun sendHandshakeViaTcp(socket: Socket) {
        p2pScope.launch {
            val myPeerId = activeCore()?.getPeerId() ?: return@launch
            val frame = MeshFrameCodec.encodeHandshake(myPeerId)
            try {
                writeTcpFrame(socket, frame, "TCP handshake")
            } catch (e: Exception) {
                Log.e(TAG, "TCP handshake failed", e)
            }
        }
    }

    @Throws(SecurityException::class)
    private fun startScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        Log.d(TAG, "Starting BLE Scanning")
        
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
            
        scanner.startScan(filters, settings, scanCallback)
    }

    @Throws(SecurityException::class)
    private fun stopScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        Log.d(TAG, "Stopping BLE Scanning")
        scanner.stopScan(scanCallback)
        
        // Close all client connections
        connectedClients.values.forEach { 
            try { it.close() } catch(e: SecurityException) {}
        }
        connectedClients.clear()
        clearAllRouteState()
    }

    /**
     * Called by AdaCoreViewModel when Rust emits `ADAEvent::SendViaLocalMesh`.
     */
    fun sendBytes(peerB64: String, payload: ByteArray) {
        Log.d(TAG, "Dispatching ${payload.size} bytes to $peerB64 via Local Mesh")
        
        val targetRoute = peerRouteMap[peerB64]

        // 1. Try Wi-Fi Aware (NAN) first (highest throughput, zero hotspot conflicts)
        val nanPeer = if (targetRoute != null) nanPeersByRoute[targetRoute] else nanPeersByRoute.values.firstOrNull()
        if (nanPeer != null) {
            val radioFrames = activeCore()?.meshRadioPrepareFrames(peerB64, payload, TRANSPORT_WIFI_AWARE, true)
            val framesToSend = if (!radioFrames.isNullOrEmpty()) radioFrames else listOf(payload)
            for (f in framesToSend) {
                nanPublishSession?.sendMessage(nanPeer, 0, f)
                    ?: nanSubscribeSession?.sendMessage(nanPeer, 0, f)
            }
            return
        }

        // 2. Try BLE Coded PHY via L2CAP CoC (long range up to 800m, high reliability)
        if (targetRoute != null && l2capSockets.containsKey(targetRoute)) {
            val radioFrames = activeCore()?.meshRadioPrepareFrames(peerB64, payload, TRANSPORT_BLE_CODED, true)
            val framesToSend = if (!radioFrames.isNullOrEmpty()) radioFrames else listOf(payload)
            var allSent = true
            for (f in framesToSend) {
                if (!writeL2capFrame(targetRoute, f)) {
                    allSent = false
                    break
                }
            }
            if (allSent) return
        }

        val frame = MeshFrameCodec.encodePayload(payload)
        
        // 3. Fallback to standard BLE GATT
        connectedClients.values.forEach { gatt ->
            if (targetRoute == null || targetRoute == gatt.device.address) {
                try {
                    enqueueBleFrame(gatt, frame)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied writing characteristic", e)
                }
            }
        }
        
        // 4. Fallback to Wi-Fi Direct TCP Sockets
        p2pScope.launch {
            val deadSockets = mutableListOf<Socket>()
            activeSockets.forEach { socket ->
                if (targetRoute == null || targetRoute == socket.inetAddress.hostAddress) {
                    try {
                        writeTcpFrame(socket, frame, "mesh payload")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed sending via TCP socket to ${socket.inetAddress.hostAddress}", e)
                        deadSockets.add(socket)
                    }
                }
            }
            // Remove and close sockets that failed to write (avoid zombie accumulation)
            deadSockets.forEach { dead ->
                activeSockets.remove(dead)
                socketWriteLocks.remove(dead)
                runCatching { dead.close() }
            }
        }
    }

    /**
     * Called when transport bytes arrive from BLE or Wi-Fi Direct.
     */
    fun onReceivedBytes(routeId: String, transportBytes: ByteArray) {
        val frames = decodeTransportFrames(routeId, transportBytes)
        if (frames.isEmpty()) {
            Log.d(TAG, "Buffered ${transportBytes.size} transport bytes for route $routeId awaiting more data")
            return
        }

        frames.forEach { frame ->
            when (frame.type) {
                MeshFrameCodec.FrameType.Handshake -> handleHandshakeFrame(routeId, frame.payload)
                MeshFrameCodec.FrameType.Payload -> handlePayloadFrame(routeId, frame.payload)
            }
        }
    }

    // =========================================================
    // WI-FI DIRECT IMPLEMENTATION
    // =========================================================

    @Throws(SecurityException::class)
    private fun startWifiDirect() {
        if (wifiP2pManager == null) return
        val ch = try {
            wifiP2pManager?.initialize(context, context.mainLooper, null)
        } catch (e: Exception) {
            Log.e(TAG, "Wi-Fi Direct channel init failed (hotspot active?)", e)
            null
        }
        if (ch == null) {
            Log.w(TAG, "Wi-Fi Direct unavailable — skipping P2P init")
            return
        }
        wifiP2pChannel = ch

        p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.d(TAG, "Wi-Fi Direct P2P is enabled")
                            discoverWifiPeers()
                        } else {
                            Log.d(TAG, "Wi-Fi Direct P2P is not enabled")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        wifiP2pManager?.requestPeers(wifiP2pChannel) { peers ->
                            Log.d(TAG, "Wi-Fi Direct found ${peers.deviceList.size} peers")
                            for (device in peers.deviceList) {
                                // Connect automatically for mesh routing
                                connectToWifiPeer(device)
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo: android.net.NetworkInfo? =
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                            }
                        @Suppress("DEPRECATION")
                        if (networkInfo?.isConnected == true) {
                            wifiP2pManager?.requestConnectionInfo(wifiP2pChannel) { info ->
                                if (info.groupFormed && info.isGroupOwner) {
                                    Log.d(TAG, "I am Wi-Fi Direct Group Owner. Starting Server Socket.")
                                    startWifiDirectServer()
                                } else if (info.groupFormed) {
                                    val ownerIp = info.groupOwnerAddress.hostAddress
                                    Log.d(TAG, "Connected as Peer. Group Owner IP = $ownerIp")
                                    connectWifiDirectSocket(ownerIp)
                                }
                            }
                        }
                    }
                }
            }
        }
        context.registerReceiver(p2pReceiver, intentFilter)
    }

    private fun stopWifiDirect() {
        try {
            p2pReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {}
        p2pReceiver = null

        serverSocket?.close()
        serverSocket = null

        val ch = wifiP2pChannel ?: return
        try {
            wifiP2pManager?.cancelConnect(ch, null)
        } catch (e: Exception) {
            Log.w(TAG, "cancelConnect failed", e)
        }
        try {
            wifiP2pManager?.removeGroup(ch, null)
        } catch (e: Exception) {
            Log.w(TAG, "removeGroup failed", e)
        }
        wifiP2pChannel = null
    }

    @Throws(SecurityException::class)
    private fun discoverWifiPeers() {
        wifiP2pManager?.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "P2P Peer Discovery started") }
            override fun onFailure(reasonCode: Int) { Log.e(TAG, "P2P Peer Discovery failed: $reasonCode") }
        })
    }

    @Throws(SecurityException::class)
    private fun connectToWifiPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        wifiP2pManager?.connect(wifiP2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Initiated connection to Wi-Fi Direct peer ${device.deviceAddress}") }
            override fun onFailure(reason: Int) { Log.d(TAG, "Failed connecting to peer: $reason") }
        })
    }

    private fun startWifiDirectServer() {
        p2pScope.launch {
            try {
                if (serverSocket == null) {
                    serverSocket = ServerSocket(8888)
                }
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    Log.d(TAG, "Accepted TCP client connection from ${client.inetAddress.hostAddress}")
                    handleSocketConnection(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error", e)
            }
        }
    }

    private fun connectWifiDirectSocket(ownerIp: String?) {
        if (ownerIp == null) return
        p2pScope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ownerIp, 8888), 5000)
                Log.d(TAG, "Connected TCP socket to Server Group Owner")
                // A true mesh allows bidirectional transmission:
                handleSocketConnection(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Client socket error", e)
            }
        }
    }

    private fun handleSocketConnection(socket: Socket) {
        activeSockets.add(socket)
        socketWriteLocks[socket] = Any()
        sendHandshakeViaTcp(socket)
        p2pScope.launch {
            val input: InputStream = socket.getInputStream()
            val buffer = ByteArray(8192)
            try {
                while (isRunning) {
                    val len = input.read(buffer)
                    if (len == -1) break
                    
                    val transportBytes = buffer.copyOfRange(0, len)
                    onReceivedBytes(socket.inetAddress.hostAddress ?: "wifi-direct-peer", transportBytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket read error: ${e.message}")
            } finally {
                val ip = socket.inetAddress.hostAddress
                val disconnectedPeer = if (ip != null) clearRoute(ip) else null
                if (disconnectedPeer != null) {
                    activeCore()?.meshPeerDisconnected(disconnectedPeer)
                }
                activeSockets.remove(socket)
                socketWriteLocks.remove(socket)
                try { socket.close() } catch(e: Exception) {}
            }
        }
    }
}
