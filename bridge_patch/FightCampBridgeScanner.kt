package com.vaanhanma.punchvision.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class FightCampBridgeScanner(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)

    private val _state = MutableStateFlow(BridgeScanState())
    val state: StateFlow<BridgeScanState> = _state.asStateFlow()

    private val lock = Any()
    private val peripherals = LinkedHashMap<String, BridgePeripheral>()
    private val lastPayloads = HashMap<String, String>()
    private val endpoints = LinkedHashMap<String, BridgeEndpoint>()
    private val logs = ArrayDeque<String>()
    private val pairedDevices = mutableListOf<String>()
    private val lastPublish = AtomicLong(0L)

    @Volatile
    private var scanning = false
    private var scanJob: Job? = null
    private var startedAt = 0L
    private var requestedDurationSeconds = 30
    private var activeNsdListener: NsdManager.DiscoveryListener? = null
    private var classicReceiverRegistered = false

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val address = runCatching { device.address }.getOrNull() ?: "unknown"
                    val name = if (hasBleConnectPermission()) runCatching { device.name }.getOrNull() else null
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val likely = isFightCampLike(name.orEmpty())
                    synchronized(lock) {
                        val previous = peripherals[address]
                        peripherals[address] = BridgePeripheral(
                            address = address,
                            name = name ?: "Unnamed classic device",
                            transport = "Bluetooth Classic",
                            rssi = rssi,
                            serviceUuids = previous?.serviceUuids.orEmpty(),
                            manufacturerData = previous?.manufacturerData.orEmpty(),
                            payloadPreview = previous?.payloadPreview.orEmpty(),
                            seenCount = (previous?.seenCount ?: 0) + 1,
                            changedPackets = previous?.changedPackets ?: 0,
                            lastSeenEpochMs = System.currentTimeMillis(),
                            likelyFightCamp = likely || previous?.likelyFightCamp == true,
                        )
                    }
                    publishSnapshot()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> addLog("Classic Bluetooth discovery finished.")
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = processBleResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::processBleResult)
        override fun onScanFailed(errorCode: Int) = addLog("BLE scan error $errorCode")
    }

    fun startScan(durationSeconds: Int = 30) {
        if (scanning) return
        requestedDurationSeconds = durationSeconds.coerceIn(15, 90)
        synchronized(lock) {
            peripherals.clear()
            lastPayloads.clear()
            endpoints.clear()
            logs.clear()
            pairedDevices.clear()
        }
        scanning = true
        startedAt = System.currentTimeMillis()
        _state.value = BridgeScanState(
            phase = BridgeScanPhase.SCANNING,
            durationSeconds = requestedDurationSeconds,
        )
        addLog("Scan started. Keep the FightCamp Console on and begin a workout.")
        readPairedDevices()
        startBleScan()
        startClassicDiscovery()

        scanJob = scope.launch {
            coroutineScope {
                launch { discoverNsdServices() }
                launch { discoverSsdpDevices() }
                launch { probeLocalSubnet() }
                while (scanning && elapsedSeconds() < requestedDurationSeconds) {
                    publishSnapshot(force = true)
                    delay(500)
                }
            }
            finishScan()
        }
    }

    fun stopScan() {
        if (!scanning) return
        addLog("Stop requested.")
        scanning = false
        stopBleScan()
        stopClassicDiscovery()
        stopNsdDiscovery()
    }

    fun latestReportFile(): File = File(appContext.filesDir, REPORT_FILE)

    override fun close() {
        scanning = false
        stopBleScan()
        stopClassicDiscovery()
        stopNsdDiscovery()
        scanJob?.cancel()
        scope.cancel()
    }

    private fun elapsedSeconds(): Int =
        ((System.currentTimeMillis() - startedAt) / 1_000L).toInt().coerceAtLeast(0)

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!hasBleScanPermission()) {
            addLog("Nearby-device permission is missing; BLE scan skipped.")
            return
        }
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            addLog("This phone has no Bluetooth adapter.")
            return
        }
        if (!adapter.isEnabled) {
            addLog("Bluetooth is off. Turn it on and scan again.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            addLog("BLE scanner is unavailable.")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        runCatching { scanner.startScan(null, settings, bleCallback) }
            .onSuccess { addLog("Passive BLE advertisement scan active.") }
            .onFailure { addLog("BLE start failed: ${it.javaClass.simpleName}") }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!hasBleScanPermission()) return
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(bleCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        if (!hasBleScanPermission()) return
        val adapter = bluetoothManager?.adapter ?: return
        if (!classicReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(classicReceiver, filter)
            }
            classicReceiverRegistered = true
        }
        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        }.onSuccess { addLog("Classic Bluetooth discovery active.") }
            .onFailure { addLog("Classic discovery failed: ${it.javaClass.simpleName}") }
    }

    @SuppressLint("MissingPermission")
    private fun stopClassicDiscovery() {
        if (hasBleScanPermission()) runCatching { bluetoothManager?.adapter?.cancelDiscovery() }
        if (classicReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(classicReceiver) }
            classicReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun processBleResult(result: ScanResult) {
        if (!scanning) return
        val record = result.scanRecord
        val address = runCatching { result.device.address }.getOrNull() ?: "unknown"
        val name = record?.deviceName
            ?: if (hasBleConnectPermission()) runCatching { result.device.name }.getOrNull() else null
            ?: "Unnamed BLE device"
        val serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid.toString() }.sorted()
        val manufacturer = buildList {
            val data = record?.manufacturerSpecificData
            if (data != null) {
                for (index in 0 until data.size()) {
                    val id = data.keyAt(index)
                    val bytes = data.valueAt(index)
                    add("0x${id.toString(16).uppercase(Locale.US)}:${bytes.toHex(48)}")
                }
            }
        }
        val payload = record?.bytes?.toHex(96).orEmpty()
        val likely = isFightCampLike(name, serviceUuids.joinToString(), manufacturer.joinToString())
        synchronized(lock) {
            val previous = peripherals[address]
            val changed = if (lastPayloads[address] != null && lastPayloads[address] != payload) 1 else 0
            lastPayloads[address] = payload
            peripherals[address] = BridgePeripheral(
                address = address,
                name = name,
                transport = "BLE advertisement",
                rssi = result.rssi,
                serviceUuids = serviceUuids,
                manufacturerData = manufacturer,
                payloadPreview = payload,
                seenCount = (previous?.seenCount ?: 0) + 1,
                changedPackets = (previous?.changedPackets ?: 0) + changed,
                lastSeenEpochMs = System.currentTimeMillis(),
                likelyFightCamp = likely,
            )
        }
        publishSnapshot()
    }

    @SuppressLint("MissingPermission")
    private fun readPairedDevices() {
        if (!hasBleConnectPermission()) return
        val devices = runCatching { bluetoothManager?.adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        synchronized(lock) {
            pairedDevices.clear()
            pairedDevices += devices.map { device ->
                val name = runCatching { device.name }.getOrNull() ?: "Unnamed paired device"
                val address = runCatching { device.address }.getOrNull() ?: "unknown"
                "$name | $address"
            }.sorted()
        }
        addLog("Paired Bluetooth devices: ${devices.size}")
    }

    private suspend fun discoverNsdServices() {
        val serviceTypes = listOf(
            "_http._tcp.",
            "_https._tcp.",
            "_googlecast._tcp.",
            "_companion-link._tcp.",
            "_fightcamp._tcp.",
        )
        for (type in serviceTypes) {
            if (!scanning) break
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = addLog("mDNS listening: $regType")

                override fun onServiceFound(service: NsdServiceInfo) {
                    val name = service.serviceName ?: "Unnamed service"
                    val serviceType = service.serviceType ?: type
                    addEndpoint(
                        BridgeEndpoint(
                            key = "nsd:$serviceType:$name",
                            name = name,
                            host = "mDNS",
                            port = service.port,
                            source = "NSD $serviceType",
                            likelyFightCamp = isFightCampLike(name, serviceType),
                        )
                    )
                }

                override fun onServiceLost(service: NsdServiceInfo) =
                    addLog("mDNS service lost: ${service.serviceName}")

                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                    addLog("mDNS $serviceType failed: $errorCode")
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) =
                    addLog("mDNS stop failed: $errorCode")
            }
            activeNsdListener = listener
            runCatching { nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { addLog("mDNS unavailable: ${it.javaClass.simpleName}") }
            delay(3_500)
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            activeNsdListener = null
            delay(200)
        }
    }

    private fun stopNsdDiscovery() {
        val listener = activeNsdListener ?: return
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        activeNsdListener = null
    }

    private suspend fun discoverSsdpDevices() {
        val message = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: ssdp:all\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 650
                val destination = InetAddress.getByName("239.255.255.250")
                repeat(3) {
                    if (scanning) socket.send(DatagramPacket(message, message.size, destination, 1900))
                    delay(350)
                }
                val buffer = ByteArray(8_192)
                while (scanning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        val response = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        val server = header(response, "SERVER")
                        val usn = header(response, "USN")
                        val location = header(response, "LOCATION")
                        val name = listOf(server, usn).firstOrNull { it.isNotBlank() } ?: "SSDP device"
                        addEndpoint(
                            BridgeEndpoint(
                                key = "ssdp:${packet.address.hostAddress}:$location",
                                name = name.take(120),
                                host = packet.address.hostAddress ?: "unknown",
                                port = 1900,
                                source = "SSDP ${location.take(120)}",
                                likelyFightCamp = isFightCampLike(name, location),
                            )
                        )
                    }
                }
            }
        }.onFailure { addLog("SSDP scan ended: ${it.javaClass.simpleName}") }
    }

    private suspend fun probeLocalSubnet() {
        val localAddress = localIpv4Address()
        if (localAddress == null) {
            addLog("No Wi-Fi/LAN IPv4 address found; subnet probe skipped.")
            return
        }
        val parts = localAddress.hostAddress?.split('.')
        if (parts == null || parts.size != 4) return
        val prefix = parts.take(3).joinToString(".")
        val own = parts[3].toIntOrNull()
        val ports = listOf(80, 443, 8008, 8009, 8080, 8443, 9000)
        addLog("LAN probe active on $prefix.0/24")
        val gate = Semaphore(40)
        coroutineScope {
            for (last in 1..254) {
                if (!scanning) break
                if (last == own) continue
                launch {
                    gate.withPermit {
                        val host = "$prefix.$last"
                        for (port in ports) {
                            if (!scanning) break
                            if (canConnect(host, port)) {
                                addEndpoint(
                                    BridgeEndpoint(
                                        key = "lan:$host:$port",
                                        name = "Open local service",
                                        host = host,
                                        port = port,
                                        source = "LAN TCP probe",
                                        likelyFightCamp = port in setOf(8008, 8009),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun localIpv4Address(): Inet4Address? {
        val network = connectivityManager.activeNetwork ?: return null
        val properties = connectivityManager.getLinkProperties(network) ?: return null
        return properties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
    }

    private fun canConnect(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 160)
            true
        }
    }.getOrDefault(false)

    private fun addEndpoint(endpoint: BridgeEndpoint) {
        synchronized(lock) { endpoints[endpoint.key] = endpoint }
        publishSnapshot()
    }

    private fun addLog(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lock) {
            logs.addFirst("$stamp  $message")
            while (logs.size > 80) logs.removeLast()
        }
        publishSnapshot()
    }

    private fun publishSnapshot(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish.get() < 250L) return
        lastPublish.set(now)
        val snapshot = synchronized(lock) {
            val peripheralList = peripherals.values.sortedWith(
                compareByDescending<BridgePeripheral> { it.likelyFightCamp }
                    .thenByDescending { it.changedPackets }
                    .thenByDescending { it.rssi }
            )
            val endpointList = endpoints.values.sortedWith(
                compareByDescending<BridgeEndpoint> { it.likelyFightCamp }
                    .thenBy { it.host }
                    .thenBy { it.port }
            )
            BridgeScanState(
                phase = if (scanning) BridgeScanPhase.SCANNING else _state.value.phase,
                elapsedSeconds = elapsedSeconds().coerceAtMost(requestedDurationSeconds),
                durationSeconds = requestedDurationSeconds,
                peripherals = peripheralList,
                pairedDevices = pairedDevices.toList(),
                endpoints = endpointList,
                eventCount = peripheralList.sumOf { it.seenCount },
                packetChanges = peripheralList.sumOf { it.changedPackets },
                logs = logs.toList(),
                reportText = _state.value.reportText,
                error = _state.value.error,
            )
        }
        _state.value = snapshot
    }

    private fun finishScan() {
        if (!scanning && _state.value.phase == BridgeScanPhase.COMPLETE) return
        scanning = false
        stopBleScan()
        stopClassicDiscovery()
        stopNsdDiscovery()
        publishSnapshot(force = true)
        val finishedState = _state.value.copy(phase = BridgeScanPhase.COMPLETE)
        val report = buildReport(finishedState)
        runCatching { latestReportFile().writeText(report) }
            .onFailure { addLog("Could not save report: ${it.javaClass.simpleName}") }
        _state.value = finishedState.copy(reportText = report)
    }

    private fun buildReport(scan: BridgeScanState): String = buildString {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        appendLine("PUNCHVISION FIGHTCAMP CONSOLE BRIDGE SCAN")
        appendLine("Generated: $date")
        appendLine("Duration: ${scan.elapsedSeconds}s")
        appendLine("Mode: passive diagnostic; no tracker or Console connection was forced")
        appendLine()
        appendLine("SUMMARY")
        appendLine("Bluetooth devices: ${scan.peripherals.size}")
        appendLine("Bluetooth events: ${scan.eventCount}")
        appendLine("Changed BLE payloads: ${scan.packetChanges}")
        appendLine("Paired devices: ${scan.pairedDevices.size}")
        appendLine("Network services/endpoints: ${scan.endpoints.size}")
        appendLine()
        appendLine("PAIRED BLUETOOTH DEVICES")
        if (scan.pairedDevices.isEmpty()) appendLine("None visible")
        scan.pairedDevices.forEach { appendLine("- $it") }
        appendLine()
        appendLine("BLUETOOTH SIGNALS")
        if (scan.peripherals.isEmpty()) appendLine("None detected")
        scan.peripherals.forEachIndexed { index, item ->
            appendLine("${index + 1}. ${item.name}${if (item.likelyFightCamp) " [POSSIBLE FIGHTCAMP]" else ""}")
            appendLine("   Transport: ${item.transport}")
            appendLine("   Address: ${item.address}")
            appendLine("   RSSI: ${item.rssi} dBm | Seen: ${item.seenCount} | Payload changes: ${item.changedPackets}")
            appendLine("   Service UUIDs: ${item.serviceUuids.ifEmpty { listOf("none advertised") }.joinToString()}")
            appendLine("   Manufacturer data: ${item.manufacturerData.ifEmpty { listOf("none") }.joinToString()}")
            appendLine("   Payload preview: ${item.payloadPreview.ifBlank { "none" }}")
        }
        appendLine()
        appendLine("LOCAL NETWORK DISCOVERY")
        if (scan.endpoints.isEmpty()) appendLine("None detected")
        scan.endpoints.forEachIndexed { index, item ->
            appendLine("${index + 1}. ${item.name}${if (item.likelyFightCamp) " [POSSIBLE FIGHTCAMP]" else ""}")
            appendLine("   ${item.host}:${item.port} | ${item.source}")
        }
        appendLine()
        appendLine("EVENT LOG")
        scan.logs.asReversed().forEach { appendLine(it) }
    }

    private fun hasBleScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasBleConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isFightCampLike(vararg values: String): Boolean {
        val joined = values.joinToString(" ").lowercase(Locale.US)
        return listOf("fightcamp", "fight camp", "fight-camp", "fc console", "punch tracker").any(joined::contains)
    }

    private fun header(response: String, name: String): String =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()

    private fun ByteArray.toHex(maxChars: Int): String {
        if (isEmpty()) return ""
        val full = joinToString("") { "%02X".format(it) }
        return if (full.length <= maxChars) full else full.take(maxChars) + "…"
    }

    companion object {
        private const val REPORT_FILE = "fightcamp_bridge_scan_latest.txt"
    }
}
