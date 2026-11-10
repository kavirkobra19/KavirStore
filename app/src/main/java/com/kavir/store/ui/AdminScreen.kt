package com.kavir.store.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kavir.store.R
import com.kavir.store.data.CATEGORY_IDS
import com.kavir.store.data.SourceApp

@Composable
private fun msgText(m: UiMsg): String =
    if (m.arg != null) stringResource(m.res, m.arg) else stringResource(m.res)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(store: StoreViewModel, admin: AdminViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceApp?>(null) }
    var removing by remember { mutableStateOf<SourceApp?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_title)) },
                navigationIcon = {
                    IconButton(onClick = { store.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (admin.authorized) {
                        IconButton(onClick = { admin.refreshReleases() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.admin_refresh))
                        }
                        IconButton(onClick = { showAdd = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.admin_add_app))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (admin.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            admin.message?.let {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msgText(it), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { admin.clearMessage() }) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
            if (!admin.authorized) {
                LoginForm(admin)
            } else {
                AdminList(admin, onEdit = { editing = it }, onRemove = { removing = it })
            }
        }
    }

    if (showAdd) {
        AddDialog(admin, onDismiss = { showAdd = false })
    }
    editing?.let { src ->
        EditDialog(src, onDismiss = { editing = null }) { updated ->
            admin.update(src.repo, updated)
            editing = null
        }
    }
    removing?.let { src ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.admin_remove)) },
            text = { Text(stringResource(R.string.admin_remove_confirm, src.repo)) },
            confirmButton = {
                TextButton(onClick = { admin.remove(src.repo); removing = null }) {
                    Text(stringResource(R.string.admin_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LoginForm(admin: AdminViewModel) {
    var token by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.admin_login_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.admin_login_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            singleLine = true,
            label = { Text(stringResource(R.string.admin_login_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { admin.signIn(token) }, enabled = !admin.busy && token.isNotBlank()) {
            Text(stringResource(R.string.admin_login))
        }
    }
}

@Composable
private fun AdminList(admin: AdminViewModel, onEdit: (SourceApp) -> Unit, onRemove: (SourceApp) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.admin_signed_in),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { admin.signOut() }) { Text(stringResource(R.string.admin_logout)) }
            }
        }
        if (admin.sources.isEmpty()) {
            item { CenterMessage(stringResource(R.string.admin_empty)) }
        }
        items(admin.sources, key = { it.repo }) { src ->
            val pub = admin.published[src.repo]
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(src.name ?: pub?.name ?: src.repo, style = MaterialTheme.typography.titleMedium)
                    Text(src.repo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        pub?.release?.let { stringResource(R.string.admin_current_version, it.versionName) }
                            ?: stringResource(R.string.admin_pending),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text(stringResource(R.string.admin_enabled), modifier = Modifier.weight(1f))
                        Switch(checked = src.enabled, onCheckedChange = { admin.setEnabled(src.repo, it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_featured), modifier = Modifier.weight(1f))
                        Switch(checked = src.featured, onCheckedChange = { admin.setFeatured(src.repo, it) })
                    }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onEdit(src) }) { Text(stringResource(R.string.admin_edit)) }
                        TextButton(onClick = { onRemove(src) }) {
                            Text(stringResource(R.string.admin_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDialog(admin: AdminViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val preview = admin.preview
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_add_app)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.admin_repo_url)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { admin.fetchPreview(url) }, enabled = !admin.busy && url.isNotBlank()) {
                    Text(stringResource(R.string.admin_fetch_info))
                }
                admin.previewError?.let {
                    Text(msgText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (preview != null) {
                    Text(stringResource(R.string.admin_detected, preview.repo), style = MaterialTheme.typography.titleSmall)
                    preview.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (preview.tag != null) {
                        Text(stringResource(R.string.admin_latest_release, preview.tag), style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.admin_apks, preview.apks.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            stringResource(R.string.admin_no_apk),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = preview != null && !admin.busy,
                onClick = {
                    preview?.let { admin.add(SourceApp(repo = it.repo)) }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.admin_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EditDialog(src: SourceApp, onDismiss: () -> Unit, onSave: (SourceApp) -> Unit) {
    var name by remember { mutableStateOf(src.name ?: "") }
    var nameFa by remember { mutableStateOf(src.nameFa ?: "") }
    var desc by remember { mutableStateOf(src.description ?: "") }
    var descFa by remember { mutableStateOf(src.descriptionFa ?: "") }
    var icon by remember { mutableStateOf(src.icon ?: "") }
    var shots by remember { mutableStateOf(src.screenshots.joinToString("\n")) }
    var pattern by remember { mutableStateOf(src.assetPattern ?: "") }
    var category by remember { mutableStateOf(src.category) }
    var pre by remember { mutableStateOf(src.prerelease) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(src.repo) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.admin_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(nameFa, { nameFa = it }, label = { Text(stringResource(R.string.admin_field_name_fa)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text(stringResource(R.string.admin_field_description)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(descFa, { descFa = it }, label = { Text(stringResource(R.string.admin_field_description_fa)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(icon, { icon = it }, label = { Text(stringResource(R.string.admin_field_icon)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(shots, { shots = it }, label = { Text(stringResource(R.string.admin_field_screenshots)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pattern, { pattern = it }, label = { Text(stringResource(R.string.admin_field_asset)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.admin_field_category), style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CATEGORY_IDS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { id ->
                                FilterChip(
                                    selected = category == id,
                                    onClick = { category = id },
                                    label = { Text(categoryLabel(id)) },
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.admin_prerelease), modifier = Modifier.weight(1f))
                    Switch(checked = pre, onCheckedChange = { pre = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun blankToNull(s: String): String? = s.trim().ifEmpty { null }
                onSave(
                    src.copy(
                        name = blankToNull(name),
                        nameFa = blankToNull(nameFa),
                        description = blankToNull(desc),
                        descriptionFa = blankToNull(descFa),
                        icon = blankToNull(icon),
                        screenshots = shots.lines().map { it.trim() }.filter { it.startsWith("https://") },
                        assetPattern = blankToNull(pattern),
                        category = category,
                        prerelease = pre,
                    )
                )
            }) { Text(stringResource(R.string.admin_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
