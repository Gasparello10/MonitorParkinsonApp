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
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val messageClient by lazy { Wearable.getMessageClient(application) }
    private val nodeClient by lazy { Wearable.getNodeClient(application) }

    // --- Lógica de Rede Adicionada ---
    private val networkClient = OkHttpClient()
    // IMPORTANTE: Lembre-se de substituir este IP pelo do seu computador!
    private val serverUrl = "http://192.168.0.12:5000/data"
    // --- Fim da Lógica de Rede ---

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

                // --- Lógica de Rede Adicionada ---
                // Envia o novo dado para o servidor Python
                sendDataToServer(it)
                // --- Fim da Lógica de Rede ---

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

    // --- Lógica de Rede Adicionada ---
    private fun sendDataToServer(dataPoint: SensorDataPoint) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonObject = JSONObject().apply {
                    put("timestamp", dataPoint.timestamp)
                    put("x", dataPoint.values[0])
                    put("y", dataPoint.values[1])
                    put("z", dataPoint.values[2])
                }

                val requestBody = jsonObject.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(serverUrl)
                    .post(requestBody)
                    .build()

                networkClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d("MainViewModel", "Dado enviado com sucesso para o servidor.")
                    } else {
                        Log.e("MainViewModel", "Falha ao enviar dado: ${response.code} - ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Erro de rede ao enviar dado", e)
            }
        }
    }
    // --- Fim da Lógica de Rede ---

    fun checkConnection() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val nearbyNodes = nodes.filter { it.isNearby }
            _isConnected.value = nearbyNodes.isNotEmpty()

            if (nearbyNodes.isNotEmpty()) {
                val nodeInfo = nearbyNodes.joinToString(", ") { node -> "${node.displayName} (ID: ${node.id})" }
                Log.d("MainViewModel", "Conectado ao(s) nó(s): $nodeInfo")
            } else {
                Log.d("MainViewModel", "Nenhum nó conectado.")
            }
        }.addOnFailureListener { e ->
            _isConnected.value = false
            Log.e("MainViewModel", "Falha ao verificar nós conectados", e)
        }
    }

    fun exportDataToCsv() {
        viewModelScope.launch {
            val data = _sensorDataPoints.value
            if (data.isEmpty()) {
                _status.value = "Nenhum dado para exportar."
                return@launch
            }

            val stringBuilder = StringBuilder()
            stringBuilder.append("timestamp,x,y,z\n")
            data.forEach { dataPoint ->
                val line = "${dataPoint.timestamp},${dataPoint.values[0]},${dataPoint.values[1]},${dataPoint.values[2]}\n"
                stringBuilder.append(line)
            }
            _exportCsvEvent.emit(stringBuilder.toString())
        }
    }

    fun sendPingToWatch() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, DataLayerConstants.PING_PATH, "PING".toByteArray(StandardCharsets.UTF_8))
                    .addOnSuccessListener { Log.d("MainViewModel", "Ping enviado para ${node.displayName}") }
                    .addOnFailureListener { e -> Log.e("MainViewModel", "Falha ao enviar Ping", e) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        statusUpdateJob?.cancel()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(sensorDataReceiver)
    }
}
