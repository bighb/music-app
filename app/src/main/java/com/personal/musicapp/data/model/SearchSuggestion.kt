package com.personal.musicapp.data.model

data class SearchSuggestion(
    val keyword: String,
    val subtitle: String? = null,
    val isHistory: Boolean = false,
    val source: String = "QQ"
)
