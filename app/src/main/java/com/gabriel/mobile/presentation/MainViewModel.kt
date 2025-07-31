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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    // MUDANÇA: Adiciona um fluxo de eventos para a ação de exportar
    private val _exportCsvEvent = MutableSharedFlow<String>()
    val exportCsvEvent = _exportCsvEvent.asSharedFlow()

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
        checkConnection()
    }

    // MUDANÇA: Nova função para criar o conteúdo CSV e emitir o evento
    fun exportDataToCsv() {
        viewModelScope.launch {
            val data = _sensorDataPoints.value
            if (data.isEmpty()) {
                _status.value = "Nenhum dado para exportar."
                return@launch
            }

            val stringBuilder = StringBuilder()
            // Adiciona o cabeçalho do CSV
            stringBuilder.append("timestamp,x,y,z\n")
            // Adiciona cada ponto de dado em uma nova linha
            data.forEach { dataPoint ->
                val line = "${dataPoint.timestamp},${dataPoint.values[0]},${dataPoint.values[1]},${dataPoint.values[2]}\n"
                stringBuilder.append(line)
            }
            // Emite o evento com o conteúdo CSV pronto
            _exportCsvEvent.emit(stringBuilder.toString())
        }
    }

    fun checkConnection() {
        // ... (código existente)
    }

    fun sendPingToWatch() {
        // ... (código existente)
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(sensorDataReceiver)
    }
}
