package com.gabriel.mobile.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gabriel.mobile.ui.theme.MonitorParkinsonAppTheme
import com.gabriel.shared.SensorDataPoint
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MonitorParkinsonAppTheme {
                // Coleta os estados do ViewModel para a UI
                val status by viewModel.status.collectAsState()
                val dataPoints by viewModel.sensorDataPoints.collectAsState()
                // MUDANÇA 1: Coleta o novo estado de conexão
                val isConnected by viewModel.isConnected.collectAsState()

                // MUDANÇA 2: Verifica a conexão a cada 5 segundos
                LaunchedEffect(Unit) {
                    while (true) {
                        viewModel.checkConnection()
                        delay(5000) // 5 segundos
                    }
                }

                MainScreen(
                    status = status,
                    dataPoints = dataPoints,
                    isConnected = isConnected
                )
            }
        }
    }
}

@Composable
fun MainScreen(status: String, dataPoints: List<SensorDataPoint>, isConnected: Boolean) {
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Monitor de Parkinson",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                // MUDANÇA 3: Novo Text para o status da conexão
                Text(
                    text = if (isConnected) "Relógio Conectado" else "Relógio Desconectado",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isConnected) Color.Green else Color.Red
                )
                Text(
                    text = "Status dos Dados: $status",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    ) { innerPadding ->
        if (dataPoints.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Nenhum dado recebido ainda.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(dataPoints.reversed()) { dataPoint ->
                    DataPointCard(dataPoint = dataPoint)
                }
            }
        }
    }
}

@Composable
fun DataPointCard(dataPoint: SensorDataPoint) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val (x, y, z) = dataPoint.values
            Text(
                text = "Eixo X: ${"%.3f".format(x)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Eixo Y: ${"%.3f".format(y)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Eixo Z: ${"%.3f".format(z)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Timestamp: ${dataPoint.timestamp}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
