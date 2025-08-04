package com.gabriel.mobile.presentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.mobile.BuildConfig
import com.gabriel.mobile.service.DataLayerListenerService
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val messageClient by lazy { Wearable.getMessageClient(application) }
    private val nodeClient by lazy { Wearable.getNodeClient(application) }
    private val networkClient = OkHttpClient()
    private val serverUrl = "${BuildConfig.SERVER_URL}/data"

    // Buffer para enviar dados em lote para o servidor
    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val BATCH_SIZE = 25

    private val _status = MutableStateFlow("Aguardando dados...")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _sensorDataPoints = MutableStateFlow<List<SensorDataPoint>>(emptyList())
    val sensorDataPoints = _sensorDataPoints.asStateFlow()

    private val _exportCsvEvent = MutableSharedFlow<String>()
    val exportCsvEvent = _exportCsvEvent.asSharedFlow()

    private val maxDataPointsForChart = 100
    private val dataQueue = ArrayDeque<SensorDataPoint>(maxDataPointsForChart)
    private var statusUpdateJob: Job? = null

    // --- MUDANÇA: Buffer para otimizar as atualizações da UI ---
    private val uiUpdateBuffer = mutableListOf<SensorDataPoint>()
    private val UI_UPDATE_BATCH_SIZE = 25 // Atualiza a UI a cada x amostras (10x por segundo a 50Hz)
    // --- Fim da Mudança ---

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dataPoint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_DATA, SensorDataPoint::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_DATA) as? SensorDataPoint
            }

            dataPoint?.let {
                statusUpdateJob?.cancel()
                _status.value = "Recebendo dados..."

                // Adiciona o ponto à fila de dados para o gráfico
                synchronized(dataQueue) {
                    if (dataQueue.size >= maxDataPointsForChart) {
                        dataQueue.removeFirst()
                    }
                    dataQueue.addLast(it)
                }

                // Adiciona o ponto ao buffer de envio para o servidor
                synchronized(dataBuffer) {
                    dataBuffer.add(it)
                    if (dataBuffer.size >= BATCH_SIZE) {
                        sendBatchToServer(ArrayList(dataBuffer))
                        dataBuffer.clear()
                    }
                }

                // --- MUDANÇA: Lógica de atualização da UI em lotes ---
                synchronized(uiUpdateBuffer) {
                    uiUpdateBuffer.add(it)
                    if (uiUpdateBuffer.size >= UI_UPDATE_BATCH_SIZE) {
                        // Atingiu o tamanho do lote, atualiza a UI com a janela de dados mais recente
                        _sensorDataPoints.value = dataQueue.toList()
                        uiUpdateBuffer.clear() // Limpa o buffer para o próximo lote
                    }
                }
                // --- Fim da Mudança ---

                statusUpdateJob = viewModelScope.launch {
                    delay(3000L)
                    _status.value = "Parado"
                }
            }
        }
    }

    init {
        val intentFilter = IntentFilter(DataLayerListenerService.ACTION_RECEIVE_SENSOR_DATA)
        LocalBroadcastManager.getInstance(application).registerReceiver(sensorDataReceiver, intentFilter)
        checkConnection()
    }

    private fun sendBatchToServer(batch: List<SensorDataPoint>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray()
                batch.forEach { dataPoint ->
                    val jsonObject = JSONObject().apply {
                        put("timestamp", dataPoint.timestamp)
                        put("x", dataPoint.values[0])
                        put("y", dataPoint.values[1])
                        put("z", dataPoint.values[2])
                    }
                    jsonArray.put(jsonObject)
                }

                val requestBody = jsonArray.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(serverUrl)
                    .post(requestBody)
                    .build()

                networkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("MainViewModel", "Falha ao enviar lote: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Erro de rede ao enviar lote", e)
            }
        }
    }

    fun checkConnection() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            _isConnected.value = nodes.any { it.isNearby }
        }
    }

    fun exportDataToCsv() {
        viewModelScope.launch {
            val data = _sensorDataPoints.value
            if (data.isEmpty()) {
                _status.value = "Nenhum dado para exportar."
                return@launch
            }
            val stringBuilder = StringBuilder().apply {
                append("timestamp,x,y,z\n")
                data.forEach { dp ->
                    append("${dp.timestamp},${dp.values[0]},${dp.values[1]},${dp.values[2]}\n")
                }
            }
            _exportCsvEvent.emit(stringBuilder.toString())
        }
    }

    fun sendPingToWatch() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.filter { it.isNearby }.forEach { node ->
                messageClient.sendMessage(node.id, DataLayerConstants.PING_PATH, "PING".toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        statusUpdateJob?.cancel()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(sensorDataReceiver)
    }
}
