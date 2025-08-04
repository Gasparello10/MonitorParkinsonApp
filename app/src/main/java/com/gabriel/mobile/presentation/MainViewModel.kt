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

    // --- MUDANÇA: Lógica de Envio em Lotes ---
    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val BATCH_SIZE = 25 // Envia a cada 25 amostras (0.5 segundos a 50Hz)
    // --- Fim da Mudança ---

    private val _status = MutableStateFlow("Aguardando dados...")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _sensorDataPoints = MutableStateFlow<List<SensorDataPoint>>(emptyList())
    val sensorDataPoints = _sensorDataPoints.asStateFlow()

    private val _exportCsvEvent = MutableSharedFlow<String>()
    val exportCsvEvent = _exportCsvEvent.asSharedFlow()

    private val maxDataPoints = 100
    private var statusUpdateJob: Job? = null

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

                val currentList = _sensorDataPoints.value.toMutableList()
                currentList.add(it)
                _sensorDataPoints.value = if (currentList.size > maxDataPoints) {
                    currentList.takeLast(maxDataPoints)
                } else {
                    currentList
                }

                // --- MUDANÇA: Lógica de Envio em Lotes ---
                synchronized(dataBuffer) {
                    dataBuffer.add(it)
                    if (dataBuffer.size >= BATCH_SIZE) {
                        sendBatchToServer(ArrayList(dataBuffer)) // Envia uma cópia
                        dataBuffer.clear() // Limpa o buffer
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

    // --- MUDANÇA: Função atualizada para enviar um lote de dados ---
    private fun sendBatchToServer(batch: List<SensorDataPoint>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Cria um array JSON a partir da lista de data points
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
                    if (response.isSuccessful) {
                        Log.d("MainViewModel", "${batch.size} dados enviados com sucesso para o servidor.")
                    } else {
                        Log.e("MainViewModel", "Falha ao enviar lote: ${response.code} - ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Erro de rede ao enviar lote", e)
            }
        }
    }
    // --- Fim da Mudança ---

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
