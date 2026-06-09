package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.Channel
import com.example.utils.M3uParser
import com.example.utils.PlaylistCache
import com.example.utils.TvPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class TvPlayerViewModel(application: Application) : AndroidViewModel(application) {

    sealed class UiState {
        object Loading : UiState()
        data class Success(val channels: List<Channel>) : UiState()
        data class Error(val message: String) : UiState()
    }

    enum class FilterMode {
        ALL, RECENTS
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    private val _filterMode = MutableStateFlow(FilterMode.ALL)
    val filterMode: StateFlow<FilterMode> = _filterMode

    private val _playingChannel = MutableStateFlow<Channel?>(null)
    val playingChannel: StateFlow<Channel?> = _playingChannel

    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())
    private val _recentChannels = MutableStateFlow<List<Channel>>(emptyList())

    val displayedChannels: StateFlow<List<Channel>> = combine(_allChannels, _recentChannels, _filterMode) { all, recents, mode ->
        when (mode) {
            FilterMode.ALL -> all
            FilterMode.RECENTS -> recents
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            // Load cached first for speed
            val cached = withContext(Dispatchers.IO) {
                PlaylistCache.openInputStream(getApplication())?.use { inputStream ->
                    M3uParser.parse(inputStream.bufferedReader())
                }
            }
            if (!cached.isNullOrEmpty()) {
                val sorted = cached.sortedBy { it.number ?: Int.MAX_VALUE }
                _allChannels.value = sorted
                _uiState.value = UiState.Success(sorted)
                loadRecents(sorted)
                autoPlayLastWatched(sorted)
            }
            // Then refresh from network
            refreshPlaylist()
        }
    }

    fun refreshPlaylist() {
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val url = URL("https://raw.githubusercontent.com/FunctionError/PiratesTv/main/combined_playlist.m3u")
                    url.openStream().use { input ->
                        PlaylistCache.save(getApplication(), input)
                    }
                    
                    PlaylistCache.openInputStream(getApplication())?.use { inputStream ->
                        M3uParser.parse(inputStream.bufferedReader())
                    }
                }
                
                if (!parsed.isNullOrEmpty()) {
                    val sorted = parsed.sortedBy { it.number ?: Int.MAX_VALUE }
                    _allChannels.value = sorted
                    _uiState.value = UiState.Success(sorted)
                    loadRecents(sorted)
                    if (_playingChannel.value == null) {
                        autoPlayLastWatched(sorted)
                    }
                } else if (_allChannels.value.isEmpty()) {
                    _uiState.value = UiState.Error("Playlist is empty or failed to load")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (_allChannels.value.isEmpty()) {
                    _uiState.value = UiState.Error(e.message ?: "Failed can not connect to playlist server")
                }
            }
        }
    }

    private fun autoPlayLastWatched(channels: List<Channel>) {
        val lastUrl = TvPreferences.getLastWatchedUrl(getApplication())
        if (lastUrl != null) {
            val lastChannel = channels.find { it.streamUrl == lastUrl }
            if (lastChannel != null) {
                playChannel(lastChannel)
                return
            }
        }
        if (channels.isNotEmpty()) playChannel(channels[0])
    }

    private fun loadRecents(allChannels: List<Channel>) {
        val recentUrls = TvPreferences.getRecentUrls(getApplication())
        val recents = recentUrls.mapNotNull { url ->
            allChannels.find { it.streamUrl == url }
        }
        _recentChannels.value = recents
    }

    fun changeFilterMode(mode: FilterMode) {
        _filterMode.value = mode
    }

    fun playChannel(channel: Channel) {
        _playingChannel.value = channel
        TvPreferences.saveLastWatchedUrl(getApplication(), channel.streamUrl)
        
        // Add to recents
        val currentRecents = TvPreferences.getRecentUrls(getApplication()).toMutableList()
        currentRecents.remove(channel.streamUrl)
        currentRecents.add(0, channel.streamUrl)
        if (currentRecents.size > 20) {
            currentRecents.removeAt(currentRecents.size - 1)
        }
        TvPreferences.saveRecentUrls(getApplication(), currentRecents)
        
        // Refresh recents list
        loadRecents(_allChannels.value)
    }

    fun getPlayingChannelIndex(): Int {
        val current = _playingChannel.value ?: return -1
        val channels = _allChannels.value
        // First try finding the exact object
        val index = channels.indexOf(current)
        if (index != -1) return index
        
        // Fallback to comparing key properties if object reference is different
        return channels.indexOfFirst { 
            (it.streamUrl == current.streamUrl) && (it.name == current.name) && (it.number == current.number)
        }
    }

    fun playNextChannel() {
        val channels = _allChannels.value
        if (channels.isEmpty()) return
        val currentIndex = getPlayingChannelIndex()
        val nextIndex = if (currentIndex == -1 || currentIndex >= channels.size - 1) {
            0
        } else {
            currentIndex + 1
        }
        playChannel(channels[nextIndex])
    }

    fun playPreviousChannel() {
        val channels = _allChannels.value
        if (channels.isEmpty()) return
        val currentIndex = getPlayingChannelIndex()
        val prevIndex = if (currentIndex <= 0) {
            channels.size - 1
        } else {
            currentIndex - 1
        }
        playChannel(channels[prevIndex])
    }

    fun playChannelByNumber(number: Int): Boolean {
        val channels = _allChannels.value
        if (channels.isEmpty()) return false
        
        var found = channels.find { it.number == number }
        if (found == null && number in 1..channels.size) {
            found = channels[number - 1]
        }
        
        return if (found != null) {
            playChannel(found)
            true
        } else {
            false
        }
    }
}
