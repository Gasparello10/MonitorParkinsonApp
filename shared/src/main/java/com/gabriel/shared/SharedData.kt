package com.gabriel.shared

import java.io.Serializable

/**
 * Objeto para armazenar constantes usadas na comunicação via Data Layer.
 * Usado por ambos os módulos para garantir consistência.
 */
object DataLayerConstants {
    // Caminho para os eventos de início/parada da medição (se necessário no futuro)
    const val BATTERY_PATH = "/battery_level"
    const val BATTERY_KEY = "battery_level_key"
    const val CONTROL_PATH = "/control"
    const val START_COMMAND = "start"
    const val STOP_COMMAND = "stop"
    // Caminho para o envio dos dados do acelerômetro
    const val SENSOR_DATA_PATH = "/sensor_data"
    const val BATCH_SIZE = 10
    const val KEY_TIMESTAMP = "timestamp"

    const val KEY_VALUES = "values" // Array de Floats (x, y, z)

    // Caminho para as mensagens de "ping" do celular para o relógio
    const val PING_PATH = "/ping"
}

/**
 * Modelo de dados para uma única leitura do sensor.
 * Implementa Serializable para que possa ser facilmente enviado entre módulos.
 *
 * @param timestamp O momento em que a leitura foi feita.
 * @param values Um array de 3 floats representando os eixos [x, y, z].
 */
data class SensorDataPoint(
    val timestamp: Long,
    val values: FloatArray
) : Serializable {
    // Sobrescrevendo equals e hashCode para comparar arrays corretamente.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SensorDataPoint
        if (timestamp != other.timestamp) return false
        if (!values.contentEquals(other.values)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + values.contentHashCode()
        return result
    }
}
