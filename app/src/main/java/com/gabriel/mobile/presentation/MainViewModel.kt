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
import kotlinx.coroutines.flow.update
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

    // <<< NOVO: Estados para o modo de seleção e exclusão >>>
    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode = _isInSelectionMode.asStateFlow()

    private val _selectedForDeletion = MutableStateFlow<Set<String>>(emptySet())
    val selectedForDeletion = _selectedForDeletion.asStateFlow()
    // <<< FIM DOS NOVOS ESTADOS >>>


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
            Log.d("SocketIO", "Conectado/Reconectado ao servidor!")

            // 1. Reapresenta o paciente para aparecer na lista "Online" (isso já existia)
            _selectedPatient.value?.let { patient ->
                socket?.emit("register_patient", JSONObject().put("patientId", patient.name))

                // 2. <<< NOVO >>> Verifica se há uma sessão ativa localmente
                if (_isSessionActive.value && currentSessionId != null) {
                    // Se houver, informa o servidor para restaurar o estado
                    val payload = JSONObject().apply {
                        put("patientName", patient.name)
                        put("sessionId", currentSessionId)
                    }
                    socket?.emit("resume_active_session", payload)
                    Log.d("SocketIO", "Informando ao servidor para resumir a sessão ativa: $currentSessionId")
                }
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

    // <<< NOVAS FUNÇÕES PARA GERENCIAR A SELEÇÃO E EXCLUSÃO >>>
    fun enterSelectionMode(initialPatientId: String) {
        _isInSelectionMode.value = true
        _selectedForDeletion.value = setOf(initialPatientId)
    }

    fun toggleSelection(patientId: String) {
        _selectedForDeletion.update { currentSelection ->
            if (currentSelection.contains(patientId)) {
                currentSelection - patientId
            } else {
                currentSelection + patientId
            }
        }
    }

    fun deleteSelectedPatients() {
        val selection = _selectedForDeletion.value
        if (selection.isEmpty()) {
            exitSelectionMode()
            return
        }

        // Verifica se o paciente atualmente conectado está na lista de exclusão
        if (_selectedPatient.value != null && selection.contains(_selectedPatient.value!!.id)) {
            disconnectFromSocket()
            _selectedPatient.value = null
        }

        val updatedList = _patients.value.filter { !selection.contains(it.id) }
        _patients.value = updatedList
        savePatients(updatedList)

        exitSelectionMode()
    }

    fun exitSelectionMode() {
        _isInSelectionMode.value = false
        _selectedForDeletion.value = emptySet()
    }
    // <<< FIM DAS NOVAS FUNÇÕES >>>


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
        // 1. Manda o relógio parar de coletar dados.
        sendCommandToWatch(DataLayerConstants.STOP_COMMAND)

        // 2. Envia o último lote de dados que restou no buffer.
        if (dataBuffer.isNotEmpty()) {
            sendBatchToServer(ArrayList(dataBuffer))
            dataBuffer.clear()
        }

        // 3. Atualiza o estado interno do app para parar de processar novos dados.
        _isSessionActive.value = false
        currentSessionId = null

        // 4. AGORA, como última ação, notifica o servidor que tudo foi encerrado.
        _selectedPatient.value?.let { patient ->
            val payload = JSONObject().apply {
                put("patientId", patient.name)
            }
            socket?.emit("session_stopped_by_client", payload)
            Log.d("SocketIO", "Notificação FINAL de parada enviada para o servidor para o paciente '${patient.name}'.")
        }
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