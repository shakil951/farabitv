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
            val cached = withContext(Dispatchers.IO) {
                PlaylistCache.openInputStream(getApplication())?.use { inputStream ->
                    M3uParser.parse(inputStream.bufferedReader())
                }
            }
            if (!cached.isNullOrEmpty()) {
                _allChannels.value = cached
                _uiState.value = UiState.Success(cached)
                loadRecents(cached)
                autoPlayLastWatched(cached)
            }
            refreshPlaylist()
        }
    }

    fun refreshPlaylist() {
        viewModelScope.launch {
            try {
                val combinedList = withContext(Dispatchers.IO) {
                    val url1 = URL("https://raw.githubusercontent.com/shakil951/PlaylistCheck/main/combined_playlist.m3u")
                    val url2 = URL("https://raw.githubusercontent.com/FunctionError/PiratesTv/main/combined_playlist.m3u")
                    
                    val list1: List<Channel> = try {
                        url1.openStream().use { M3uParser.parse(it.bufferedReader()) }
                    } catch (e: Exception) { emptyList() }
                    
                    val list2: List<Channel> = try {
                        url2.openStream().use { M3uParser.parse(it.bufferedReader()) }
                    } catch (e: Exception) { emptyList() }
                    
                    val result = mutableListOf<Channel>()
                    var currentNumber = 1
                    
                    for (ch in list1) {
                        result.add(Channel(
                            name = ch.name,
                            streamUrl = ch.streamUrl,
                            logoUrl = ch.logoUrl,
                            groupTitle = ch.groupTitle,
                            number = currentNumber++
                        ))
                    }
                    
                    for (ch in list2) {
                        result.add(Channel(
                            name = ch.name,
                            streamUrl = ch.streamUrl,
                            logoUrl = ch.logoUrl,
                            groupTitle = ch.groupTitle,
                            number = currentNumber++
                        ))
                    }
                    
                    result
                }
                
                if (combinedList.isNotEmpty()) {
                    _allChannels.value = combinedList
                    _uiState.value = UiState.Success(combinedList)
                    loadRecents(combinedList)
                    if (_playingChannel.value == null) {
                        autoPlayLastWatched(combinedList)
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
        
        val currentRecents = TvPreferences.getRecentUrls(getApplication()).toMutableList()
        currentRecents.remove(channel.streamUrl)
        currentRecents.add(0, channel.streamUrl)
        if (currentRecents.size > 20) {
            currentRecents.removeAt(currentRecents.size - 1)
        }
        TvPreferences.saveRecentUrls(getApplication(), currentRecents)
        
        loadRecents(_allChannels.value)
    }

    fun getPlayingChannelIndex(): Int {
        val current = _playingChannel.value ?: return -1
        val channels = _allChannels.value
        val index = channels.indexOf(current)
        if (index != -1) return index
        
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
        
        val found = channels.find { it.number == number }
        
        return if (found != null) {
            playChannel(found)
            true
        } else {
            false
        }
    }
}
