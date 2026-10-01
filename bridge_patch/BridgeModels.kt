package com.vaanhanma.punchvision.bridge

enum class BridgeScanPhase {
    IDLE,
    SCANNING,
    COMPLETE,
    ERROR,
}

data class BridgePeripheral(
    val address: String,
    val name: String,
    val transport: String,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerData: List<String>,
    val payloadPreview: String,
    val seenCount: Int,
    val changedPackets: Int,
    val lastSeenEpochMs: Long,
    val likelyFightCamp: Boolean,
)

data class BridgeEndpoint(
    val key: String,
    val name: String,
    val host: String,
    val port: Int,
    val source: String,
    val likelyFightCamp: Boolean,
)

data class BridgeScanState(
    val phase: BridgeScanPhase = BridgeScanPhase.IDLE,
    val elapsedSeconds: Int = 0,
    val durationSeconds: Int = 30,
    val peripherals: List<BridgePeripheral> = emptyList(),
    val pairedDevices: List<String> = emptyList(),
    val endpoints: List<BridgeEndpoint> = emptyList(),
    val eventCount: Int = 0,
    val packetChanges: Int = 0,
    val logs: List<String> = emptyList(),
    val reportText: String = "",
    val error: String? = null,
)
