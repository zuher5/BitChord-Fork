package com.music.bitchord.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The data layer behind the three tabs. Playback is deliberately not here:
 * the shell talks to Media3's MediaController directly ([rememberMediaController]),
 * so tabs can come and go without the queue ever losing an owner.
 */
class MainViewModel : ViewModel() {

    /** Home feed shelves; [UiState.Loading] on first entry and on refresh. */
    private val _home = MutableStateFlow<UiState<HomeFeed>>(UiState.Loading)
    val home: StateFlow<UiState<HomeFeed>> = _home

    private var homeContinuation: String? = null
    private val loadedShelves = mutableListOf<com.music.bitchord.data.model.HomeShelf>()

    /** Text completions for the search field's typeahead. */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    /** Committed search results, one page. */
    private val _results = MutableStateFlow<UiState<List<SearchResult>>>(UiState.Success(emptyList()))
    val results: StateFlow<UiState<List<SearchResult>>> = _results

    /** The filter the current results were fetched with, so the chips can show selection. */
    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter

    private var searchContinuation: String? = null

    init {
        loadHome()
    }

    /** Loads the first home page; YouTube's front page carries most shelves already. */
    fun loadHome() {
        _home.value = UiState.Loading
        loadedShelves.clear()
        homeContinuation = null
        viewModelScope.launch {
            YtMusicRepository.home().fold(
                onSuccess = { feed ->
                    loadedShelves += feed.shelves
                    homeContinuation = feed.continuation
                    _home.value = UiState.Success(HomeFeed(loadedShelves.toList(), feed.continuation))
                },
                onFailure = { e -> _home.value = UiState.Error(e.message ?: "Gagal memuat Home") },
            )
        }
    }

    /** Appends the next home page once the list scrolls near its end. */
    fun loadMoreHome() {
        val token = homeContinuation ?: return
        homeContinuation = null
        viewModelScope.launch {
            YtMusicRepository.moreHome(token).fold(
                onSuccess = { feed ->
                    loadedShelves += feed.shelves
                    homeContinuation = feed.continuation
                    _home.value = UiState.Success(HomeFeed(loadedShelves.toList(), feed.continuation))
                },
                onFailure = { homeContinuation = token },
            )
        }
    }

    /** Typeahead: text completions, debounced so keystrokes don't spam the network. */
    @OptIn(FlowPreview::class)
    fun onSearchInput(input: String) {
        inputSource.value = input
    }

    private val inputSource = MutableStateFlow("")

    init {
        viewModelScope.launch {
            inputSource.debounce(200).distinctUntilChanged().collect { input ->
                if (input.isBlank()) {
                    _suggestions.value = emptyList()
                } else {
                    YtMusicRepository.searchSuggestions(input).fold(
                        onSuccess = { _suggestions.value = it },
                        onFailure = { _suggestions.value = emptyList() },
                    )
                }
            }
        }
    }

    /** Commits a query. Results replace the typeahead view immediately. */
    fun search(query: String, filter: SearchFilter = _filter.value) {
        if (query.isBlank()) return
        _filter.value = filter
        _results.value = UiState.Loading
        viewModelScope.launch {
            YtMusicRepository.searchPage(query, filter).fold(
                onSuccess = { page ->
                    searchContinuation = page.continuation
                    _results.value = UiState.Success(page.rows)
                },
                onFailure = { e -> _results.value = UiState.Error(e.message ?: "Pencarian gagal") },
            )
        }
    }

    /** Appends the next result page when the list reaches its end. */
    fun loadMoreResults(query: String) {
        val token = searchContinuation ?: return
        searchContinuation = null
        viewModelScope.launch {
            YtMusicRepository.searchContinuation(token, _filter.value).fold(
                onSuccess = { page ->
                    searchContinuation = page.continuation
                    val current = (_results.value as? UiState.Success)?.data.orEmpty()
                    _results.value = UiState.Success(current + page.rows)
                },
                onFailure = { searchContinuation = token },
            )
        }
    }
}
