package com.gabriel.wear.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.gabriel.wear.R
import com.gabriel.wear.presentation.MainActivity
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException


class SensorWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val gson = Gson()
    private val dataBuffer = mutableListOf<SensorDataPoint>()

    companion object {
        const val WORK_NAME = "SensorCollectionWork"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
        private const val BATCH_SIZE = DataLayerConstants.BATCH_SIZE
        private const val TAG = "SensorWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker iniciado.")

        // 1. Foreground obrigatório no WorkManager
        val foregroundInfo = createForegroundInfo()
        setForeground(foregroundInfo)

        // 2. WakeLock para manter CPU ativa
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MyApp::SensorWakeLock"
        )

        try {
            wakeLock.acquire()
            Log.d(TAG, "WakeLock adquirido.")

            // 3. Receber dados via Flow
            callbackFlow {
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            val dataPoint = SensorDataPoint(
                                timestamp = System.currentTimeMillis(),
                                values = floatArrayOf(it.values[0], it.values[1], it.values[2])
                            )
                            trySend(dataPoint)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                Log.d(TAG, "Registrando listener do sensor.")
                sensorManager.registerListener(
                    listener,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_GAME
                )

                awaitClose {
                    Log.d(TAG, "Cancelando listener do sensor.")
                    sensorManager.unregisterListener(listener)
                }
            }.collect { dataPoint ->
                addDataToBuffer(dataPoint)
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "Worker cancelado, finalizando a coleta.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante a execução do Worker", e)
            return Result.failure()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "WakeLock liberado.")
            }
            Log.d(TAG, "Worker finalizado.")
        }

        return Result.success()
    }

    private fun addDataToBuffer(dataPoint: SensorDataPoint) {
        synchronized(dataBuffer) {
            dataBuffer.add(dataPoint)
            if (dataBuffer.size >= BATCH_SIZE) {
                val batchToSend = ArrayList(dataBuffer)
                dataBuffer.clear()
                CoroutineScope(coroutineContext).launch {
                    sendDataToPhone(batchToSend)
                }
            }
        }
    }

    private suspend fun sendDataToPhone(batch: List<SensorDataPoint>) {
        val serializedBatch = gson.toJson(batch).toByteArray(StandardCharsets.UTF_8)
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            nodes.forEach { node ->
                messageClient.sendMessage(
                    node.id,
                    DataLayerConstants.SENSOR_DATA_PATH,
                    serializedBatch
                ).await()
                Log.d(TAG, "Lote de ${batch.size} amostras enviado para ${node.displayName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao enviar lote para o celular", e)
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle("Monitorização Ativa")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)

        val touchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, touchIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val ongoingActivity = OngoingActivity.Builder(context, NOTIFICATION_ID, notificationBuilder)
            .setTouchIntent(pendingIntent)
            .setStatus(Status.Builder().addTemplate("Coletando dados...").build())
            .build()

        ongoingActivity.apply(context)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build())
        }
    }

    // Mantidas só por referência
    override fun onSensorChanged(event: SensorEvent?) {}
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
