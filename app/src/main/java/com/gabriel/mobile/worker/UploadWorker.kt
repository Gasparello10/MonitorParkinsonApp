package com.gabriel.mobile.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gabriel.mobile.BuildConfig
import com.gabriel.mobile.data.local.AppDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class UploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val batchDao = AppDatabase.getDatabase(appContext).sensorDataBatchDao()
    private val networkClient = OkHttpClient()

    override suspend fun doWork(): Result {
        // Pega o lote mais antigo que ainda não foi enviado
        val pendingBatch = batchDao.getOldestPendingBatch()
            ?: return Result.success().also {
                Log.d("UploadWorker", "Nenhum lote pendente encontrado. Trabalho concluído.")
            }

        Log.d("UploadWorker", "Encontrado lote pendente (id: ${pendingBatch.id}). Tentando enviar...")

        return try {
            val serverUrl = "${BuildConfig.SERVER_URL}/data"

            val rootJsonObject = JSONObject().apply {
                put("patientId", pendingBatch.patientId)
                put("sessao_id", pendingBatch.sessionId)
                // O jsonData já é uma string de array JSON, então podemos usá-la para construir um JSONArray
                put("data", org.json.JSONArray(pendingBatch.jsonData))
            }

            val requestBody = rootJsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(serverUrl).post(requestBody).build()

            networkClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i("UploadWorker", "Lote ${pendingBatch.id} enviado com sucesso! Deletando do banco local.")
                    // Deleta o lote do banco de dados APENAS se o envio for bem-sucedido
                    batchDao.deleteBatch(pendingBatch)
                    Result.success()
                } else {
                    Log.w("UploadWorker", "Falha no envio do lote ${pendingBatch.id}: ${response.code}. Tentando novamente mais tarde.")
                    Result.retry() // Informa ao WorkManager para tentar de novo mais tarde
                }
            }
        } catch (e: Exception) {
            Log.e("UploadWorker", "Erro de rede/exceção ao enviar o lote ${pendingBatch.id}.", e)
            Result.retry() // Tenta novamente em caso de falha de conexão
        }
    }
}