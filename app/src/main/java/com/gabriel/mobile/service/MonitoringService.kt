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
import com.gabriel.mobile.BuildConfig
import com.gabriel.mobile.R
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.flow.MutableStateFlow

class MonitoringService : Service() {

    private var currentWatchBatteryLevel: Int? = null

    // Coroutine scope para o serviço
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Clientes e utilitários
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val networkClient = OkHttpClient()
    private val gson = Gson()
    private var socket: Socket? = null

    // Estado da sessão
    private var isSessionActive = false
    private var currentPatientName: String? = null
    private var currentSessionId: Int? = null

    // Buffers de dados
    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val dataQueue = ArrayDeque<SensorDataPoint>(MAX_DATA_POINTS_FOR_CHART)
    private var statusUpdateJob: Job? = null

    private val batteryDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DataLayerListenerService.ACTION_RECEIVE_BATTERY_DATA) {
                val level = intent.getIntExtra(DataLayerListenerService.EXTRA_BATTERY_LEVEL, -1)
                if (level != -1) {
                    Log.d("BatteryDebug", "2. MonitoringService: Broadcast de bateria recebido! Nível: $level")
                    currentWatchBatteryLevel = level
                    // Envia a atualização para o servidor
                    sendWatchStatusToServer()
                }
            }
        }
    }

    // BroadcastReceiver para dados do relógio
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

        val batteryIntentFilter = IntentFilter(DataLayerListenerService.ACTION_RECEIVE_BATTERY_DATA)
        LocalBroadcastManager.getInstance(this).registerReceiver(batteryDataReceiver, batteryIntentFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MonitoringService_DEBUG", "onStartCommand recebido com ação: ${intent?.action}")
        when (intent?.action) {
            ACTION_CONNECT -> {
                val patientName = intent.getStringExtra(EXTRA_PATIENT_NAME)
                if (patientName != null) {
                    currentPatientName = patientName
                    connectToSocket()
                }
            }
            ACTION_REQUEST_START_SESSION -> {
                val patientName = intent.getStringExtra(EXTRA_PATIENT_NAME)
                if (patientName != null && !isSessionActive) {
                    requestStartSessionOnServer(patientName)
                }
            }
            ACTION_START -> {
                val patientName = intent.getStringExtra(EXTRA_PATIENT_NAME)
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (patientName != null && sessionId != -1) {
                    startMonitoring(patientName, sessionId)
                }
            }
            ACTION_STOP -> {
                stopMonitoring()
            }
        }
        return START_STICKY
    }

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

        val notification = createNotification("Coleta de dados ativa para $patientName")
        startForeground(NOTIFICATION_ID, notification)

        //connectToSocket()
        sendCommandToWatch(DataLayerConstants.START_COMMAND)
        sendSessionStateUpdate()
        sendStatusUpdate("Sessão iniciada")
    }

    private fun stopMonitoring() {

        if (!isSessionActive) return
        Log.d(TAG, "Parando monitoramento da sessão.")

        // 1. Informa a UI e o sistema que a sessão não está mais ativa
        isSessionActive = false
        sendSessionStateUpdate()
        sendStatusUpdate("Sessão finalizada, conectado.")

        // 2. Para a coleta de dados no relógio e envia o buffer final
        sendCommandToWatch(DataLayerConstants.STOP_COMMAND)
        if (dataBuffer.isNotEmpty()) {
            sendBatchToServer(ArrayList(dataBuffer))
            dataBuffer.clear()
        }

        // 3. Notifica o servidor que a SESSÃO parou (mas não a conexão)
        currentPatientName?.let {
            val payload = JSONObject().apply { put("patientId", it) }
            socket?.emit("session_stopped_by_client", payload)
        }

        // 4. Limpa apenas o ID da sessão, mantendo o paciente selecionado
        currentSessionId = null

        // 5. Remove a notificação da barra de status, MAS MANTÉM O SERVIÇO RODANDO
        stopForeground(false) // O 'false' é crucial aqui.

    }
    private fun processDataPoint(dataPoint: SensorDataPoint) {
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

    // --- Comunicação com o Servidor (Socket.IO) ---
    private fun connectToSocket() {
        if (currentPatientName == null) {
            Log.w(TAG, "Tentativa de conectar sem um nome de paciente.")
            return
        }

        try {
            val socketUrl = BuildConfig.SERVER_URL.replace("http", "ws")

            // <<< CORREÇÃO AQUI >>>
            // 1. Sempre desconecta o socket anterior, se existir.
            // Isso garante que uma nova seleção de paciente limpe a conexão antiga.
            socket?.disconnect()

            // 2. Cria uma nova instância de socket e se conecta.
            Log.d(TAG, "Criando nova conexão de socket para o paciente: $currentPatientName")
            socket = IO.socket(socketUrl)
            setupSocketListeners()
            socket?.connect()

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar socket: ${e.message}")
        }
    }

    private fun setupSocketListeners() {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Socket conectado!")
            currentPatientName?.let { name ->
                socket?.emit("register_patient", JSONObject().put("patientId", name))
                sendWatchStatusToServer()
                if(isSessionActive && currentSessionId != null) {
                    val payload = JSONObject().apply {
                        put("patientName", name)
                        put("sessionId", currentSessionId)
                    }
                    socket?.emit("resume_active_session", payload)
                }
            }
        }

        socket?.on("start_monitoring") { args ->
            try {
                val data = args[0] as JSONObject
                val sessionId = data.getInt("sessao_id")
                Log.d(TAG, "Comando 'start' recebido do servidor para a sessão: $sessionId")
                // O serviço já sabe o nome do paciente, então só precisa do ID da sessão
                // A função onStartCommand do serviço lida com o início real
                val serviceIntent = Intent(this, MonitoringService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_PATIENT_NAME, currentPatientName)
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
                ContextCompat.startForegroundService(this, serviceIntent)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar 'start_monitoring'", e)
            }
        }

        socket?.on("stop_monitoring") {
            Log.d(TAG, "Comando 'stop' recebido do servidor")
            // Chama a própria função de parada do serviço
            stopMonitoring()
        }

        socket?.on(Socket.EVENT_DISCONNECT) { Log.d(TAG, "Socket desconectado.") }
    }

    private fun disconnectFromSocket() {
        socket?.disconnect()
        socket?.off()
    }

    private fun sendWatchStatusToServer() {
        val patient = currentPatientName ?: return
        val battery = currentWatchBatteryLevel ?: return
        Log.d("BatteryDebug", "3. MonitoringService: Enviando status para o servidor (Bateria: $battery%)")
        val payload = JSONObject().apply {
            put("patientId", patient)
            put("batteryLevel", battery)
        }
        socket?.emit("watch_status_update", payload)
        Log.d(TAG, "Enviando status do relógio para o servidor: Bateria $battery%")

        val intent = Intent(ACTION_WATCH_STATUS_UPDATE).apply {
            putExtra(EXTRA_BATTERY_LEVEL, battery)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun requestStartSessionOnServer(patientName: String) {
        serviceScope.launch {
            try {
                val serverUrl = "${BuildConfig.SERVER_URL}/api/start_session"
                val jsonObject = JSONObject().apply {
                    put("patientId", patientName)
                }
                val requestBody = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(serverUrl).post(requestBody).build()

                networkClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Requisição para iniciar sessão enviada com sucesso para o servidor.")
                        // O servidor agora vai responder com um evento WebSocket 'start_monitoring',
                        // que o nosso serviço já sabe como manipular. Não precisamos fazer mais nada aqui.
                    } else {
                        Log.e(TAG, "Falha ao requisitar início de sessão: ${response.code} ${response.message}")
                        // Opcional: Enviar um broadcast para a UI informando o erro.
                        sendStatusUpdate("Erro ao iniciar sessão")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro de rede ao requisitar início de sessão", e)
                sendStatusUpdate("Erro de rede")
            }
        }
    }

    private fun sendBatchToServer(batch: List<SensorDataPoint>) {
        val patientId = currentPatientName ?: return
        val sessionId = currentSessionId ?: return
        serviceScope.launch {
            try {
                val serverUrl = "${BuildConfig.SERVER_URL}/data"
                val rootJsonObject = JSONObject().apply {
                    put("patientId", patientId)
                    put("sessao_id", sessionId)

                    // <<< CORREÇÃO AQUI >>>
                    // Cria um array JSON vazio
                    val dataJsonArray = JSONArray()
                    // Itera sobre cada ponto de dado no lote
                    batch.forEach { dp ->
                        // Para cada ponto, cria um objeto JSON com as chaves "x", "y", "z"
                        val dataObject = JSONObject().apply {
                            put("timestamp", dp.timestamp)
                            put("x", dp.values[0])
                            put("y", dp.values[1])
                            put("z", dp.values[2])
                        }
                        // Adiciona o objeto formatado ao array
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

    // --- Comunicação com o Relógio ---
    private fun sendCommandToWatch(command: String) {
        val nodeClient = Wearable.getNodeClient(this)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                val payload = command.toByteArray(StandardCharsets.UTF_8)
                messageClient.sendMessage(node.id, DataLayerConstants.CONTROL_PATH, payload)
            }
        }
    }

    // --- Comunicação com a UI (ViewModel) via Broadcasts ---
    private fun sendStatusUpdate(message: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendSessionStateUpdate() {
        val intent = Intent(ACTION_SESSION_STATE_UPDATE).apply {
            putExtra(EXTRA_IS_SESSION_ACTIVE, isSessionActive)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendDataUpdate(data: List<SensorDataPoint>) {
        val intent = Intent(ACTION_NEW_DATA_UPDATE).apply {
            putExtra(EXTRA_DATA_POINTS, gson.toJson(data))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // --- Ciclo de Vida e Notificação ---
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Serviço destruído.")
        serviceJob.cancel() // Cancela todas as coroutines
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorDataReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(batteryDataReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Canal de Monitoramento",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    private fun createNotification(contentText: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Monitoramento de Tremor")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // IMPORTANTE: Verifique se este ícone existe em res/drawable
            .setOngoing(true)
            .build()

    companion object {

        const val ACTION_WATCH_STATUS_UPDATE = "ACTION_WATCH_STATUS_UPDATE"
        const val EXTRA_BATTERY_LEVEL = "EXTRA_BATTERY_LEVEL"
        private const val TAG = "MonitoringService"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        const val ACTION_CONNECT = "ACTION_CONNECT"
        const val ACTION_REQUEST_START_SESSION = "ACTION_REQUEST_START_SESSION"

        const val EXTRA_PATIENT_NAME = "EXTRA_PATIENT_NAME"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"

        const val ACTION_STATUS_UPDATE = "ACTION_STATUS_UPDATE"
        const val EXTRA_STATUS_MESSAGE = "EXTRA_STATUS_MESSAGE"

        const val ACTION_SESSION_STATE_UPDATE = "ACTION_SESSION_STATE_UPDATE"
        const val EXTRA_IS_SESSION_ACTIVE = "EXTRA_IS_SESSION_ACTIVE"

        const val ACTION_NEW_DATA_UPDATE = "ACTION_NEW_DATA_UPDATE"
        const val EXTRA_DATA_POINTS = "EXTRA_DATA_POINTS"

        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "MonitoringChannel"

        private const val BATCH_SIZE = DataLayerConstants.BATCH_SIZE
        private const val MAX_DATA_POINTS_FOR_CHART = 100
    }
}