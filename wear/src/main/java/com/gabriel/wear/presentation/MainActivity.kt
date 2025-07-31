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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.gabriel.wear.presentation.theme.MonitorParkinsonAppTheme
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MonitorParkinsonAppTheme {
                WearAppRoot()
            }
        }
    }
}

@Composable
private fun WearAppRoot() {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isConnected by remember { mutableStateOf(false) }
    val nodeClient = Wearable.getNodeClient(context)

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                val nearbyNodes = nodes.filter { it.isNearby }
                isConnected = nearbyNodes.isNotEmpty()

                if (nearbyNodes.isNotEmpty()) {
                    val nodeInfo = nearbyNodes.joinToString(", ") { node ->
                        "${node.displayName} (ID: ${node.id})"
                    }
                    Log.d("WearAppRoot", "Conectado ao(s) nó(s): $nodeInfo")
                } else {
                    Log.d("WearAppRoot", "Nenhum nó próximo conectado.")
                }

            } catch (e: Exception) {
                isConnected = false
                Log.e("WearAppRoot", "Falha ao verificar nós conectados", e)
            }
            delay(5000) // Espera 5 segundos
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )

    Scaffold(
        timeText = { TimeText(modifier = Modifier.padding(top = 8.dp)) }
    ) {
        if (hasPermission) {
            ControlScreen(isConnected = isConnected)
        } else {
            RequestPermissionScreen(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.BODY_SENSORS) }
            )
        }
    }
}

@Composable
fun ControlScreen(isConnected: Boolean) {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(false) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        item {
            Text(
                text = "Monitoramento",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )
        }
        item {
            Text(
                text = if (isConnected) "Conectado" else "Desconectado",
                color = if (isConnected) Color.Green else Color.Red,
                style = MaterialTheme.typography.caption1
            )
        }
        item {
            Text(
                text = if (isServiceRunning) "Status: Coletando" else "Status: Parado",
                style = MaterialTheme.typography.caption2
            )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item {
            Button(
                onClick = {
                    Intent(context, SensorService::class.java).also {
                        it.action = SensorService.ACTION_START
                        context.startService(it)
                    }
                    isServiceRunning = true
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                enabled = !isServiceRunning
            ) { Text("Iniciar") }
        }
        item {
            Button(
                onClick = {
                    Intent(context, SensorService::class.java).also {
                        it.action = SensorService.ACTION_STOP
                        context.startService(it)
                    }
                    isServiceRunning = false
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                enabled = isServiceRunning
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
