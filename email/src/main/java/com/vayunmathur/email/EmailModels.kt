package com.vayunmathur.email

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class EmailFolder(
    @PrimaryKey val fullName: String,
    val name: String,
    val parentFullName: String? = null,
    val delimiter: String = "/"
)

@Serializable
@Entity(primaryKeys = ["id", "folderName"])
data class EmailMessage(
    val id: Long,
    val folderName: String,
    val subject: String,
    val from: String,
    val date: String,
    val body: String? = null,
    val isHtml: Boolean = false
)
