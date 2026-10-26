package com.pintoinsta.app.data.model

data class SearchKeyword(
    val id: Long = 0,
    val keyword: String,
    val createdAt: Long = System.currentTimeMillis()
)
