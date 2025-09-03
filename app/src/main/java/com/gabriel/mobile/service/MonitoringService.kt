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
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gabriel.mobile.BuildConfig
import com.gabriel.mobile.R
import com.gabriel.mobile.data.SensorDataRepository
import com.gabriel.mobile.data.local.AppDatabase
import com.gabriel.mobile.data.local.SensorDataBatch
import com.gabriel.mobile.worker.UploadWorker
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
import java.util.ArrayList

class MonitoringService : Service() {

    private var currentWatchBatteryLevel: Int? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val networkClient = OkHttpClient()
    private val gson = Gson()
    private var socket: Socket? = null
    private var isSessionActive = false
    private var currentPatientName: String? = null
    private var currentSessionId: Int? = null

    private val dataQueue = ArrayDeque<SensorDataPoint>(MAX_DATA_POINTS_FOR_CHART)
    private var statusUpdateJob: Job? = null
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val sensorDataRepository by lazy { SensorDataRepository(db.sensorDataBatchDao()) }

    // <<< CORREÇÃO 1: Implementação completa do onReceive >>>
    private val batteryDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DataLayerListenerService.ACTION_RECEIVE_BATTERY_DATA) {
                val level = intent.getIntExtra(DataLayerListenerService.EXTRA_BATTERY_LEVEL, -1)
                if (level != -1) {
                    currentWatchBatteryLevel = level
                    sendWatchStatusToServer()
                }
            }
        }
    }

    // <<< CORREÇÃO 2: Tratamento de tipo para o lote de dados >>>
    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isSessionActive) return

            // Pega o dado serializável do Intent
            val serializableExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_BATCH, ArrayList::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getSerializableExtra(DataLayerListenerService.EXTRA_SENSOR_BATCH)
            }

            // Faz um cast seguro para o tipo que esperamos (ArrayList de SensorDataPoint)
            @Suppress("UNCHECKED_CAST")
            val batch = serializableExtra as? ArrayList<SensorDataPoint>

            batch?.let {
                if(it.isNotEmpty()) {
                    // Agora 'it' é um ArrayList<SensorDataPoint>, que é um subtipo de List,
                    // então a chamada para a função é segura.
                    processDataBatch(it)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço criado.")
        createNotificationChannel()
        // Registra o receiver para os dados do sensor
        val sensorIntentFilter = IntentFilter(DataLayerListenerService.ACTION_RECEIVE_SENSOR_DATA)
        LocalBroadcastManager.getInstance(this).registerReceiver(sensorDataReceiver, sensorIntentFilter)
        // Registra o receiver para os dados da bateria
        val batteryIntentFilter = IntentFilter(DataLayerListenerService.ACTION_RECEIVE_BATTERY_DATA)
        LocalBroadcastManager.getInstance(this).registerReceiver(batteryDataReceiver, batteryIntentFilter)
    }

    private fun processDataBatch(batch: List<SensorDataPoint>) {
        statusUpdateJob?.cancel()
        sendStatusUpdate("Recebendo Dados...")

        synchronized(dataQueue) {
            batch.forEach { dataPoint ->
                if (dataQueue.size >= MAX_DATA_POINTS_FOR_CHART) dataQueue.removeFirst()
                dataQueue.addLast(dataPoint)
            }
            sendDataUpdate(dataQueue.toList())
        }

        serviceScope.launch {
            val success = sendBatchDirectly(batch)
            if (!success) {
                Log.w(TAG, "Envio direto falhou. Salvando lote no banco para envio posterior.")
                saveBatchAndScheduleUpload(batch)
            }
        }

        statusUpdateJob = serviceScope.launch {
            delay(3000L)
            sendStatusUpdate("Pausado")
        }
    }

    private suspend fun sendBatchDirectly(batch: List<SensorDataPoint>): Boolean {
        if (!isSessionActive) return false
        val patientId = currentPatientName ?: return false
        val sessionId = currentSessionId ?: return false

        return try {
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
                if (response.isSuccessful) {
                    Log.i(TAG, "Lote enviado com sucesso pela rota expressa.")
                    true
                } else {
                    Log.w(TAG, "Falha na rota expressa: Código ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção de rede na rota expressa: ${e.message}")
            false
        }
    }

    private fun saveBatchAndScheduleUpload(batch: List<SensorDataPoint>) {
        val patientId = currentPatientName ?: return
        val sessionId = currentSessionId ?: return
        serviceScope.launch(Dispatchers.IO) {
            val jsonArray = JSONArray()
            batch.forEach { dp ->
                val dataObject = JSONObject().apply {
                    put("timestamp", dp.timestamp)
                    put("x", dp.values[0])
                    put("y", dp.values[1])
                    put("z", dp.values[2])
                }
                jsonArray.put(dataObject)
            }
            val jsonDataString = jsonArray.toString()
            val batchToSave = SensorDataBatch(
                sessionId = sessionId,
                patientId = patientId,
                jsonData = jsonDataString
            )
            sensorDataRepository.saveBatchForUpload(batchToSave)
            Log.d(TAG, "Lote salvo localmente via repositório.")
            scheduleUpload()
        }
    }

    // --- O RESTO DO SEU CÓDIGO PERMANECE O MESMO ---

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        if (isSessionActive) return
        Log.d(TAG, "Iniciando monitoramento para $patientName, sessão $sessionId")
        currentPatientName = patientName
        currentSessionId = sessionId
        isSessionActive = true
        dataQueue.clear()
        val notification = createNotification("Coleta de dados ativa para $patientName")
        startForeground(NOTIFICATION_ID, notification)
        sendCommandToWatch(DataLayerConstants.START_COMMAND)
        sendSessionStateUpdate()
        sendStatusUpdate("Sessão iniciada")
    }

    private fun stopMonitoring() {
        if (!isSessionActive) return
        Log.d(TAG, "Parando monitoramento da sessão.")
        val sessionToStop = currentSessionId
        isSessionActive = false
        sendSessionStateUpdate()
        sendStatusUpdate("Sessão finalizada, conectado.")
        sendCommandToWatch(DataLayerConstants.STOP_COMMAND)

        currentPatientName?.let {
            val payload = JSONObject().apply { put("patientId", it) }
            socket?.emit("session_stopped_by_client", payload)
        }
        if (sessionToStop != null) {
            serviceScope.launch(Dispatchers.IO) {
                sensorDataRepository.clearPendingBatchesForSession(sessionToStop)
                WorkManager.getInstance(applicationContext).cancelUniqueWork("unique_upload_work")
            }
        }
        currentSessionId = null
        stopForeground(false)
    }

    private fun scheduleUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val uploadWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "unique_upload_work",
            ExistingWorkPolicy.REPLACE,
            uploadWorkRequest
        )
        Log.d(TAG, "Tarefa de upload agendada com WorkManager.")
    }

    private fun connectToSocket() {
        if (currentPatientName == null) return
        try {
            val socketUrl = BuildConfig.SERVER_URL.replace("http", "ws")
            socket?.disconnect()
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
            stopMonitoring()
        }
        socket?.on(Socket.EVENT_DISCONNECT) { Log.d(TAG, "Socket desconectado.") }
    }

    private fun sendWatchStatusToServer() {
        val patient = currentPatientName ?: return
        val battery = currentWatchBatteryLevel ?: return
        val payload = JSONObject().apply {
            put("patientId", patient)
            put("batteryLevel", battery)
        }
        socket?.emit("watch_status_update", payload)
        val intent = Intent(ACTION_WATCH_STATUS_UPDATE).apply {
            putExtra(EXTRA_BATTERY_LEVEL, battery)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun requestStartSessionOnServer(patientName: String) {
        serviceScope.launch {
            try {
                val serverUrl = "${BuildConfig.SERVER_URL}/api/start_session"
                val jsonObject = JSONObject().apply { put("patientId", patientName) }
                val requestBody = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(serverUrl).post(requestBody).build()
                networkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        sendStatusUpdate("Erro ao iniciar sessão")
                    }
                }
            } catch (e: Exception) {
                sendStatusUpdate("Erro de rede")
            }
        }
    }

    private fun sendCommandToWatch(command: String) {
        val nodeClient = Wearable.getNodeClient(this)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                val payload = command.toByteArray(StandardCharsets.UTF_8)
                messageClient.sendMessage(node.id, DataLayerConstants.CONTROL_PATH, payload)
            }
        }
    }

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

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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

