package com.gabriel.wear.presentation

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Objeto singleton para manter o estado do monitoramento no app do relógio.
 * Isso permite que a UI e o Serviço compartilhem o estado de forma desacoplada.
 */
object MonitoringStateHolder {
    val isMonitoring = MutableStateFlow(false)
}
