package com.example.model

data class Channel(
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val number: Int? = null
)
