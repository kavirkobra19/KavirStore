package com.kavir.store.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kavir.store.BuildConfig
import com.kavir.store.R
import com.kavir.store.data.CatalogApp
import com.kavir.store.data.CATEGORY_IDS
import com.kavir.store.data.formatSize
import com.kavir.store.data.sdkToAndroid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title) }, actions = actions)
}

// ---- Home ---------------------------------------------------------------------------------------

@Composable
fun HomeScreen(vm: StoreViewModel) {
    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.app_name)) {
            IconButton(onClick = { vm.loadCatalog() }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }
        if (vm.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (val s = vm.catalog) {
            is CatalogState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is CatalogState.Error -> CenterMessage(
                stringResource(R.string.load_failed),
                stringResource(R.string.load_failed_hint),
            ) { Button(onClick = { vm.loadCatalog() }) { Text(stringResource(R.string.retry)) } }
            is CatalogState.Ready -> {
                if (vm.apps.isEmpty()) {
                    CenterMessage(stringResource(R.string.empty_catalog))
                } else {
                    HomeContent(vm, s.offline)
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(vm: StoreViewModel) {
    val count = vm.updatable.size
    if (count == 0) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.updates_available) + " (" + count + ")",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.updateAll() }) { Text(stringResource(R.string.update_all)) }
        }
    }
}

@Composable
private fun HomeContent(vm: StoreViewModel, offline: Boolean) {
    val fa = isFarsi()
    LazyColumn(Modifier.fillMaxSize()) {
        if (offline) {
            item {
                Text(
                    stringResource(R.string.offline_cached),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        item { UpdateBanner(vm) }
        item { SectionTitle(stringResource(R.string.featured)) }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.featured, key = { "f-" + it.packageName }) { app ->
                    Card(
                        modifier = Modifier
                            .width(140.dp)
                            .clickable { vm.push(Route.Detail(app.packageName)) },
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppIcon(app, 72.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                app.displayName(fa),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                app.release?.versionName ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.recently_updated)) }
        items(vm.recent.take(6), key = { "r-" + it.packageName }) { app ->
            AppRow(vm, app) { vm.push(Route.Detail(app.packageName)) }
        }
        item { SectionTitle(stringResource(R.string.all_apps)) }
        items(vm.apps.sortedBy { it.name.lowercase() }, key = { "a-" + it.packageName }) { app ->
            AppRow(vm, app) { vm.push(Route.Detail(app.packageName)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---- Categories ---------------------------------------------------------------------------------

@Composable
fun CategoriesScreen(vm: StoreViewModel) {
    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.tab_categories))
        val apps = vm.apps
        val ids = listOf("all") + CATEGORY_IDS.filter { id -> apps.any { it.category == id } }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ids, key = { it }) { id ->
                val count = if (id == "all") apps.size else apps.count { it.category == id }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.push(Route.Category(id)) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(categoryLabel(id), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.app_count, count.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(vm: StoreViewModel, id: String) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(categoryLabel(id)) },
                navigationIcon = {
                    IconButton(onClick = { vm.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val list = (if (id == "all") vm.apps else vm.apps.filter { it.category == id })
            .sortedBy { it.name.lowercase() }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (list.isEmpty()) item { CenterMessage(stringResource(R.string.no_results)) }
            items(list, key = { it.packageName }) { app ->
                AppRow(vm, app) { vm.push(Route.Detail(app.packageName)) }
            }
        }
    }
}

// ---- Search -------------------------------------------------------------------------------------

@Composable
fun SearchScreen(vm: StoreViewModel) {
    val fa = isFarsi()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
        )
        val q = vm.query.trim().lowercase()
        val results: List<CatalogApp> = if (q.isEmpty()) vm.recent else vm.apps.filter {
            it.name.lowercase().contains(q) ||
                (it.nameFa ?: "").lowercase().contains(q) ||
                it.displayDescription(fa).lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (results.isEmpty()) item { CenterMessage(stringResource(R.string.no_results)) }
            items(results, key = { it.packageName }) { app ->
                AppRow(vm, app) { vm.push(Route.Detail(app.packageName)) }
            }
        }
    }
}

// ---- My apps ------------------------------------------------------------------------------------

@Composable
fun MyAppsScreen(vm: StoreViewModel) {
    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.tab_my_apps)) {
            IconButton(onClick = {
                vm.loadCatalog()
                vm.refreshInstalled()
            }) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh)) }
        }
        val updates = vm.updatable
        val others = vm.installedApps.filter { a -> updates.none { it.packageName == a.packageName } }
        LazyColumn(Modifier.fillMaxSize()) {
            if (updates.isNotEmpty()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle(stringResource(R.string.updates_available) + " (" + updates.size + ")", Modifier.weight(1f))
                        Button(onClick = { vm.updateAll() }) { Text(stringResource(R.string.update_all)) }
                    }
                }
                items(updates, key = { "u-" + it.packageName }) { app ->
                    AppRow(vm, app) { vm.push(Route.Detail(app.packageName)) }
                }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.installed_apps)) }
                items(others, key = { "i-" + it.packageName }) { app ->
                    AppRow(vm, app) { vm.push(Route.Detail(app.packageName)) }
                }
            }
            if (updates.isEmpty() && others.isEmpty()) {
                item { CenterMessage(stringResource(R.string.nothing_installed)) }
            } else if (updates.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_updates),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

// ---- Settings -----------------------------------------------------------------------------------

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun SettingsScreen(vm: StoreViewModel, onLanguageChanged: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.tab_settings))
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionTitle(stringResource(R.string.settings_language)) }
            item {
                RadioRow(stringResource(R.string.lang_system), vm.language == "system") {
                    if (vm.language != "system") { vm.setLanguage("system"); onLanguageChanged() }
                }
                RadioRow(stringResource(R.string.lang_en), vm.language == "en") {
                    if (vm.language != "en") { vm.setLanguage("en"); onLanguageChanged() }
                }
                RadioRow(stringResource(R.string.lang_fa), vm.language == "fa") {
                    if (vm.language != "fa") { vm.setLanguage("fa"); onLanguageChanged() }
                }
            }
            item { SectionTitle(stringResource(R.string.settings_theme)) }
            item {
                RadioRow(stringResource(R.string.theme_system), vm.theme == "system") { vm.setTheme("system") }
                RadioRow(stringResource(R.string.theme_light), vm.theme == "light") { vm.setTheme("light") }
                RadioRow(stringResource(R.string.theme_dark), vm.theme == "dark") { vm.setTheme("dark") }
            }
            item { SectionTitle(stringResource(R.string.settings_about)) }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // Five quick taps open the admin sign-in. It is useless without a valid GitHub token.
                        .clickable {
                            taps += 1
                            if (taps >= 5) {
                                taps = 0
                                vm.push(Route.Admin)
                            }
                        }
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.version) + " " + BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.catalog_source, BuildConfig.CATALOG_REPO),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---- Detail -------------------------------------------------------------------------------------

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: StoreViewModel, pkg: String) {
    val app = vm.appByPackage(pkg)
    val fa = isFarsi()
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app?.displayName(fa) ?: "") },
                navigationIcon = {
                    IconButton(onClick = { vm.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (app != null && vm.installed.containsKey(app.packageName)) {
                        TextButton(onClick = { vm.uninstall(app.packageName) }) {
                            Text(stringResource(R.string.uninstall))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (app == null) {
            Box(Modifier.padding(padding).fillMaxSize()) { CenterMessage(stringResource(R.string.no_results)) }
            return@Scaffold
        }
        val r = app.release
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 84.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.displayName(fa), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            categoryLabel(app.category),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (r != null) {
                            Text(
                                r.versionName + " · " + formatSize(r.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ActionArea(vm, app, compact = false)
                    val have = vm.installed[app.packageName]
                    if (have != null && vm.statusOf(app) == AppStatus.Update) {
                        Text(
                            stringResource(R.string.installed_version, have.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (app.screenshots.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.screenshots), Modifier.padding(top = 8.dp)) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(app.screenshots, key = { it }) { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 135.dp, height = 240.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                }
            }
            val desc = app.displayDescription(fa)
            if (desc.isNotBlank()) {
                item { SectionTitle(stringResource(R.string.description), Modifier.padding(top = 8.dp)) }
                item { Text(desc, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            if (r != null) {
                item { SectionTitle(stringResource(R.string.whats_new), Modifier.padding(top = 8.dp)) }
                item {
                    Text(
                        r.changelog.trim().ifEmpty { stringResource(R.string.no_changelog) },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item { SectionTitle(stringResource(R.string.information), Modifier.padding(top = 8.dp)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        InfoRow(stringResource(R.string.version), r.versionName + " (" + r.versionCode + ")")
                        InfoRow(stringResource(R.string.size), formatSize(r.size))
                        InfoRow(stringResource(R.string.min_android), sdkToAndroid(r.minSdk))
                        InfoRow(stringResource(R.string.updated), r.publishedAt.take(10))
                        InfoRow(stringResource(R.string.package_name), app.packageName)
                    }
                }
            }
            if (app.repoUrl.isNotBlank()) {
                item {
                    AssistChip(
                        onClick = { uri.openUri(app.repoUrl) },
                        label = { Text(stringResource(R.string.source_code)) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
