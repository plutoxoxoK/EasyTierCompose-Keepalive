package cc.ptoe.easytier.compose.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.core.ProfileValidator
import cc.ptoe.easytier.compose.core.ValidationMessage
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.GlobalSettingsRepository
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.TunMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EasyTierUiState(
    val profiles: List<EasyTierProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val draft: EasyTierProfile? = null,
    val runtime: RuntimeStatus = RuntimeStatus.Stopped,
    val fieldErrors: Map<String, ValidationMessage> = emptyMap(),
    val globalSettings: GlobalSettings = GlobalSettings(),
)

class EasyTierViewModel(
    private val repository: ProfileRepository,
    private val coordinator: EasyTierRuntimeCoordinator,
    private val globalSettingsRepository: GlobalSettingsRepository,
) : ViewModel() {
    private val selectedId = MutableStateFlow<String?>(null)
    private val draft = MutableStateFlow<EasyTierProfile?>(null)
    private val errors = MutableStateFlow<Map<String, ValidationMessage>>(emptyMap())
    private val globalSettings = MutableStateFlow(GlobalSettings())
    private data class ProfileInputs(
        val profiles: List<EasyTierProfile>,
        val persistedSelection: String?,
        val localSelection: String?,
        val editing: EasyTierProfile?,
    )

    private val profileInputs = combine(
        repository.profiles,
        repository.selectedProfileId,
        selectedId,
        draft,
    ) { profiles, persistedSelection, localSelection, editing ->
        ProfileInputs(profiles, persistedSelection, localSelection, editing)
    }

    val state: StateFlow<EasyTierUiState> = combine(
        profileInputs,
        coordinator.status,
        errors,
        globalSettings,
    ) { inputs, runtime, fieldErrors, settings ->
        EasyTierUiState(
            profiles = inputs.profiles,
            selectedProfileId = inputs.localSelection
                ?: inputs.persistedSelection
                ?: inputs.profiles.firstOrNull()?.id,
            draft = inputs.editing,
            runtime = runtime,
            fieldErrors = fieldErrors,
            globalSettings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EasyTierUiState())


    init {
        viewModelScope.launch {
            globalSettingsRepository.settings.collect { globalSettings.value = it }
        }
        // Adopt only an already live in-process no-TUN core. Never auto-connect on resume.
        viewModelScope.launch {
            val profiles = repository.profiles.first()
            val settings = globalSettingsRepository.settings.first()
            coordinator.attachRunningInstance(settings) { id -> profiles.firstOrNull { it.id == id } }
        }
    }

    fun selectProfile(id: String?) = viewModelScope.launch { selectedId.value = id; repository.select(id) }
    fun beginCreate() { draft.value = repository.newProfile(); errors.value = emptyMap() }
    fun beginEdit(profile: EasyTierProfile) { draft.value = profile; errors.value = emptyMap() }
    fun discardDraft() { draft.value = null; errors.value = emptyMap() }
    fun updateDraft(transform: (EasyTierProfile) -> EasyTierProfile) { draft.value = draft.value?.let(transform) }

    fun saveDraft() = viewModelScope.launch {
        val value = draft.value ?: return@launch
        if (state.value.runtime.profileId == value.id && state.value.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
            errors.value = mapOf("form" to ValidationMessage.Resource(R.string.error_disconnect_before_save))
            return@launch
        }
        val validation = ProfileValidator().validate(value, globalSettings.value)
        if (validation.isNotEmpty()) { errors.value = validation; return@launch }
        repository.save(value)
        selectedId.value = value.id
        repository.select(value.id)
        draft.value = null
        errors.value = emptyMap()
    }

    fun delete(profile: EasyTierProfile) = viewModelScope.launch {
        if (state.value.runtime.profileId == profile.id && state.value.runtime.state != RuntimeState.STOPPED) return@launch
        repository.delete(profile.id)
    }

    fun updateTunMode(mode: TunMode) = viewModelScope.launch {
        val profile = selectedProfile() ?: return@launch
        if (mode != TunMode.NO_TUN || state.value.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) return@launch
        val updated = profile.copy(tunMode = mode)
        repository.save(updated)
        draft.value = draft.value?.takeIf { it.id == updated.id }?.copy(tunMode = mode)
    }

    fun updateStartOnBoot(enabled: Boolean) = viewModelScope.launch {
        val profile = selectedProfile() ?: return@launch
        val updated = profile.copy(startOnBoot = enabled)
        repository.save(updated)
        draft.value = draft.value?.takeIf { it.id == updated.id }?.copy(startOnBoot = enabled)
    }

    fun updateGlobalSettings(settings: GlobalSettings) = viewModelScope.launch {
        // Global settings may contain credentials; never log their values.
        // Optimistic update: reflect the change immediately so toggles respond
        // without waiting for the DataStore write round-trip. The repository
        // collector then stays in sync with the persisted value.
        globalSettings.value = settings
        globalSettingsRepository.update(settings)
    }

    fun connect() = viewModelScope.launch { selectedProfile()?.let { coordinator.start(it, globalSettings.value) } }
    fun disconnect() = viewModelScope.launch { coordinator.stop() }
    fun resetProfiles() = viewModelScope.launch { coordinator.stop(); repository.reset(); selectedId.value = null }
    private fun selectedProfile() = state.value.profiles.firstOrNull { it.id == state.value.selectedProfileId }

    companion object {
        private const val TAG = "EasyTierSettings"
    }
}
