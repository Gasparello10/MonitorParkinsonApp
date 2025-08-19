package com.gabriel.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.mobile.BuildConfig
import com.gabriel.mobile.R
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class MonitoringService : Service() {

    // --- ESCOPO E UTILITÁRIOS ---
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val networkClient = OkHttpClient()
    private val gson = Gson()
    private var socket: Socket? = null

    // --- ESTADO DO SERVIÇO ---
    private var isSessionActive = false

    // <<< NOVAS VARIÁVEIS DE ESTADO >>>
    // Armazena o ID único do aparelho
    private var deviceName: String? = null
    // Armazena a lista de pacientes (em formato JSON) vinda do ViewModel
    private var patientListJson: String? = null
    // Armazena o paciente que foi ativado remotamente pelo dashboard
    private var currentPatientName: String? = null

    private var currentSessionId: Int? = null

    // --- BUFFERS E JOBS ---
    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val dataQueue = ArrayDeque<SensorDataPoint>(MAX_DATA_POINTS_FOR_CHART)
    private var statusUpdateJob: Job? = null

    // --- BROADCAST RECEIVER (PARA DADOS DO RELÓGIO) ---
    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isSessionActive) return
            val dataPoint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_DATA, SensorDataPoint::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_DATA) as? SensorDataPoint
            }
            dataPoint?.let { processDataPoint(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço criado.")
        createNotificationChannel()
        val intentFilter = IntentFilter(DataLayerListenerService.ACTION_RECEIVE_SENSOR_DATA)
        LocalBroadcastManager.getInstance(this).registerReceiver(sensorDataReceiver, intentFilter)
    }

    // <<< onStartCommand TOTALMENTE REESCRITO >>>
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand recebido com ação: ${intent?.action}")

        when (intent?.action) {
            // Ação para estabelecer a conexão inicial com o servidor
            ACTION_CONNECT -> {
                this.deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
                this.patientListJson = intent.getStringExtra(EXTRA_PATIENT_LIST)
                connectToSocket()
            }
            // Ação para iniciar a coleta de dados (disparada pelo dashboard)
            ACTION_START -> {
                val patientName = intent.getStringExtra(EXTRA_PATIENT_NAME)
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (patientName != null && sessionId != -1) {
                    startMonitoring(patientName, sessionId)
                }
            }
            // Ação para parar a coleta de dados
            ACTION_STOP -> {
                stopMonitoring()
            }
        }
        return START_STICKY
    }

    // <<< FUNÇÕES DE CONTROLE DA SESSÃO (LÓGICA INTERNA INALTERADA) >>>
    private fun startMonitoring(patientName: String, sessionId: Int) {
        if (isSessionActive) {
            Log.w(TAG, "Tentativa de iniciar uma sessão já ativa.")
            return
        }
        Log.d(TAG, "Iniciando monitoramento para $patientName, sessão $sessionId")

        currentPatientName = patientName
        currentSessionId = sessionId
        isSessionActive = true
        dataBuffer.clear()
        dataQueue.clear()

        startForeground(NOTIFICATION_ID, createNotification("Coleta ativa para $patientName"))
        sendCommandToWatch(DataLayerConstants.START_COMMAND)
        sendSessionStateUpdate()
        sendStatusUpdate("Sessão iniciada")
    }

    private fun stopMonitoring() {
        if (!isSessionActive) return
        Log.d(TAG, "Parando monitoramento da sessão.")

        isSessionActive = false
        sendSessionStateUpdate()
        sendStatusUpdate("Conectado. Sessão finalizada.")

        sendCommandToWatch(DataLayerConstants.STOP_COMMAND)
        if (dataBuffer.isNotEmpty()) {
            sendBatchToServer(ArrayList(dataBuffer))
            dataBuffer.clear()
        }

        currentPatientName?.let {
            socket?.emit("session_stopped_by_client", JSONObject().put("patientId", it))
        }

        currentSessionId = null
        stopForeground(false)
    }

    private fun processDataPoint(dataPoint: SensorDataPoint) {
        // ... (esta função permanece a mesma)
        statusUpdateJob?.cancel()
        sendStatusUpdate("Recebendo Dados...")

        synchronized(dataQueue) {
            if (dataQueue.size >= MAX_DATA_POINTS_FOR_CHART) dataQueue.removeFirst()
            dataQueue.addLast(dataPoint)
            sendDataUpdate(dataQueue.toList())
        }

        synchronized(dataBuffer) {
            dataBuffer.add(dataPoint)
            if (dataBuffer.size >= BATCH_SIZE) {
                sendBatchToServer(ArrayList(dataBuffer))
                dataBuffer.clear()
            }
        }

        statusUpdateJob = serviceScope.launch {
            delay(3000L)
            sendStatusUpdate("Pausado")
        }
    }

    // --- COMUNICAÇÃO COM O SERVIDOR ---
    // <<< FUNÇÃO CONNECTTOSOCKET ALTERADA >>>
    private fun connectToSocket() {
        // Agora depende apenas do deviceName, não mais do paciente
        if (deviceName == null || patientListJson == null) {
            Log.e(TAG, "Tentativa de conectar sem deviceName ou patientListJson.")
            return
        }

        if (socket?.connected() == true) {
            Log.w(TAG, "Socket já conectado.")
            return
        }

        try {
            val socketUrl = BuildConfig.SERVER_URL.replace("http", "ws")
            socket?.disconnect() // Garante que a conexão anterior seja limpa

            Log.d(TAG, "Iniciando nova conexão de socket para o dispositivo: $deviceName")
            socket = IO.socket(socketUrl)
            setupSocketListeners() // Configura os handlers ANTES de conectar
            socket?.connect()

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar socket: ${e.message}")
            sendStatusUpdate("Erro de conexão")
        }
    }

    // <<< SETUPSOCKETLISTENERS ATUALIZADO (COMO DEFINIMOS ANTERIORMENTE) >>>
    private fun setupSocketListeners() {
        // 1. Ao conectar, registra o dispositivo e sua lista de pacientes
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Socket conectado! Registrando dispositivo no servidor...")
            try {
                val registrationData = JSONObject().apply {
                    put("deviceName", deviceName)
                    put("patients", JSONArray(patientListJson))
                }
                socket?.emit("register_device_with_patients", registrationData)
                sendStatusUpdate("Conectado ao servidor")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao registrar dispositivo.", e)
            }
        }

        // 2. Ouve o comando do dashboard para ativar um paciente
        socket?.on("set_active_patient") { args ->
            try {
                val patientData = args[0] as JSONObject
                val patientId = patientData.getString("id")
                val patientName = patientData.getString("name")
                Log.d(TAG, "Comando do dashboard: Ativar paciente '$patientName'")

                // Atualiza o paciente ativo no estado interno do serviço
                currentPatientName = patientName

                // Notifica a UI (ViewModel) sobre a mudança
                val intent = Intent(ACTION_PATIENT_SELECTED_BY_DASHBOARD).apply {
                    putExtra(EXTRA_PATIENT_ID, patientId)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                // (Opcional) Envia confirmação
                socket?.emit("patient_activated", JSONObject().put("patientName", patientName))
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar 'set_active_patient'", e)
            }
        }

        // 3. Ouve os comandos de start/stop da sessão
        socket?.on("start_monitoring") { args ->
            try {
                if (currentPatientName == null) {
                    Log.e(TAG, "Comando 'start_monitoring' recebido, mas nenhum paciente está ativo!")
                    return@on
                }
                val data = args[0] as JSONObject
                val sessionId = data.getInt("sessao_id")
                // Inicia o monitoramento usando a função já existente
                startMonitoring(currentPatientName!!, sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar 'start_monitoring'", e)
            }
        }

        socket?.on("stop_monitoring") {
            Log.d(TAG, "Comando 'stop' recebido do servidor")
            stopMonitoring()
        }

        // 4. Lida com a desconexão
        socket?.on(Socket.EVENT_DISCONNECT) {
            Log.w(TAG, "Socket desconectado do servidor.")
            sendStatusUpdate("Desconectado")
            currentPatientName = null
        }
    }

    private fun sendBatchToServer(batch: List<SensorDataPoint>) {
        // ... (esta função permanece a mesma)
        val patientId = currentPatientName ?: return
        val sessionId = currentSessionId ?: return
        serviceScope.launch {
            try {
                val serverUrl = "${BuildConfig.SERVER_URL}/data"
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
                    if (!response.isSuccessful) Log.e(TAG, "Falha ao enviar lote: ${response.code}")
                    else Log.d(TAG, "Lote enviado com sucesso para a sessão $sessionId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro de rede ao enviar lote", e)
            }
        }
    }

    // --- RESTO DO CÓDIGO (COMUNICAÇÃO, NOTIFICAÇÃO, ETC.) ---
    // ... (as funções abaixo permanecem praticamente inalteradas)

    private fun sendCommandToWatch(command: String) { /* ... seu código ... */ }
    private fun sendStatusUpdate(message: String) { /* ... seu código ... */ }
    private fun sendSessionStateUpdate() { /* ... seu código ... */ }
    private fun sendDataUpdate(data: List<SensorDataPoint>) { /* ... seu código ... */ }
    override fun onDestroy() { /* ... seu código ... */ }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun createNotificationChannel() { /* ... seu código ... */ }
    private fun createNotification(contentText: String) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Monitoramento de Tremor")
        .setContentText(contentText)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .build()

    // <<< COMPANION OBJECT ATUALIZADO COM NOVAS CONSTANTES >>>
    companion object {
        private const val TAG = "MonitoringService"

        // Ações para o ViewModel controlar o Serviço
        const val ACTION_CONNECT = "ACTION_CONNECT" // Inicia a conexão do socket
        const val ACTION_START = "ACTION_START"     // Inicia a coleta de dados
        const val ACTION_STOP = "ACTION_STOP"       // Para a coleta de dados

        // Extras para passar dados via Intent
        const val EXTRA_DEVICE_NAME = "EXTRA_DEVICE_NAME"
        const val EXTRA_PATIENT_LIST = "EXTRA_PATIENT_LIST"
        const val EXTRA_PATIENT_NAME = "EXTRA_PATIENT_NAME"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"

        // Ações para o Serviço notificar o ViewModel
        const val ACTION_STATUS_UPDATE = "ACTION_STATUS_UPDATE"
        const val EXTRA_STATUS_MESSAGE = "EXTRA_STATUS_MESSAGE"
        const val ACTION_SESSION_STATE_UPDATE = "ACTION_SESSION_STATE_UPDATE"
        const val EXTRA_IS_SESSION_ACTIVE = "EXTRA_IS_SESSION_ACTIVE"
        const val ACTION_NEW_DATA_UPDATE = "ACTION_NEW_DATA_UPDATE"
        const val EXTRA_DATA_POINTS = "EXTRA_DATA_POINTS"
        // Nova ação para notificar a UI sobre seleção remota
        const val ACTION_PATIENT_SELECTED_BY_DASHBOARD = "ACTION_PATIENT_SELECTED_BY_DASHBOARD"
        const val EXTRA_PATIENT_ID = "EXTRA_PATIENT_ID"

        // Constantes internas
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "MonitoringChannel"
        private const val BATCH_SIZE = DataLayerConstants.BATCH_SIZE
        private const val MAX_DATA_POINTS_FOR_CHART = 100
    }
}