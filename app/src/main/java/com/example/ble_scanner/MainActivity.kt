package com.example.ble_scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ble_scanner.ui.theme.BLE_scannerTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            BLE_scannerTheme {
                val scannerViewModel: Scanner = viewModel(
                    factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                )
                val connectViewModel: BLEClient = viewModel(
                    factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                )

                Scaffold(
                    topBar = { TopAppBar(title = { Text(text = "BLE Scanner") }) },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                            .fillMaxSize()
                    ) {
                        BleDeviceListScreen(
                            this@MainActivity,
                            scannerViewModel,
                            connectViewModel,
                        )
                    }
                }
            }
        }
    }
}