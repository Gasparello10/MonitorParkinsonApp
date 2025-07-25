package com.gabriel.wear.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import com.gabriel.shared.DataLayerConstants
import com.gabriel.wear.R
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SensorService"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        // MUDANÇA: Adicionando log no início do onCreate
        Log.d(TAG, "onCreate: Serviço está sendo criado.")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Coleta de Dados do Sensor",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUDANÇA: Adicionando log no início do onStartCommand
        Log.d(TAG, "onStartCommand: Recebido comando com ação: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopForegroundService()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        Log.d(TAG, "startForegroundService: Iniciando serviço em primeiro plano.")
        val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Monitoramento Ativo")
            .setContentText("Coletando dados do acelerômetro.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            MonitoringStateHolder.isMonitoring.value = true
            Log.d(TAG, "Listener do acelerômetro registrado. Estado: monitorando")
        }
    }

    private fun stopForegroundService() {
        Log.d(TAG, "stopForegroundService: Parando serviço.")
        sensorManager.unregisterListener(this)
        MonitoringStateHolder.isMonitoring.value = false
        Log.d(TAG, "Listener do acelerômetro cancelado. Estado: parado")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val sensorValues = event.values.clone()
            val timestamp = event.timestamp
            serviceScope.launch {
                sendSensorData(timestamp, sensorValues)
            }
        }
    }

    private suspend fun sendSensorData(timestamp: Long, values: FloatArray) {
        try {
            val putDataMapRequest = PutDataMapRequest.create(DataLayerConstants.SENSOR_DATA_PATH).apply {
                dataMap.putLong(DataLayerConstants.KEY_TIMESTAMP, timestamp)
                dataMap.putFloatArray(DataLayerConstants.KEY_VALUES, values)
            }
            val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()
            dataClient.putDataItem(putDataRequest).await()
            // Log.d(TAG, "Dados do sensor enviados: timestamp=$timestamp") // Desabilitado para não poluir o log
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao enviar dados do sensor", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Serviço está sendo destruído.")
        serviceScope.cancel()
        MonitoringStateHolder.isMonitoring.value = false
        sensorManager.unregisterListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
