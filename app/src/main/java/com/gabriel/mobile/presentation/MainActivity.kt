@file:OptIn(ExperimentalMaterial3Api::class)

package com.gabriel.mobile.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
                val isSessionActive by viewModel.isSessionActive.collectAsState()

                if (isSessionActive) {
                    MonitoringScreen(viewModel)
                } else {
                    PatientManagementScreen(viewModel)
                }
            }
        }
    }
}

@ExperimentalMaterial3Api
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientManagementScreen(viewModel: MainViewModel) {
    val patients by viewModel.patients.collectAsState()
    val selectedPatient by viewModel.selectedPatient.collectAsState()
    var showAddPatientDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Gestão de Pacientes") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddPatientDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Paciente")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (patients.isEmpty()) {
                Text("Nenhum paciente registado. Adicione um para começar.")
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(patients) { patient ->
                        PatientItem(
                            patient = patient,
                            isSelected = patient.id == selectedPatient?.id,
                            onSelect = { viewModel.selectPatient(patient) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { viewModel.startSession() },
                enabled = selectedPatient != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedPatient != null) "Iniciar Sessão para ${selectedPatient?.name}" else "Selecione um Paciente")
            }
        }
    }

    if (showAddPatientDialog) {
        AddPatientDialog(
            onDismiss = { showAddPatientDialog = false },
            onAddPatient = { name ->
                viewModel.addPatient(name)
                showAddPatientDialog = false
            }
        )
    }
}

@Composable
fun PatientItem(patient: Patient, isSelected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = patient.name,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun AddPatientDialog(onDismiss: () -> Unit, onAddPatient: (String) -> Unit) {
    var patientName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Adicionar Novo Paciente", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = patientName,
                    onValueChange = { patientName = it },
                    label = { Text("Nome do Paciente") }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onAddPatient(patientName) },
                        enabled = patientName.isNotBlank()
                    ) {
                        Text("Adicionar")
                    }
                }
            }
        }
    }
}


@Composable
fun MonitoringScreen(viewModel: MainViewModel) {
    val status by viewModel.status.collectAsState()
    val dataPoints by viewModel.sensorDataPoints.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Dados", "Gráfico")

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                MonitoringTopBar(
                    status = status,
                    isConnected = isConnected,
                    onStopSession = { viewModel.stopSession() }
                )
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
fun MonitoringTopBar(status: String, isConnected: Boolean, onStopSession: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Monitoramento Ativo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(text = if (isConnected) "Relógio Conectado" else "Relógio Desconectado", style = MaterialTheme.typography.bodyLarge, color = if (isConnected) Color(0xFF4CAF50) else Color.Red)
        Text(text = "Status: $status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onStopSession, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("Parar Sessão")
        }
    }
}

@Composable
fun DataListScreen(dataPoints: List<SensorDataPoint>) {
    if (dataPoints.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum dado recebido ainda.") }
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
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Aguardando dados para exibir o gráfico.") }
    } else {
        val colorX = Color.Red
        val colorY = Color.Green
        val colorZ = Color.Blue

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
