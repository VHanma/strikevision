package com.vaanhanma.punchvision.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vaanhanma.punchvision.bridge.BridgeEndpoint
import com.vaanhanma.punchvision.bridge.BridgePeripheral
import com.vaanhanma.punchvision.bridge.BridgeScanPhase
import com.vaanhanma.punchvision.bridge.FightCampBridgeScanner

@Composable
fun BridgeScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val scanner = remember { FightCampBridgeScanner(context) }
    val state by scanner.state.collectAsState()
    var message by remember { mutableStateOf("") }

    val requiredPermissions = remember { bridgePermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allowed = requiredPermissions.all { permission ->
            grants[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (allowed) {
            message = "Scanning Console signals for 30 seconds."
            scanner.startScan()
        } else {
            message = "Nearby-device permission is needed for the Bluetooth part of the scan."
        }
    }

    DisposableEffect(scanner) {
        onDispose { scanner.close() }
    }

    fun beginScan() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else {
            message = "Scanning Console signals for 30 seconds."
            scanner.startScan()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("FIGHTCAMP CONSOLE BRIDGE", fontWeight = FontWeight.Black, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Turn on the Console and start a workout. Tap Scan, then throw a 1–2, hooks, and a few kicks while the timer runs.",
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The scan stays passive. It records Bluetooth advertisements and local-network services without stealing the trackers from the Console.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
            }
        }

        item {
            val progress = if (state.durationSeconds > 0) {
                state.elapsedSeconds.toFloat() / state.durationSeconds.toFloat()
            } else 0f
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        when (state.phase) {
                            BridgeScanPhase.IDLE -> "Ready"
                            BridgeScanPhase.SCANNING -> "Scanning ${state.elapsedSeconds}/${state.durationSeconds} seconds"
                            BridgeScanPhase.COMPLETE -> "Scan complete"
                            BridgeScanPhase.ERROR -> "Scan error"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.phase == BridgeScanPhase.SCANNING) {
                        OutlinedButton(onClick = scanner::stopScan, modifier = Modifier.fillMaxWidth()) {
                            Text("Stop and save scan")
                        }
                    } else {
                        Button(onClick = ::beginScan, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.phase == BridgeScanPhase.COMPLETE) "Scan again" else "Scan for FightCamp Console")
                        }
                    }
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("BLE", state.peripherals.size.toString(), Modifier.weight(1f))
                StatCard("CHANGES", state.packetChanges.toString(), Modifier.weight(1f))
                StatCard("NETWORK", state.endpoints.size.toString(), Modifier.weight(1f))
            }
        }

        if (state.reportText.isNotBlank()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { shareReport(context, state.reportText) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Share report") }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("FightCamp bridge scan", state.reportText))
                            message = "Report copied."
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy report") }
                }
            }
        }

        item { SectionTitle("Possible FightCamp signals") }
        val likelyPeripherals = state.peripherals.filter { it.likelyFightCamp }
        val likelyEndpoints = state.endpoints.filter { it.likelyFightCamp }
        if (likelyPeripherals.isEmpty() && likelyEndpoints.isEmpty()) {
            item {
                Text(
                    if (state.phase == BridgeScanPhase.COMPLETE) {
                        "Nothing identified by name yet. The full lists below still contain anonymous devices that may be the Console."
                    } else {
                        "Possible matches will appear here during the scan."
                    }
                )
            }
        } else {
            items(likelyPeripherals, key = { "likely-ble-${it.address}" }) { PeripheralCard(it) }
            items(likelyEndpoints, key = { "likely-net-${it.key}" }) { EndpointCard(it) }
        }

        item { SectionTitle("Bluetooth advertisements") }
        if (state.peripherals.isEmpty()) {
            item { Text("No Bluetooth signals recorded yet.") }
        } else {
            items(state.peripherals, key = { it.address }) { PeripheralCard(it) }
        }

        item { SectionTitle("Local-network services") }
        if (state.endpoints.isEmpty()) {
            item { Text("No local-network services recorded yet.") }
        } else {
            items(state.endpoints, key = { it.key }) { EndpointCard(it) }
        }

        item { SectionTitle("Scanner log") }
        items(state.logs.take(16)) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB300))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
}

@Composable
private fun PeripheralCard(item: BridgePeripheral) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                item.name + if (item.likelyFightCamp) "  • POSSIBLE MATCH" else "",
                fontWeight = FontWeight.Bold,
            )
            Text("${item.transport}  •  ${item.address}  •  ${item.rssi} dBm")
            Text(
                "Seen ${item.seenCount} times  •  ${item.changedPackets} payload changes",
                style = MaterialTheme.typography.bodySmall,
            )
            if (item.serviceUuids.isNotEmpty()) {
                Text("UUID: ${item.serviceUuids.joinToString().take(180)}", style = MaterialTheme.typography.bodySmall)
            }
            if (item.manufacturerData.isNotEmpty()) {
                Text("Maker data: ${item.manufacturerData.joinToString().take(180)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EndpointCard(item: BridgeEndpoint) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                item.name + if (item.likelyFightCamp) "  • POSSIBLE MATCH" else "",
                fontWeight = FontWeight.Bold,
            )
            Text("${item.host}:${item.port}")
            Text(item.source, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun bridgePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun shareReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "PunchVision FightCamp Console bridge scan")
        putExtra(Intent.EXTRA_TEXT, report)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Send bridge report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
