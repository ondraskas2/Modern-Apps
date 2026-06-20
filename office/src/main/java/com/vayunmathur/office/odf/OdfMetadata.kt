package com.vayunmathur.office.odf

data class OdfMetadata(
    val title: String? = null,
    val author: String? = null,
    val creator: String? = null,
    val creationDate: String? = null,
    val modifiedDate: String? = null,
    val description: String? = null,
    val subject: String? = null,
    val keywords: List<String> = emptyList(),
    val pageCount: Int? = null,
    val wordCount: Int? = null,
    val fileSize: Long? = null
)
