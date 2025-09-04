package com.gabriel.mobile.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BatteryReadingDao {

    @Insert
    suspend fun insert(reading: BatteryReading)

    /**
     * <<< NOVO: Busca um lote das leituras de bateria mais antigas. >>>
     * @param limit O número máximo de leituras a serem buscadas.
     * @return Uma lista de leituras de bateria.
     */
    @Query("SELECT * FROM pending_battery_readings ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getOldestReadings(limit: Int): List<BatteryReading>

    /**
     * <<< NOVO: Deleta uma lista de leituras que já foram enviadas com sucesso. >>>
     */
    @Delete
    suspend fun deleteReadings(readings: List<BatteryReading>)

    // Função para limpar os dados quando uma sessão for deletada (boa prática)
    @Query("DELETE FROM pending_battery_readings WHERE sessionId = :sessionId")
    suspend fun deleteReadingsBySessionId(sessionId: Int)
}

