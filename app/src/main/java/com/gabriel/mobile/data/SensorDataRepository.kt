package com.gabriel.mobile.data

import android.util.Log
import com.gabriel.mobile.data.local.SensorDataBatch
import com.gabriel.mobile.data.local.SensorDataBatchDao

/**
 * Repositório que serve como uma "fachada" para as operações de dados.
 */
class SensorDataRepository(
    private val sensorDataBatchDao: SensorDataBatchDao
) {

    /**
     * Salva um lote de dados de sensor no banco de dados local (Room).
     */
    suspend fun saveBatchForUpload(batch: SensorDataBatch) {
        sensorDataBatchDao.insertBatch(batch)
    }

    /**
     * <<< NOVO: Limpa todos os lotes pendentes de uma sessão específica. >>>
     * Será chamado pelo MonitoringService quando uma sessão for explicitamente parada.
     */
    suspend fun clearPendingBatchesForSession(sessionId: Int) {
        sensorDataBatchDao.deleteBatchesBySessionId(sessionId)
        Log.i("SensorRepository", "Lotes pendentes para a sessão $sessionId foram limpos do banco de dados local.")
    }
}
