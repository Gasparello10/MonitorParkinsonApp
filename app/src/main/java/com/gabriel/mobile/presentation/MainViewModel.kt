package com.gabriel.mobile.presentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.mobile.service.MonitoringService
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class Patient(val id: String = UUID.randomUUID().toString(), val name: String)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()
    private val sharedPreferences = application.getSharedPreferences("patient_prefs", Context.MODE_PRIVATE)
    private val nodeClient by lazy { Wearable.getNodeClient(application) }

    // <<< NOVO >>> ID único para identificar este aparelho no servidor.
    private val deviceId: String = getOrCreateDeviceId()

    private val _patients = MutableStateFlow<List<Patient>>(emptyList())
    val patients = _patients.asStateFlow()

    private val _selectedPatient = MutableStateFlow<Patient?>(null)
    val selectedPatient = _selectedPatient.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive = _isSessionActive.asStateFlow()

    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode = _isInSelectionMode.asStateFlow()

    private val _selectedForDeletion = MutableStateFlow<Set<String>>(emptySet())
    val selectedForDeletion = _selectedForDeletion.asStateFlow()

    private val _status = MutableStateFlow("Iniciando...")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _sensorDataPoints = MutableStateFlow<List<SensorDataPoint>>(emptyList())
    val sensorDataPoints = _sensorDataPoints.asStateFlow()

    // <<< CORRIGIDO >>> BroadcastReceiver agora lida com todos os casos corretamente.
    private val serviceUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MonitoringService.ACTION_STATUS_UPDATE -> {
                    _status.value = intent.getStringExtra(MonitoringService.EXTRA_STATUS_MESSAGE) ?: "Status desconhecido"
                }
                MonitoringService.ACTION_SESSION_STATE_UPDATE -> {
                    _isSessionActive.value = intent.getBooleanExtra(MonitoringService.EXTRA_IS_SESSION_ACTIVE, false)
                }
                // Case para quando o dashboard seleciona um paciente remotamente
                MonitoringService.ACTION_PATIENT_SELECTED_BY_DASHBOARD -> {
                    val patientId = intent.getStringExtra(MonitoringService.EXTRA_PATIENT_ID)
                    if (patientId != null) {
                        val patient = _patients.value.find { it.id == patientId }
                        if (patient != null) {
                            _selectedPatient.value = patient
                            _status.value = "Ativo: ${patient.name}"
                            Log.d("ViewModel", "Paciente '${patient.name}' foi ativado remotamente pelo dashboard.")
                        }
                    }
                }
                MonitoringService.ACTION_NEW_DATA_UPDATE -> {
                    val dataPointsJson = intent.getStringExtra(MonitoringService.EXTRA_DATA_POINTS)
                    if (dataPointsJson != null) {
                        val type = object : TypeToken<List<SensorDataPoint>>() {}.type
                        _sensorDataPoints.value = gson.fromJson(dataPointsJson, type)
                    }
                }
            }
        }
    }

    init {
        // <<< ALTERADO >>> Registra o receiver para ouvir TODOS os eventos do serviço.
        val intentFilter = IntentFilter().apply {
            addAction(MonitoringService.ACTION_STATUS_UPDATE)
            addAction(MonitoringService.ACTION_SESSION_STATE_UPDATE)
            addAction(MonitoringService.ACTION_NEW_DATA_UPDATE)
            addAction(MonitoringService.ACTION_PATIENT_SELECTED_BY_DASHBOARD) // Adiciona o novo listener
        }
        LocalBroadcastManager.getInstance(application).registerReceiver(serviceUpdateReceiver, intentFilter)

        checkConnection()
        loadPatients()

        // <<< NOVO >>> Inicia a conexão com o servidor assim que o app abre.
        connectServiceOnStartup()
    }

    // <<< NOVO >>> Função para gerar e salvar um ID único para este aparelho.
    private fun getOrCreateDeviceId(): String {
        val devicePrefs = getApplication<Application>().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        var id = devicePrefs.getString("device_id", null)
        if (id == null) {
            id = "Android_${UUID.randomUUID().toString().substring(0, 8)}"
            devicePrefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    // <<< NOVO >>> Função que inicia o serviço em background e manda ele conectar.
    private fun connectServiceOnStartup() {
        val serviceIntent = Intent(getApplication(), MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_CONNECT
            putExtra(MonitoringService.EXTRA_DEVICE_NAME, deviceId)
            putExtra(MonitoringService.EXTRA_PATIENT_LIST, gson.toJson(_patients.value))
        }
        getApplication<Application>().startService(serviceIntent)
        Log.d("ViewModel", "Comando ACTION_CONNECT enviado ao serviço para o dispositivo $deviceId")
    }

    // --- LÓGICA DE GERENCIAMENTO DE PACIENTES ---
    fun addPatient(name: String) {
        val newPatient = Patient(name = name)
        val updatedList = _patients.value + newPatient
        _patients.value = updatedList
        savePatients(updatedList)
        // <<< NOVO >>> Após adicionar um paciente, reconecta para atualizar a lista no servidor.
        connectServiceOnStartup()
    }

    // <<< ALTERADO >>> A seleção de um paciente na tela do celular agora é apenas uma ação local.
    // Ela não dispara mais uma conexão de rede.
    fun selectPatient(patient: Patient) {
        if (!_isSessionActive.value) {
            _selectedPatient.value = patient
            _status.value = "Selecionado: ${patient.name}"
            Log.d("ViewModel", "Seleção local do paciente ${patient.name}")
        } else {
            Log.w("ViewModel", "Não é possível trocar de paciente durante uma sessão ativa.")
        }
    }

    fun deleteSelectedPatients() {
        val selection = _selectedForDeletion.value
        if (selection.isEmpty()) {
            exitSelectionMode()
            return
        }
        if (_selectedPatient.value != null && selection.contains(_selectedPatient.value!!.id)) {
            if (_isSessionActive.value) {
                stopSession()
            }
            _selectedPatient.value = null
        }
        val updatedList = _patients.value.filter { !selection.contains(it.id) }
        _patients.value = updatedList
        savePatients(updatedList)
        // <<< NOVO >>> Após deletar, reconecta para atualizar a lista no servidor.
        connectServiceOnStartup()
        exitSelectionMode()
    }

    // ... (enterSelectionMode, toggleSelection, exitSelectionMode permanecem iguais)
    fun enterSelectionMode(initialPatientId: String) { /* ... seu código ... */ }
    fun toggleSelection(patientId: String) { /* ... seu código ... */ }
    fun exitSelectionMode() { /* ... seu código ... */ }

    // --- FUNÇÕES DE CONTROLE DO SERVIÇO (sem alterações) ---
    fun startSession(sessionId: Int) { /* ... seu código ... */ }
    fun stopSession() { /* ... seu código ... */ }

    private fun savePatients(patients: List<Patient>) { /* ... seu código ... */ }
    private fun loadPatients() { /* ... seu código ... */ }
    fun checkConnection() { /* ... seu código ... */ }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(serviceUpdateReceiver)
    }
}