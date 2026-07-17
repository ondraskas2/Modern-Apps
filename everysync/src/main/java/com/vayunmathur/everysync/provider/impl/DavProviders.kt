package com.vayunmathur.everysync.provider.impl

import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.DavCredentials
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.library.ui.IconProvider

/**
 * Apple / iCloud, delivered as a preconfigured CalDAV + CardDAV account (Apple has
 * no public REST API). Requires an app-specific password. Contacts and calendars
 * live on different iCloud hosts.
 */
class ICloudProvider : DavProvider(
    id = "icloud",
    displayName = "Apple / iCloud",
    icon = { IconProvider() },
    capabilities = setOf(DataType.CONTACTS, DataType.CALENDAR),
    davPresetUrl = "https://caldav.icloud.com",
) {
    override fun contactsBaseUrl(config: AccountConfig, creds: DavCredentials) = "https://contacts.icloud.com"
    override fun calendarBaseUrl(config: AccountConfig, creds: DavCredentials) = "https://caldav.icloud.com"
}

/** Generic CalDAV server (RFC 4791). User supplies the collection/principal URL. */
class GenericCalDavProvider : DavProvider(
    id = "caldav",
    displayName = "CalDAV server",
    icon = { IconProvider() },
    capabilities = setOf(DataType.CALENDAR),
)

/** Generic CardDAV server (RFC 6352). User supplies the collection/principal URL. */
class GenericCardDavProvider : DavProvider(
    id = "carddav",
    displayName = "CardDAV server",
    icon = { IconProvider() },
    capabilities = setOf(DataType.CONTACTS),
)
