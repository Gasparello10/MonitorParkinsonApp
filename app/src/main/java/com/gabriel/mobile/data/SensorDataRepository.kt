package com.gabriel.mobile.data

import com.gabriel.shared.SensorDataPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Um repositório singleton para centralizar o fluxo de dados do sensor.
 *
 * Isso desacopla o DataLayerListenerService (que recebe os dados) do MainViewModel (que os consome),
 * permitindo que a UI reaja aos dados sem estar diretamente ligada ao ciclo de vida do serviço.
 */
object SensorDataRepository {

    // Usamos um MutableSharedFlow para emitir eventos para todos os coletores ativos.
    // O replay = 0 significa que novos coletores não receberão dados antigos.
    private val _sensorDataFlow = MutableSharedFlow<SensorDataPoint>(replay = 0)
    val sensorDataFlow = _sensorDataFlow.asSharedFlow()

    /**
     * Chamado pelo DataLayerListenerService para adicionar um novo ponto de dados ao fluxo.
     */
    suspend fun addDataPoint(dataPoint: SensorDataPoint) {
        _sensorDataFlow.emit(dataPoint)
    }
}