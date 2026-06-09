package com.example

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.adapter.ChannelAdapter
import com.example.databinding.ActivityMainBinding
import com.example.model.Channel
import com.example.viewmodel.TvPlayerViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : androidx.activity.ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TvPlayerViewModel by viewModels()
    private lateinit var adapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastOkPressTime: Long = 0
    private var numericInput = ""
    private var lastPlayedChannel: Channel? = null
    private var retryCount = 0

    // Clock update runnable (runs every 10 seconds)
    private val clockRunnable = object : Runnable {
        override fun run() {
            val calendar = Calendar.getInstance()
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            binding.hudClock.text = format.format(calendar.time)
            mainHandler.postDelayed(this, 10000)
        }
    }

    // Auto-hide playback controls HUD panel
    private val hideHudRunnable = Runnable {
        binding.playerHudOverlay.isVisible = false
        binding.dimOverlay.isVisible = binding.sidebarContainer.isVisible
    }

    // Auto-schedule numeric entry channel-switching trigger
    private val numericDialRunnable = Runnable {
        val number = numericInput.toIntOrNull()
        if (number != null) {
            val success = viewModel.playChannelByNumber(number)
            if (!success) {
                Toast.makeText(this@MainActivity, getString(R.string.channel_not_found, number), Toast.LENGTH_SHORT).show()
            }
        }
        binding.numericDialOverlay.isVisible = false
        numericInput = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupRecyclerView()
        setupPlayer()
        setupCategoryTabs()
        observeViewModel()

        binding.btnExitApp.setOnClickListener {
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var lastBackPressTime = 0L

            override fun handleOnBackPressed() {
                if (binding.sidebarContainer.isVisible) {
                    hideSidebar()
                } else {
                    val currentTime = System.currentTimeMillis()
                    if ((currentTime - lastBackPressTime) < 2000) {
                        finish()
                    } else {
                        showSidebar()
                        Toast.makeText(this@MainActivity, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                    }
                    lastBackPressTime = currentTime
                }
            }
        })

        // Start active clock interval ticking
        clockRunnable.run()
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter { channel ->
            // On Item Clicked from Sidebar list
            viewModel.playChannel(channel)
            hideSidebar()
        }
        binding.channelListRecycler.layoutManager = LinearLayoutManager(this)
        binding.channelListRecycler.adapter = adapter
    }

    private fun setupPlayer() {
        // Optimize LoadControl for low-memory/low-end devices
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // minBufferMs
                50000, // maxBufferMs
                2500,  // bufferForPlaybackMs
                5000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
        binding.playerView.player = player

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        binding.loadingProgress.isVisible = true
                        binding.hudLiveBadge.text = getString(R.string.buffering)
                        binding.hudLiveBadge.setTextColor("#3B82F6".toColorInt())
                    }
                    Player.STATE_READY -> {
                        binding.loadingProgress.isVisible = false
                        binding.notificationBanner.isVisible = false
                        binding.hudLiveBadge.text = getString(R.string.live_stream)
                        binding.hudLiveBadge.setTextColor("#10B981".toColorInt())
                        retryCount = 0
                    }
                    Player.STATE_ENDED -> {
                        binding.loadingProgress.isVisible = false
                    }
                    Player.STATE_IDLE -> {
                        // Do nothing
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.loadingProgress.isVisible = false
                if (retryCount < 3) {
                    retryCount++
                    binding.notificationBanner.isVisible = true
                    binding.notificationText.text = getString(R.string.stream_offline_retrying, retryCount)
                    mainHandler.postDelayed({
                        lastPlayedChannel?.let { playStream(it) }
                    }, 2000)
                } else {
                    binding.notificationBanner.isVisible = true
                    binding.notificationText.text = getString(R.string.stream_failed)
                }
            }
        })
    }

    private fun setupCategoryTabs() {
        binding.tabAll.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.tabAll.setBackgroundColor("#3B82F6".toColorInt())
                binding.tabAll.setTextColor(android.graphics.Color.WHITE)
                viewModel.changeFilterMode(TvPlayerViewModel.FilterMode.ALL)
            } else {
                binding.tabAll.setBackgroundColor("#1F2937".toColorInt())
                binding.tabAll.setTextColor("#9CA3AF".toColorInt())
            }
        }

        binding.tabRecent.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.tabRecent.setBackgroundColor("#3B82F6".toColorInt())
                binding.tabRecent.setTextColor(android.graphics.Color.WHITE)
                viewModel.changeFilterMode(TvPlayerViewModel.FilterMode.RECENTS)
            } else {
                binding.tabRecent.setBackgroundColor("#1F2937".toColorInt())
                binding.tabRecent.setTextColor("#9CA3AF".toColorInt())
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. Observe Playlist Loading UI state
                launch {
                    viewModel.uiState.collectLatest { state ->
                        when (state) {
                            is TvPlayerViewModel.UiState.Loading -> {
                                if (viewModel.playingChannel.value == null) {
                                    binding.loadingProgress.visibility = View.VISIBLE
                                }
                                binding.notificationBanner.visibility = View.GONE
                            }
                            is TvPlayerViewModel.UiState.Success -> {
                                binding.loadingProgress.isVisible = false
                                binding.notificationBanner.isVisible = false
                                val channelsCount = state.channels.size
                                binding.sidebarChannelCount.text = getString(R.string.channels_loaded, channelsCount)
                            }
                            is TvPlayerViewModel.UiState.Error -> {
                                binding.loadingProgress.visibility = View.GONE
                                binding.notificationBanner.visibility = View.VISIBLE
                                binding.notificationText.text = state.message
                            }
                        }
                    }
                }

                // 2. Observe channels collection mapped into RecyclerView adapter
                launch {
                    viewModel.displayedChannels.collectLatest { channels ->
                        adapter.setChannels(channels)
                    }
                }

                // 3. Observe active selected played channel
                launch {
                    viewModel.playingChannel.collectLatest { channel ->
                        if (channel != null) {
                            lastPlayedChannel = channel
                            adapter.setPlayingChannelUrl(channel.streamUrl)
                            playStream(channel)
                            updateHudOverlay(channel)
                            showHudOverlay(true)
                        }
                    }
                }
            }
        }
    }

    private fun playStream(channel: Channel) {
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
            val mediaItem = MediaItem.fromUri(channel.streamUrl)
            p.setMediaItem(mediaItem)
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun updateHudOverlay(channel: Channel) {
        binding.hudChannelName.text = channel.name
        
        val pos = viewModel.getPlayingChannelIndex()
        val numDisplay = channel.number?.toString() ?: if (pos != -1) (pos + 1).toString() else "CH"
        binding.hudChannelNumber.text = getString(R.string.channel_number_display, numDisplay)

        if (!channel.logoUrl.isNullOrEmpty()) {
            binding.hudChannelLogo.load(channel.logoUrl) {
                bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                placeholder(R.drawable.ic_default_channel_logo)
                error(R.drawable.ic_default_channel_logo)
            }
        } else {
            binding.hudChannelLogo.setImageResource(R.drawable.ic_default_channel_logo)
        }
    }

    private fun showHudOverlay(show: Boolean, durationMs: Long = 4000) {
        mainHandler.removeCallbacks(hideHudRunnable)
        if (show) {
            binding.playerHudOverlay.isVisible = true
            binding.dimOverlay.isVisible = binding.sidebarContainer.isVisible
            mainHandler.postDelayed(hideHudRunnable, durationMs)
        } else {
            binding.playerHudOverlay.isVisible = false
        }
    }

    private fun showSidebar() {
        binding.sidebarContainer.visibility = View.VISIBLE
        binding.dimOverlay.visibility = View.VISIBLE
        
        // Hide player controls to clear screen spacing
        showHudOverlay(false)

        // Focus adapter active playing channel item if available
        val activeIndex = viewModel.getPlayingChannelIndex()
        if (activeIndex != -1 && viewModel.filterMode.value == TvPlayerViewModel.FilterMode.ALL) {
            binding.channelListRecycler.scrollToPosition(activeIndex)
            binding.channelListRecycler.post {
                val holder = binding.channelListRecycler.findViewHolderForAdapterPosition(activeIndex)
                holder?.itemView?.requestFocus() ?: binding.channelListRecycler.requestFocus()
            }
        } else {
            binding.channelListRecycler.requestFocus()
        }
    }

    private fun hideSidebar() {
        binding.sidebarContainer.visibility = View.GONE
        binding.dimOverlay.visibility = View.GONE
    }

    private fun handleNumericInput(digit: String) {
        mainHandler.removeCallbacks(numericDialRunnable)
        numericInput += digit
        binding.numericDialText.text = numericInput
        binding.numericDialOverlay.visibility = View.VISIBLE
        mainHandler.postDelayed(numericDialRunnable, 1500)
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                Toast.makeText(this, "PAUSED", Toast.LENGTH_SHORT).show()
                binding.hudLiveBadge.text = getString(R.string.playback_paused)
                binding.hudLiveBadge.setTextColor("#F59E0B".toColorInt())
            } else {
                p.play()
                Toast.makeText(this, "PLAYING", Toast.LENGTH_SHORT).show()
                binding.hudLiveBadge.text = getString(R.string.live_stream)
                binding.hudLiveBadge.setTextColor("#10B981".toColorInt())
            }
            showHudOverlay(true)
        }
    }

    private fun adjustVolume(raise: Boolean): Boolean {
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
        return true
    }

    @SuppressLint("PredictiveBack")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Track for long BACK press or DPAD long pressing
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            event.startTracking()
            return true
        }

        // Numeric entry dials
        val digit: String? = when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
            else -> null
        }
        if (digit != null) {
            handleNumericInput(digit)
            return true
        }

        // Player navigation controls: when list drawer is hidden
        if (binding.sidebarContainer.visibility != View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    viewModel.playPreviousChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    viewModel.playNextChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    adjustVolume(raise = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    adjustVolume(raise = true)
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.isTracking && !event.isCanceled) {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }

        // DPAD Center clicks
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.isTracking && !event.isCanceled) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastOkPressTime < 300) {
                    togglePlayPause()
                } else {
                    if (binding.sidebarContainer.visibility != View.VISIBLE) {
                        showHudOverlay(true)
                    }
                }
                lastOkPressTime = currentTime
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Exit application upon long pressing BACK key on remote
            finish()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Show HUD stream information bar for longer span (e.g. 10s)
            showHudOverlay(true, durationMs = 10000)
            return true
        }

        return super.onKeyLongPress(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            setupPlayer()
        }
        mainHandler.removeCallbacks(clockRunnable)
        clockRunnable.run()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(clockRunnable)
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
