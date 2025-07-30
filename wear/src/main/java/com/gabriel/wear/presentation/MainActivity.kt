package com.gabriel.wear.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
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

    // MUDANÇA: Adiciona estado para a conexão
    var isConnected by remember { mutableStateOf(false) }
    val nodeClient = Wearable.getNodeClient(context)

    // MUDANÇA: Efeito que verifica a conexão a cada 5 segundos
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                isConnected = nodes.isNotEmpty()
            } catch (e: Exception) {
                isConnected = false
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
            // MUDANÇA: Passa o status da conexão para a tela de controle
            ControlScreen(isConnected = isConnected)
        } else {
            RequestPermissionScreen(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.BODY_SENSORS) }
            )
        }
    }
}

@Composable
fun ControlScreen(isConnected: Boolean) { // MUDANÇA: Recebe o status da conexão
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Monitoramento",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))

        // MUDANÇA: Adiciona os textos de status
        Text(
            text = if (isConnected) "Conectado" else "Desconectado",
            color = if (isConnected) Color.Green else Color.Red,
            style = MaterialTheme.typography.caption1
        )
        Text(
            text = if (isServiceRunning) "Status: Coletando" else "Status: Parado",
            style = MaterialTheme.typography.caption2
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                Intent(context, SensorService::class.java).also {
                    it.action = SensorService.ACTION_START
                    context.startService(it)
                }
                isServiceRunning = true
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isServiceRunning
        ) { Text("Iniciar") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                Intent(context, SensorService::class.java).also {
                    it.action = SensorService.ACTION_STOP
                    context.startService(it)
                }
                isServiceRunning = false
            },
            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
            modifier = Modifier.fillMaxWidth(),
            enabled = isServiceRunning
        ) { Text("Parar") }
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
