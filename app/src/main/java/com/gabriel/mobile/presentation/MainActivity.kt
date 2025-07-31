package com.gabriel.mobile.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gabriel.mobile.ui.theme.MonitorParkinsonAppTheme
import com.gabriel.shared.SensorDataPoint
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MonitorParkinsonAppTheme {
                val status by viewModel.status.collectAsState()
                val dataPoints by viewModel.sensorDataPoints.collectAsState()
                val isConnected by viewModel.isConnected.collectAsState()

                // MUDANÇA: Launcher para salvar o arquivo
                val context = LocalContext.current
                val fileSaverLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv"),
                    onResult = { uri ->
                        uri?.let {
                            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                                // O conteúdo CSV será escrito aqui
                            }
                        }
                    }
                )

                // MUDANÇA: Ouve os eventos de exportação do ViewModel
                LaunchedEffect(Unit) {
                    viewModel.exportCsvEvent.collectLatest { csvContent ->
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val fileName = "parkinson_data_$timestamp.csv"
                        fileSaverLauncher.launch(fileName)

                        // Re-lança o launcher com o conteúdo para ser escrito no callback
                        val newFileSaver = registerForActivityResult(
                            ActivityResultContracts.CreateDocument("text/csv")
                        ) { uri ->
                            uri?.let {
                                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                                    outputStream.write(csvContent.toByteArray())
                                }
                            }
                        }
                        newFileSaver.launch(fileName)
                    }
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        viewModel.checkConnection()
                        delay(5000)
                    }
                }

                MainScreen(
                    status = status,
                    dataPoints = dataPoints,
                    isConnected = isConnected,
                    onPingClicked = { viewModel.sendPingToWatch() },
                    onExportClicked = { viewModel.exportDataToCsv() } // MUDANÇA
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    status: String,
    dataPoints: List<SensorDataPoint>,
    isConnected: Boolean,
    onPingClicked: () -> Unit,
    onExportClicked: () -> Unit // MUDANÇA
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Dados", "Gráfico")

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopBarContent(status, isConnected, onPingClicked, onExportClicked) // MUDANÇA
                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            when (selectedTabIndex) {
                0 -> DataListScreen(dataPoints = dataPoints)
                1 -> RealTimeChartScreen(dataPoints = dataPoints)
            }
        }
    }
}

@Composable
fun TopBarContent(
    status: String,
    isConnected: Boolean,
    onPingClicked: () -> Unit,
    onExportClicked: () -> Unit // MUDANÇA
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Monitor de Parkinson",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        // ... (Textos de status existentes)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPingClicked) {
                Text("Ping Relógio")
            }
            // MUDANÇA: Novo botão de exportação
            Button(onClick = onExportClicked) {
                Text("Exportar CSV")
            }
        }
    }
}

// ... (O resto dos seus Composables: DataListScreen, RealTimeChartScreen, DataPointCard)
// Nenhuma mudança necessária neles.
@Composable
fun DataListScreen(dataPoints: List<SensorDataPoint>) {
    if (dataPoints.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nenhum dado recebido ainda.")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            reverseLayout = true
        ) {
            items(dataPoints) { dataPoint ->
                DataPointCard(dataPoint = dataPoint)
            }
        }
    }
}

@Composable
fun RealTimeChartScreen(dataPoints: List<SensorDataPoint>) {
    val modelProducer = remember { ChartEntryModelProducer() }

    // Este LaunchedEffect atualiza o gráfico sempre que novos dados chegam.
    LaunchedEffect(dataPoints) {
        if (dataPoints.isNotEmpty()) {
            val xData = dataPoints.mapIndexed { index, _ -> index.toFloat() }
            val yDataX = dataPoints.map { it.values[0] }
            val yDataY = dataPoints.map { it.values[1] }
            val yDataZ = dataPoints.map { it.values[2] }

            modelProducer.setEntries(
                xData.zip(yDataX, ::FloatEntry), // Eixo X
                xData.zip(yDataY, ::FloatEntry), // Eixo Y
                xData.zip(yDataZ, ::FloatEntry)  // Eixo Z
            )
        }
    }

    if (dataPoints.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Aguardando dados para exibir o gráfico.")
        }
    } else {
        Chart(
            chart = lineChart(),
            chartModelProducer = modelProducer,
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
            modifier = Modifier.fillMaxSize().padding(16.dp)
        )
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
            Text(text = "Eixo X: ${"%.3f".format(x)}")
            Text(text = "Eixo Y: ${"%.3f".format(y)}")
            Text(text = "Eixo Z: ${"%.3f".format(z)}")
            Text(
                text = "Timestamp: ${dataPoint.timestamp}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

