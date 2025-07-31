package com.gabriel.mobile.presentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.mobile.service.DataLayerListenerService
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val messageClient by lazy { Wearable.getMessageClient(application) }
    private val nodeClient by lazy { Wearable.getNodeClient(application) }

    private val _status = MutableStateFlow("Aguardando dados...")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _sensorDataPoints = MutableStateFlow<List<SensorDataPoint>>(emptyList())
    val sensorDataPoints = _sensorDataPoints.asStateFlow()

    // MUDANÇA: Define o número máximo de pontos de dados a serem mantidos na memória.
    private val maxDataPoints = 100

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dataPoint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_DATA, SensorDataPoint::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_DATA) as? SensorDataPoint
            }

            dataPoint?.let {
                _status.value = "Recebendo dados..."
                // MUDANÇA: Lógica para adicionar e limitar o número de pontos.
                val currentList = _sensorDataPoints.value.toMutableList()
                currentList.add(it)
                if (currentList.size > maxDataPoints) {
                    _sensorDataPoints.value = currentList.takeLast(maxDataPoints)
                } else {
                    _sensorDataPoints.value = currentList
                }
            }
        }
    }

    init {
        val intentFilter = IntentFilter(DataLayerListenerService.ACTION_RECEIVE_SENSOR_DATA)
        LocalBroadcastManager.getInstance(application).registerReceiver(sensorDataReceiver, intentFilter)
        Log.d("MainViewModel", "BroadcastReceiver para dados do sensor registrado.")
        checkConnection()
    }

    fun checkConnection() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            _isConnected.value = nodes.isNotEmpty()
            if (nodes.isNotEmpty()) {
                val nodeInfo = nodes.joinToString(", ") { node -> "${node.displayName} (ID: ${node.id})" }
                Log.d("MainViewModel", "Nós conectados encontrados: $nodeInfo")
            } else {
                Log.d("MainViewModel", "Nenhum nó conectado encontrado.")
            }
        }.addOnFailureListener { e ->
            _isConnected.value = false
            Log.e("MainViewModel", "Falha ao verificar nós conectados", e)
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
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(sensorDataReceiver)
    }
}
