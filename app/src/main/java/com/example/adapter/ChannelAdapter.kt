package com.example.adapter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.databinding.ItemChannelBinding
import com.example.model.Channel

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private val channelsList = ArrayList<Channel>()
    private var playingChannelUrl: String? = null

    fun setChannels(newChannels: List<Channel>) {
        channelsList.clear()
        channelsList.addAll(newChannels)
        notifyDataSetChanged()
    }

    fun setPlayingChannelUrl(url: String?) {
        val oldUrl = playingChannelUrl
        playingChannelUrl = url
        
        for (i in channelsList.indices) {
            val itemUrl = channelsList[i].streamUrl
            if (itemUrl == oldUrl || itemUrl == url) {
                notifyItemChanged(i)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channelsList[position], position)
    }

    override fun getItemCount(): Int = channelsList.size

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        
        init {
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate()
                        .scaleX(1.04f)
                        .scaleY(1.04f)
                        .setDuration(150)
                        .start()
                    view.setBackgroundResource(R.drawable.bg_channel_item_focused)
                    binding.itemChannelName.isSelected = true
                } else {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .start()
                    view.setBackgroundResource(R.drawable.bg_channel_item_normal)
                    binding.itemChannelName.isSelected = false
                }
            }

            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelClick(channelsList[position])
                }
            }
        }

        fun bind(channel: Channel, position: Int) {
            binding.itemChannelName.text = channel.name
            binding.itemChannelNumber.text = channel.number?.toString() ?: (position + 1).toString()
            binding.itemChannelGroup.text = channel.groupTitle ?: "GENERAL"

            val isPlaying = channel.streamUrl == playingChannelUrl
            binding.itemPlayingIndicator.isVisible = isPlaying

            if (!channel.logoUrl.isNullOrEmpty()) {
                binding.itemChannelLogo.load(channel.logoUrl) {
                    crossfade(enable = true)
                    bitmapConfig(Bitmap.Config.RGB_565)
                    placeholder(R.drawable.ic_default_channel_logo)
                    error(R.drawable.ic_default_channel_logo)
                }
            } else {
                binding.itemChannelLogo.setImageResource(R.drawable.ic_default_channel_logo)
            }
        }
    }
}
