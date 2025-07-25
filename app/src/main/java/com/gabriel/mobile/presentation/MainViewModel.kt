package com.gabriel.mobile.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gabriel.mobile.data.SensorDataRepository
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// MUDANÇA: Usamos AndroidViewModel para ter acesso ao contexto do aplicativo.
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val nodeClient by lazy { Wearable.getNodeClient(getApplication()) }

    // StateFlow para os dados do sensor
    private val _sensorDataPoints = MutableStateFlow<List<SensorDataPoint>>(emptyList())
    val sensorDataPoints = _sensorDataPoints.asStateFlow()

    // StateFlow para o status de recebimento de dados
    private val _status = MutableStateFlow("Aguardando dados...")
    val status = _status.asStateFlow()

    // MUDANÇA: Novo StateFlow para o status da conexão
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    init {
        // Verifica a conexão assim que o ViewModel é criado
        checkConnection()

        // Coleta os dados do sensor vindos do repositório
        viewModelScope.launch {
            SensorDataRepository.sensorDataFlow.collect { dataPoint ->
                _sensorDataPoints.value += dataPoint
                _status.value = "Recebendo dados..."
            }
        }
    }

    // MUDANÇA: Nova função para verificar os nós conectados
    fun checkConnection() {
        viewModelScope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                _isConnected.value = nodes.isNotEmpty()
                if (nodes.isNotEmpty()) {
                    Log.d("MainViewModel", "Nós conectados encontrados: ${nodes.size}")
                } else {
                    Log.d("MainViewModel", "Nenhum nó conectado encontrado.")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Falha ao verificar conexão", e)
                _isConnected.value = false
            }
        }
    }
}
