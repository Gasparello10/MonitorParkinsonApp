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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

data class Patient(val id: String = UUID.randomUUID().toString(), val name: String)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val messageClient by lazy { Wearable.getMessageClient(application) }
    private val nodeClient by lazy { Wearable.getNodeClient(application) }
    private val networkClient = OkHttpClient()
    private val serverUrl = "${BuildConfig.SERVER_URL}/data"
    private val gson = Gson()
    private val sharedPreferences = application.getSharedPreferences("patient_prefs", Context.MODE_PRIVATE)

    private var socket: Socket? = null
    private val socketUrl = BuildConfig.SERVER_URL.replace("http", "ws")

    private val _patients = MutableStateFlow<List<Patient>>(emptyList())
    val patients = _patients.asStateFlow()

    private val _selectedPatient = MutableStateFlow<Patient?>(null)
    val selectedPatient = _selectedPatient.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive = _isSessionActive.asStateFlow()

    private var currentSessionId: Int? = null
    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val BATCH_SIZE = 25

    private val _status = MutableStateFlow("Aguardando dados...")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _sensorDataPoints = MutableStateFlow<List<SensorDataPoint>>(emptyList())
    val sensorDataPoints = _sensorDataPoints.asStateFlow()

    private val maxDataPointsForChart = 100
    private val dataQueue = ArrayDeque<SensorDataPoint>(maxDataPointsForChart)
    private var statusUpdateJob: Job? = null
    private val uiUpdateBuffer = mutableListOf<SensorDataPoint>()
    private val UI_UPDATE_BATCH_SIZE = 5

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!_isSessionActive.value) return
            val dataPoint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_DATA, SensorDataPoint::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_DATA) as? SensorDataPoint
            }
            dataPoint?.let { processDataPoint(it) }
        }
    }

    init {
        val intentFilter = IntentFilter(DataLayerListenerService.ACTION_RECEIVE_SENSOR_DATA)
        LocalBroadcastManager.getInstance(application).registerReceiver(sensorDataReceiver, intentFilter)
        checkConnection()
        loadPatients()
    }

    private fun setupSocketListeners() {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("SocketIO", "Conectado ao servidor!")
            _selectedPatient.value?.let { patient ->
                socket?.emit("register_patient", JSONObject().put("patientId", patient.name))
            }
        }
        socket?.on("start_monitoring") { args ->
            try {
                val data = args[0] as JSONObject
                val sessionId = data.getInt("sessao_id")
                Log.d("SocketIO", "Comando 'start' recebido com sessao_id: $sessionId")
                startSession(sessionId)
            } catch (e: Exception) {
                Log.e("SocketIO", "Erro ao processar 'start_monitoring'", e)
            }
        }
        socket?.on("stop_monitoring") {
            Log.d("SocketIO", "Comando 'stop' recebido")
            stopSession()
        }
        socket?.on(Socket.EVENT_DISCONNECT) {
            Log.d("SocketIO", "Desconectado do servidor.")
        }
    }

    private fun connectToSocket() {
        if (_selectedPatient.value == null) return
        try {
            socket?.disconnect()
            socket = IO.socket(socketUrl)
            setupSocketListeners()
            socket?.connect()
        } catch (e: Exception) {
            Log.e("SocketIO", "Erro ao conectar: ${e.message}")
        }
    }

    private fun disconnectFromSocket() {
        socket?.disconnect()
        socket?.off()
    }

    fun addPatient(name: String) {
        val newPatient = Patient(name = name)
        val updatedList = _patients.value + newPatient
        _patients.value = updatedList
        savePatients(updatedList)
    }

    // <<< NOVA FUNÇÃO PARA EXCLUIR >>>
    fun deletePatient(patientToDelete: Patient) {
        if (_selectedPatient.value?.id == patientToDelete.id) {
            disconnectFromSocket()
            _selectedPatient.value = null
        }
        val updatedList = _patients.value.filter { it.id != patientToDelete.id }
        _patients.value = updatedList
        savePatients(updatedList)
    }

    fun selectPatient(patient: Patient) {
        if (_selectedPatient.value?.id != patient.id) {
            disconnectFromSocket()
            _selectedPatient.value = patient
            connectToSocket()
        }
    }

    fun startSession(sessionId: Int) {
        if (_selectedPatient.value != null) {
            currentSessionId = sessionId
            _isSessionActive.value = true
            _sensorDataPoints.value = emptyList()
            dataQueue.clear()
            sendCommandToWatch(DataLayerConstants.START_COMMAND)
        }
    }

    fun stopSession() {
        sendCommandToWatch(DataLayerConstants.STOP_COMMAND)
        if (dataBuffer.isNotEmpty()) {
            sendBatchToServer(ArrayList(dataBuffer))
            dataBuffer.clear()
        }
        _isSessionActive.value = false
        currentSessionId = null
    }

    private fun processDataPoint(dataPoint: SensorDataPoint) {
        statusUpdateJob?.cancel()
        _status.value = "Recebendo Dados..."
        synchronized(dataQueue) {
            if (dataQueue.size >= maxDataPointsForChart) dataQueue.removeFirst()
            dataQueue.addLast(dataPoint)
        }
        synchronized(dataBuffer) {
            dataBuffer.add(dataPoint)
            if (dataBuffer.size >= BATCH_SIZE) {
                sendBatchToServer(ArrayList(dataBuffer))
                dataBuffer.clear()
            }
        }
        synchronized(uiUpdateBuffer) {
            uiUpdateBuffer.add(dataPoint)
            if (uiUpdateBuffer.size >= UI_UPDATE_BATCH_SIZE) {
                _sensorDataPoints.value = dataQueue.toList()
                uiUpdateBuffer.clear()
            }
        }
        statusUpdateJob = viewModelScope.launch {
            delay(3000L)
            _status.value = "Parado"
        }
    }

    private fun savePatients(patients: List<Patient>) {
        val json = gson.toJson(patients)
        sharedPreferences.edit().putString("patient_list", json).apply()
    }

    private fun loadPatients() {
        val json = sharedPreferences.getString("patient_list", null)
        if (json != null) {
            val type = object : TypeToken<List<Patient>>() {}.type
            _patients.value = gson.fromJson(json, type)
        }
    }

    private fun sendBatchToServer(batch: List<SensorDataPoint>) {
        val patientId = _selectedPatient.value?.name ?: return
        val sessionId = currentSessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rootJsonObject = JSONObject().apply {
                    put("patientId", patientId)
                    put("sessao_id", sessionId)
                    val dataJsonArray = JSONArray()
                    batch.forEach { dp ->
                        val dataObject = JSONObject().apply {
                            put("timestamp", dp.timestamp)
                            put("x", dp.values[0])
                            put("y", dp.values[1])
                            put("z", dp.values[2])
                        }
                        dataJsonArray.put(dataObject)
                    }
                    put("data", dataJsonArray)
                }
                val requestBody = rootJsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(serverUrl).post(requestBody).build()
                networkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) Log.e("MainViewModel", "Falha ao enviar lote: ${response.code}")
                    else Log.d("MainViewModel", "Lote enviado com sucesso para a sessão $sessionId")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Erro de rede ao enviar lote", e)
            }
        }
    }

    private fun sendCommandToWatch(command: String) {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                val payload = command.toByteArray(StandardCharsets.UTF_8)
                messageClient.sendMessage(node.id, DataLayerConstants.CONTROL_PATH, payload)
                    .addOnSuccessListener { Log.d("MainViewModel", "Comando '$command' enviado para o relógio ${node.displayName}") }
                    .addOnFailureListener { Log.e("MainViewModel", "Falha ao enviar comando '$command' para o relógio ${node.displayName}") }
            }
        }
    }

    fun checkConnection() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            _isConnected.value = nodes.any { it.isNearby }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectFromSocket()
        statusUpdateJob?.cancel()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(sensorDataReceiver)
    }
}