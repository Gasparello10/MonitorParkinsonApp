package com.gabriel.wear.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.gabriel.wear.presentation.theme.MonitorParkinsonAppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MonitorParkinsonAppTheme { // This is a @Composable function
                WearAppRoot()
            }
        }
    }
}


@Composable
private fun WearAppRoot() {
    val context =   LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(), // Apply fillMaxSize to Scaffold
        timeText = { TimeText(modifier = Modifier.padding(top = 8.dp)) }
    ) {
        if (hasPermission) {
            ControlScreen()
        } else {
            RequestPermissionScreen(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.BODY_SENSORS) }
            )
        }
    }
}

@Composable
fun ControlScreen() {
    val context = LocalContext.current
    val isMonitoring by MonitoringStateHolder.isMonitoring.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        item { Text("Monitoramento", style = MaterialTheme.typography.title3) }
        item {
            Text(
                text = if (isMonitoring) "Status: Enviando dados..." else "Status: Parado",
                color = if (isMonitoring) Color.Green else Color.White
            )
        }
        item {
            Button(
                onClick = {
                    Log.d("MainActivity", "Botão INICIAR clicado.")
                    Intent(context, SensorService::class.java).also {
                        it.action = SensorService.ACTION_START
                        ContextCompat.startForegroundService(context, it)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                enabled = !isMonitoring
            ) { Text("Iniciar") }
        }
        item {
            Button(
                onClick = {
                    Log.d("MainActivity", "Botão PARAR clicado.")
                    Intent(context, SensorService::class.java).also {
                        it.action = SensorService.ACTION_STOP
                        context.startService(it)
                    }
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                enabled = isMonitoring
            ) { Text("Parar") }
        }
    }
}

@Composable
fun RequestPermissionScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Permissão Necessária",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.title3
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "O acesso aos sensores é vital para monitorar os tremores.",
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) { Text("Conceder") }
    }
}
