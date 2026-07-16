package com.vayunmathur.everysync.provider

import com.vayunmathur.everysync.provider.impl.GenericCalDavProvider
import com.vayunmathur.everysync.provider.impl.GenericCardDavProvider
import com.vayunmathur.everysync.provider.impl.GoogleHealthProvider
import com.vayunmathur.everysync.provider.impl.GoogleProvider
import com.vayunmathur.everysync.provider.impl.ICloudProvider
import com.vayunmathur.everysync.provider.impl.WithingsProvider

/** Static list of every provider EverySync ships. Drives the "Add account" grid. */
object ProviderRegistry {
    val all: List<SyncProvider> = listOf(
        GoogleProvider(),
        ICloudProvider(),
        GenericCalDavProvider(),
        GenericCardDavProvider(),
        WithingsProvider(),
        GoogleHealthProvider(),
    )

    fun get(id: String): SyncProvider? = all.firstOrNull { it.id == id }
}
