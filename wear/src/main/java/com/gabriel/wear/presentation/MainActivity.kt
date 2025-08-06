package com.gabriel.wear.presentation

import android.Manifest
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
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
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
                context, Manifest.permission.HIGH_SAMPLING_RATE_SENSORS // Permissão correta
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )

    // Pede a permissão assim que a app é iniciada, se ainda não a tiver
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
        }
    }

    Scaffold(
        timeText = { TimeText(modifier = Modifier.padding(top = 8.dp)) }
    ) {
        if (hasPermission) {
            StatusScreen()
        } else {
            RequestPermissionScreen(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS) }
            )
        }
    }
}

@Composable
fun StatusScreen() {
    val context = LocalContext.current
    var isConnected by remember { mutableStateOf(false) }
    val nodeClient = Wearable.getNodeClient(context)

    // Verifica a ligação com o celular periodicamente
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                isConnected = nodes.any { it.isNearby }
            } catch (e: Exception) {
                isConnected = false
                Log.e("WearAppRoot", "Falha ao verificar nós conectados", e)
            }
            delay(5000) // Espera 5 segundos
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Monitor de Parkinson",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isConnected) "Conectado" else "Desconectado",
            color = if (isConnected) Color.Green else Color.Red,
            style = MaterialTheme.typography.caption1
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Monitoramento controlado remotamente.",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center
        )
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
            text = "O acesso aos sensores é necessário para monitorizar os tremores.",
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) { Text("Conceder") }
    }
}
