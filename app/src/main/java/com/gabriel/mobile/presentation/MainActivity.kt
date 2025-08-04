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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gabriel.mobile.ui.theme.MonitorParkinsonAppTheme
import com.gabriel.shared.SensorDataPoint
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.component.shapeComponent
import com.patrykandpatrick.vico.compose.component.textComponent
import com.patrykandpatrick.vico.compose.legend.verticalLegend
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.legend.LegendItem
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
                val context = LocalContext.current
                var csvContentToSave by remember { mutableStateOf<String?>(null) }

                val fileSaverLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv"),
                    onResult = { uri ->
                        uri?.let { fileUri ->
                            csvContentToSave?.let { content ->
                                context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                                    outputStream.write(content.toByteArray())
                                }
                                csvContentToSave = null
                            }
                        }
                    }
                )

                LaunchedEffect(Unit) {
                    viewModel.exportCsvEvent.collectLatest { csvContent ->
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val fileName = "parkinson_data_$timestamp.csv"
                        csvContentToSave = csvContent
                        fileSaverLauncher.launch(fileName)
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
                    onExportClicked = { viewModel.exportDataToCsv() }
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
    onExportClicked: () -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Dados", "Gráfico")

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopBarContent(status, isConnected, onPingClicked, onExportClicked)
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
    onExportClicked: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Monitor de Parkinson", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(text = if (isConnected) "Relógio Conectado" else "Relógio Desconectado", style = MaterialTheme.typography.bodyLarge, color = if (isConnected) Color(0xFF4CAF50) else Color.Red)
        Text(text = "Status: $status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPingClicked) { Text("Ping Relógio") }
            Button(onClick = onExportClicked) { Text("Exportar CSV") }
        }
    }
}

@Composable
fun DataListScreen(dataPoints: List<SensorDataPoint>) {
    if (dataPoints.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nenhum dado recebido ainda.")
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), reverseLayout = true) {
            items(dataPoints) { dataPoint -> DataPointCard(dataPoint = dataPoint) }
        }
    }
}

@Composable
fun RealTimeChartScreen(dataPoints: List<SensorDataPoint>) {
    val modelProducer = remember { ChartEntryModelProducer() }

    LaunchedEffect(dataPoints) {
        if (dataPoints.isNotEmpty()) {
            val windowedData = dataPoints.takeLast(100)
            val xData = windowedData.mapIndexed { index, _ -> index.toFloat() }
            val yDataX = windowedData.map { it.values[0] }
            val yDataY = windowedData.map { it.values[1] }
            val yDataZ = windowedData.map { it.values[2] }
            modelProducer.setEntries(xData.zip(yDataX, ::FloatEntry), xData.zip(yDataY, ::FloatEntry), xData.zip(yDataZ, ::FloatEntry))
        }
    }

    if (dataPoints.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Aguardando dados para exibir o gráfico.")
        }
    } else {
        val colorX = Color.Red
        val colorY = Color.Green
        val colorZ = Color.Blue

        // --- MUDANÇA: O Column e os botões de escala foram removidos ---
        Chart(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            chart = lineChart(
                lines = listOf(
                    LineChart.LineSpec(lineColor = colorX.toArgb()),
                    LineChart.LineSpec(lineColor = colorY.toArgb()),
                    LineChart.LineSpec(lineColor = colorZ.toArgb())
                )
            ),
            chartModelProducer = modelProducer,
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
            legend = verticalLegend(
                items = listOf(
                    LegendItem(icon = shapeComponent(Shapes.pillShape, colorX), label = textComponent(color = MaterialTheme.colorScheme.onSurface), labelText = "Eixo X"),
                    LegendItem(icon = shapeComponent(Shapes.pillShape, colorY), label = textComponent(color = MaterialTheme.colorScheme.onSurface), labelText = "Eixo Y"),
                    LegendItem(icon = shapeComponent(Shapes.pillShape, colorZ), label = textComponent(color = MaterialTheme.colorScheme.onSurface), labelText = "Eixo Z")
                ),
                iconSize = 8.dp, iconPadding = 8.dp, spacing = 4.dp
            )
        )
    }
}

@Composable
fun DataPointCard(dataPoint: SensorDataPoint) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            val (x, y, z) = dataPoint.values
            Text(text = "Eixo X: ${"%.3f".format(x)}")
            Text(text = "Eixo Y: ${"%.3f".format(y)}")
            Text(text = "Eixo Z: ${"%.3f".format(z)}")
            Text(text = "Timestamp: ${dataPoint.timestamp}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
