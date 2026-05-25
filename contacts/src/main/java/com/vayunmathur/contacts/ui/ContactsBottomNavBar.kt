package com.vayunmathur.contacts.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
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
            icon = { Icon(painterResource(R.drawable.person_24px), contentDescription = null) },
            label = { Text(stringResource(R.string.contacts)) },
            selected = currentRoute is Route.ContactsList,
            onClick = { if (currentRoute !is Route.ContactsList) backStack.setLast(Route.ContactsList) }
        )
        NavigationBarItem(
            icon = { Icon(painterResource(R.drawable.baseline_group_24), contentDescription = null) },
            label = { Text(stringResource(R.string.groups)) },
            selected = currentRoute is Route.GroupsList,
            onClick = { if (currentRoute !is Route.GroupsList) backStack.setLast(Route.GroupsList()) }
        )
        NavigationBarItem(
            icon = { IconSettings() },
            label = { Text(stringResource(R.string.settings)) },
            selected = currentRoute is Route.Settings,
            onClick = { if (currentRoute !is Route.Settings) backStack.setLast(Route.Settings) }
        )
    }
}
