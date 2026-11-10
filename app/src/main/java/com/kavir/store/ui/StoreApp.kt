package com.kavir.store.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kavir.store.R

@Composable
fun StoreApp(vm: StoreViewModel, admin: AdminViewModel, onLanguageChanged: () -> Unit) {
    val context = LocalContext.current

    BackHandler(enabled = vm.stack.isNotEmpty() || vm.tab != Tab.Home) {
        if (!vm.pop()) vm.tab = Tab.Home
    }

    Box(Modifier.fillMaxSize()) {
        when (val route = vm.stack.lastOrNull()) {
            null -> TabsScaffold(vm, onLanguageChanged)
            is Route.Detail -> DetailScreen(vm, route.pkg)
            is Route.Category -> CategoryScreen(vm, route.id)
            Route.Admin -> AdminScreen(vm, admin)
        }
    }

    if (vm.showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { vm.showPermissionDialog = false },
            title = { Text(stringResource(R.string.perm_title)) },
            text = { Text(stringResource(R.string.perm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.showPermissionDialog = false
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.packageName),
                    )
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.perm_open)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.showPermissionDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TabsScaffold(vm: StoreViewModel, onLanguageChanged: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                TabItem(vm, Tab.Home, Icons.Default.Home, R.string.tab_home)
                TabItem(vm, Tab.Categories, Icons.Default.Menu, R.string.tab_categories)
                TabItem(vm, Tab.Search, Icons.Default.Search, R.string.tab_search)
                TabItem(vm, Tab.MyApps, Icons.Default.Favorite, R.string.tab_my_apps)
                TabItem(vm, Tab.Settings, Icons.Default.SettingsIcon, R.string.tab_settings)
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
            when (vm.tab) {
                Tab.Home -> HomeScreen(vm)
                Tab.Categories -> CategoriesScreen(vm)
                Tab.Search -> SearchScreen(vm)
                Tab.MyApps -> MyAppsScreen(vm)
                Tab.Settings -> SettingsScreen(vm, onLanguageChanged)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    vm: StoreViewModel,
    tab: Tab,
    icon: ImageVector,
    label: Int,
) {
    NavigationBarItem(
        selected = vm.tab == tab,
        onClick = { vm.tab = tab },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(label), maxLines = 1) },
    )
}
