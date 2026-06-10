package com.ada.messenger.core

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Pure BLE and Wi-Fi Direct connections for Sneakernet / Local Mesh transport.
 * Bridged with Rust (`ada_receive_mesh_bytes`).
 */
class LocalMeshManager(private val context: Context, private val core: AdaCore?) {

    companion object {
        private const val TAG = "LocalMeshManager"
        private const val MAX_PENDING_PAYLOADS_PER_ROUTE = 32
        private const val DEFAULT_BLE_MTU = 23
        private const val REQUESTED_BLE_MTU = 517
        private const val GATT_WRITE_OVERHEAD = 3
        
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

    private fun updateCore(core: AdaCore?) {
        coreRef = core
    }

    private fun activeCore(): AdaCore? = AdaCoreHolder.instance ?: coreRef

    fun start() {
        if (isRunning) return
        Log.d(TAG, "Starting LocalMeshManager (BLE / Wi-Fi Direct)")
        isRunning = true
        
        try {
            startGattServer()
            startAdvertising()
            startScanning()
            
            startWifiDirect()
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
            stopWifiDirect()
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
        val frame = MeshFrameCodec.encodePayload(payload)
        
        // 1. Send via BLE
        connectedClients.values.forEach { gatt ->
            if (targetRoute == null || targetRoute == gatt.device.address) {
                try {
                    enqueueBleFrame(gatt, frame)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied writing characteristic", e)
                }
            }
        }
        
        // 2. Send via Wi-Fi Direct TCP Sockets
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
