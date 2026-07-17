package com.vayunmathur.contacts.ui

import com.vayunmathur.library.ui.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack

@Composable
fun ContactsBottomNavBar(backStack: NavBackStack<Route>) {
    val currentRoute = backStack.last()
    NavigationBar {
        NavigationBarItem(
            icon = { IconPerson() },
            label = { Text(stringResource(R.string.contacts)) },
            selected = currentRoute is Route.ContactsList,
            onClick = { if (currentRoute !is Route.ContactsList) backStack.pop() }
        )
        NavigationBarItem(
            icon = { IconGroup() },
            label = { Text(stringResource(R.string.groups)) },
            selected = currentRoute is Route.GroupsList,
            onClick = {
                if (currentRoute !is Route.GroupsList) {
                    if (currentRoute is Route.Settings) backStack.pop()
                    backStack.add(Route.GroupsList())
                }
            }
        )
        NavigationBarItem(
            icon = { IconSettings() },
            label = { Text(stringResource(R.string.settings)) },
            selected = currentRoute is Route.Settings,
            onClick = {
                if (currentRoute !is Route.Settings) {
                    if (currentRoute is Route.GroupsList) backStack.pop()
                    backStack.add(Route.Settings)
                }
            }
        )
    }
}
