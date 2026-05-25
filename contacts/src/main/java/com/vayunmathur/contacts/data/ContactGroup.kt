package com.vayunmathur.contacts.data

import com.vayunmathur.library.util.DatabaseItem

data class ContactGroup(
    override val id: Long = 0,
    val name: String
) : DatabaseItem
