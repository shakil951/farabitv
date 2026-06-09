package com.example.utils

import com.example.model.Channel
import java.io.BufferedReader
import java.io.StringReader

import java.io.Reader

object M3uParser {
    fun parse(m3uContent: String): List<Channel> {
        return parse(StringReader(m3uContent))
    }

    fun parse(reader: Reader): List<Channel> {
        val channels = ArrayList<Channel>()
        val bufferedReader = BufferedReader(reader)
        var line: String?
        var currentInfo: String? = null
        var channelIndex = 1

        while (bufferedReader.readLine().also { line = it } != null) {
            val trimmedLine = line!!.trim()
            if (trimmedLine.isEmpty()) continue

            if (trimmedLine.startsWith("#EXTINF:")) {
                currentInfo = trimmedLine
            } else if (!trimmedLine.startsWith("#")) {
                // This is the stream URL
                if (currentInfo != null) {
                    val channel = parseExtInf(currentInfo, trimmedLine, channelIndex++)
                    channels.add(channel)
                    currentInfo = null
                }
            }
        }
        return channels
    }

    private fun parseExtInf(extInfLine: String, streamUrl: String, defaultNumber: Int): Channel {
        // Find logo
        val logoUrl = getValueForAttribute(extInfLine, "tvg-logo") 
            ?: getValueForAttribute(extInfLine, "logo")
        
        // Find group
        val groupTitle = getValueForAttribute(extInfLine, "group-title")

        // Find channel number
        val channelNumberStr = getValueForAttribute(extInfLine, "tvg-chno") 
            ?: getValueForAttribute(extInfLine, "channel-number")
        val channelNumber = channelNumberStr?.toIntOrNull() ?: defaultNumber

        // Find channel name
        val commaIndex = extInfLine.lastIndexOf(',')
        val name = if (commaIndex != -1 && commaIndex < extInfLine.length - 1) {
            extInfLine.substring(commaIndex + 1).trim()
        } else {
            "Channel $defaultNumber"
        }

        return Channel(
            name = if (name.isEmpty()) "Channel $defaultNumber" else name,
            streamUrl = streamUrl,
            logoUrl = logoUrl?.takeIf { it.isNotBlank() },
            groupTitle = groupTitle?.takeIf { it.isNotBlank() },
            number = channelNumber
        )
    }

    private fun getValueForAttribute(line: String, attributeName: String): String? {
        val searchStr = "$attributeName="
        val index = line.indexOf(searchStr)
        if (index == -1) return null

        val startQuoteIndex = index + searchStr.length
        if (startQuoteIndex >= line.length) return null

        val quoteChar = line[startQuoteIndex]
        if (quoteChar == '"' || quoteChar == '\'') {
            val endQuoteIndex = line.indexOf(quoteChar, startQuoteIndex + 1)
            if (endQuoteIndex != -1) {
                return line.substring(startQuoteIndex + 1, endQuoteIndex)
            }
        } else {
            // Unquoted string up to next space or comma
            var endIndex = line.indexOf(' ', startQuoteIndex)
            val commaIndex = line.indexOf(',', startQuoteIndex)
            if (endIndex == -1 || (commaIndex != -1 && commaIndex < endIndex)) {
                endIndex = commaIndex
            }
            if (endIndex != -1) {
                return line.substring(startQuoteIndex, endIndex)
            }
        }
        return null
    }
}
