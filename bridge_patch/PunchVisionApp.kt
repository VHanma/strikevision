package com.vaanhanma.punchvision.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsMma
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vaanhanma.punchvision.viewmodel.PunchViewModel

@Composable
fun PunchVisionApp(viewModel: PunchViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.SportsMma, null) },
                    label = { Text("Live") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.History, null) },
                    label = { Text("History") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.BluetoothSearching, null) },
                    label = { Text("Bridge") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Setup") },
                )
            }
        }
    ) { padding ->
        when (tab) {
            0 -> CameraScreen(viewModel, contentPadding = padding)
            1 -> HistoryScreen(viewModel, padding)
            2 -> BridgeScreen(padding)
            else -> SettingsScreen(viewModel, padding)
        }
    }
}
