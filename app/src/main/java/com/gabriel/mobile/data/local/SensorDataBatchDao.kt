package com.gabriel.mobile.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO (Data Access Object) para interagir com a tabela de lotes pendentes.
 */
@Dao
interface SensorDataBatchDao {

    @Insert
    suspend fun insertBatch(batch: SensorDataBatch)

    @Query("SELECT * FROM pending_batches ORDER BY createdAt ASC LIMIT 1")
    suspend fun getOldestPendingBatch(): SensorDataBatch?

    // <<< ALTERAÇÃO: Nova função para buscar múltiplos lotes de uma vez >>>
    @Query("SELECT * FROM pending_batches ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getOldestPendingBatches(limit: Int): List<SensorDataBatch>

    @Delete
    suspend fun deleteBatch(batch: SensorDataBatch)

    // <<< ALTERAÇÃO: Nova função para deletar múltiplos lotes de uma vez >>>
    @Delete
    suspend fun deleteBatches(batches: List<SensorDataBatch>)

    @Query("DELETE FROM pending_batches WHERE sessionId = :sessionId")
    suspend fun deleteBatchesBySessionId(sessionId: Int)
}

