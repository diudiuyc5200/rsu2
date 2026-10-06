package org.bakasu.bakasu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bakasu.bakasu.domain.model.CatalogModule
import org.bakasu.bakasu.domain.model.ModuleCatalogFailure
import org.bakasu.bakasu.domain.model.ModuleCatalogResult
import org.bakasu.bakasu.domain.model.ModuleReleaseAsset
import org.bakasu.bakasu.domain.usecase.FetchRemoteTextUseCase
import org.bakasu.bakasu.domain.usecase.GetCatalogModuleUseCase
import org.bakasu.bakasu.domain.usecase.ObserveCatalogModulesUseCase
import org.bakasu.bakasu.domain.usecase.ObserveRepositorySourcesUseCase

data class ModuleDetailUiState(
    val module: CatalogModule? = null,
    val loading: Boolean = true,
    val error: ModuleCatalogFailure? = null,
    val documents: Map<String, ModuleDocumentState> = emptyMap(),
)

data class ModuleDocumentState(val text: String? = null, val loading: Boolean = true, val failed: Boolean = false)

sealed interface ModuleDetailUiAction {
    data object Retry : ModuleDetailUiAction
}

class ModuleDetailViewModel(
    private val moduleId: String,
    private val repositoryUrl: String,
    private val getModule: GetCatalogModuleUseCase,
    private val fetchText: FetchRemoteTextUseCase,
    private val observeModules: ObserveCatalogModulesUseCase,
    private val observeSources: ObserveRepositorySourcesUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        observeModules().value.firstOrNull { module ->
            module.moduleId == moduleId && module.repositoryUrl == repositoryUrl &&
                observeSources.sources.value.any { it.url == repositoryUrl }
        }?.let { ModuleDetailUiState(module = it, loading = false) } ?: ModuleDetailUiState(),
    )

    val state: StateFlow<ModuleDetailUiState> = mutableState.asStateFlow()

    fun resolveAsset(asset: ModuleReleaseAsset): CatalogModule? {
        if (observeSources.sources.value.none { it.url == repositoryUrl }) return null
        return observeModules().value.firstOrNull { module ->
            module.moduleId == moduleId && module.repositoryUrl == repositoryUrl &&
                module.releases.any { release -> release.assets.any { it.hasSameIdentity(asset) } }
        }
    }

    init {
        if (mutableState.value.module == null) load()
        viewModelScope.launch {
            combine(observeModules(), observeSources.sources) { modules, sources ->
                val configured = sources.any { it.url == repositoryUrl }
                configured to modules.firstOrNull { it.moduleId == moduleId && it.repositoryUrl == repositoryUrl }
            }.collect { (configured, module) ->
                if (!configured || (module == null && mutableState.value.module != null)) {
                    mutableState.value = ModuleDetailUiState(loading = false, error = ModuleCatalogFailure.NotFound)
                } else if (module != null) {
                    mutableState.update { it.copy(module = module, loading = false, error = null) }
                }
            }
        }
    }

    fun dispatch(action: ModuleDetailUiAction) {
        when (action) {
            ModuleDetailUiAction.Retry -> load()
        }
    }

    private fun load() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            when (val result = getModule(moduleId, repositoryUrl)) {
                is ModuleCatalogResult.Success -> mutableState.update {
                    it.copy(module = result.value, loading = false, error = null)
                }

                is ModuleCatalogResult.Failure -> {
                    mutableState.update { it.copy(loading = false, error = result.reason) }
                }
            }
        }
    }

    fun loadDocument(url: String, retry: Boolean = false) {
        val document = mutableState.value.documents[url]
        if (url.isBlank() || document?.loading == true || (!retry && document != null)) return
        mutableState.update { it.copy(documents = it.documents + (url to ModuleDocumentState())) }
        viewModelScope.launch {
            val result = fetchText(url)
            mutableState.update {
                it.copy(
                    documents = it.documents + (
                        url to ModuleDocumentState(
                            text = result.getOrNull(),
                            loading = false,
                            failed = result.isFailure,
                        )
                        ),
                )
            }
        }
    }
}
