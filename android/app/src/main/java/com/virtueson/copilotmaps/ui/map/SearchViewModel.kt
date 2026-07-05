package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Place
import com.virtueson.copilotmaps.data.PlacesRepository
import com.virtueson.copilotmaps.data.PlacesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
)

class SearchViewModel(
    private val repository: PlacesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun onQueryChange(text: String) {
        _state.value = _state.value.copy(query = text)
    }

    fun submit(origin: GeoPoint) {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _state.value = when (val r = repository.searchPlaces(q, origin, null)) {
                is PlacesResult.Success ->
                    _state.value.copy(results = r.places, loading = false, searched = true, error = null)
                is PlacesResult.Failure ->
                    _state.value.copy(results = emptyList(), loading = false, searched = true, error = r.reason)
            }
        }
    }

    fun clear() {
        _state.value = SearchUiState()
    }
}

/** Google price-level enum → "$".."$$$$" ("" when free/unknown/null). */
fun priceSymbol(priceLevel: String?): String = when (priceLevel) {
    "PRICE_LEVEL_INEXPENSIVE" -> "$"
    "PRICE_LEVEL_MODERATE" -> "$$"
    "PRICE_LEVEL_EXPENSIVE" -> "$$$"
    "PRICE_LEVEL_VERY_EXPENSIVE" -> "$$$$"
    else -> ""
}

class SearchViewModelFactory(
    private val repository: PlacesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SearchViewModel(repository) as T
}
