package rocks.urzahil.iptv.ui.sources

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import rocks.urzahil.iptv.data.db.entities.EpgSourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.repo.EditableSource
import rocks.urzahil.iptv.data.repo.SourceForm
import rocks.urzahil.iptv.data.repo.XtreamLoginResult
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.sync.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SourcesViewModel(private val c: AppContainer) : ViewModel() {
    val sources: StateFlow<List<SourceEntity>?> =
        c.sourceRepository.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun progress(sourceId: Long): Flow<SyncProgress?> = c.syncScheduler.observeProgress(sourceId)

    fun refresh(s: SourceEntity) {
        c.syncScheduler.syncPlaylist(s.id, manual = true, needsNetwork = s.sourceType != SourceType.M3U_FILE)
    }

    fun setEnabled(s: SourceEntity, enabled: Boolean) {
        viewModelScope.launch { c.sourceRepository.setEnabled(s.id, enabled) }
    }

    fun delete(s: SourceEntity) {
        c.syncScheduler.cancelSource(s.id)
        viewModelScope.launch { c.sourceRepository.delete(s.id) }
    }
}

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Done(val result: XtreamLoginResult) : TestState
}

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Failed(val message: String) : SaveState
    data object Saved : SaveState
}

/** Shared by the add and edit screens. */
class SourceEditorViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.sourceRepository

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    private val _save = MutableStateFlow<SaveState>(SaveState.Idle)
    val save: StateFlow<SaveState> = _save.asStateFlow()

    private val _editable = MutableStateFlow<EditableSource?>(null)
    val editable: StateFlow<EditableSource?> = _editable.asStateFlow()

    fun load(id: Long) { viewModelScope.launch { _editable.value = repo.editable(id) } }

    fun epgSources(ownerId: Long): Flow<List<EpgSourceEntity>> = repo.observeEpgSources(ownerId)

    fun testXtream(form: SourceForm) {
        _test.value = TestState.Running
        viewModelScope.launch {
            _test.value = try {
                TestState.Done(repo.testXtream(form))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Done(XtreamLoginResult.Failed("The test could not be completed"))
            }
        }
    }

    fun resetTest() { _test.value = TestState.Idle }

    fun add(type: SourceType, form: SourceForm, fileUri: Uri? = null) {
        _save.value = SaveState.Saving
        viewModelScope.launch {
            _save.value = try {
                val id = repo.add(type, form, fileUri)
                c.syncScheduler.syncPlaylist(id, manual = true, needsNetwork = type != SourceType.M3U_FILE)
                SaveState.Saved
            } catch (e: IllegalArgumentException) {
                SaveState.Failed(e.message ?: "Invalid input")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. the Keystore failed to encrypt the credentials: show it instead of crashing.
                SaveState.Failed("Could not save the source securely. Try again.")
            }
        }
    }

    fun update(id: Long, form: SourceForm) {
        _save.value = SaveState.Saving
        viewModelScope.launch {
            _save.value = try {
                repo.update(id, form)
                val s = repo.get(id)
                c.syncScheduler.syncPlaylist(id, manual = true, needsNetwork = s?.sourceType != SourceType.M3U_FILE)
                SaveState.Saved
            } catch (e: IllegalArgumentException) {
                SaveState.Failed(e.message ?: "Invalid input")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. the Keystore failed to encrypt the credentials: show it instead of crashing.
                SaveState.Failed("Could not save the source securely. Try again.")
            }
        }
    }

    /** [onResult] gets null on success, else a message. */
    fun addEpgUrl(ownerId: Long, url: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val id = repo.addEpgUrl(ownerId, url)
                c.syncScheduler.syncEpg(id, ownerId, force = true)
                onResult(null)
            } catch (e: IllegalArgumentException) {
                onResult(e.message ?: "Invalid address")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult("Could not save the source securely. Try again.")
            }
        }
    }

    fun removeEpg(id: Long) { viewModelScope.launch { repo.removeEpgSource(id) } }

    fun consumeSaveError() { _save.value = SaveState.Idle }
}
