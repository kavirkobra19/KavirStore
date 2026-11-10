package com.kavir.store.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kavir.store.R
import com.kavir.store.data.CatalogApp
import com.kavir.store.data.formatSize
import com.kavir.store.data.sdkToAndroid
import com.kavir.store.install.InstallManager
import com.kavir.store.install.InstallManager.InstallError
import com.kavir.store.install.InstallManager.State

@Composable
fun isFarsi(): Boolean = LocalConfiguration.current.locales[0].language == "fa"

@Composable
fun categoryLabel(id: String): String = when (id) {
    "all" -> stringResource(R.string.cat_all)
    "tools" -> stringResource(R.string.cat_tools)
    "games" -> stringResource(R.string.cat_games)
    "social" -> stringResource(R.string.cat_social)
    "media" -> stringResource(R.string.cat_media)
    "productivity" -> stringResource(R.string.cat_productivity)
    "education" -> stringResource(R.string.cat_education)
    "communication" -> stringResource(R.string.cat_communication)
    "other" -> stringResource(R.string.cat_other)
    else -> id.replaceFirstChar { it.uppercase() }
}

@Composable
fun AppIcon(app: CatalogApp, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = app.name.take(1).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (!app.icon.isNullOrBlank()) {
            AsyncImage(
                model = app.icon,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
fun installErrorText(error: InstallError, detail: String?): String = when (error) {
    InstallError.INCOMPATIBLE -> stringResource(R.string.err_incompatible, sdkToAndroid(detail?.toIntOrNull() ?: 0))
    InstallError.NETWORK -> stringResource(R.string.err_network)
    InstallError.HASH -> stringResource(R.string.err_hash)
    InstallError.PACKAGE -> stringResource(R.string.err_package, detail ?: "?")
    InstallError.VERSION -> stringResource(R.string.err_version)
    InstallError.SIGNATURE -> stringResource(R.string.err_signature)
    InstallError.STORAGE -> stringResource(R.string.err_storage)
    InstallError.NO_RELEASE -> stringResource(R.string.err_no_release)
    InstallError.UNTRUSTED -> stringResource(R.string.err_untrusted)
    InstallError.INSTALL -> stringResource(R.string.err_install, detail ?: "")
}

/** Install / Update / Open button plus progress and error display for one app. */
@Composable
fun ActionArea(vm: StoreViewModel, app: CatalogApp, compact: Boolean) {
    val states by InstallManager.states.collectAsState()
    val st = states[app.packageName]
    val status = vm.statusOf(app)

    when (st) {
        is State.Downloading -> {
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (st.fraction >= 0f) {
                        CircularProgressIndicator(progress = { st.fraction }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    }
                    IconButton(onClick = { InstallManager.cancel(app.packageName) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (st.fraction >= 0f) {
                            LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.weight(1f))
                        } else {
                            LinearProgressIndicator(modifier = Modifier.weight(1f))
                        }
                        IconButton(onClick = { InstallManager.cancel(app.packageName) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                    val done = formatSize(st.bytes)
                    val total = if (st.total > 0) " / " + formatSize(st.total) else ""
                    Text(
                        stringResource(R.string.downloading) + "  " + done + total,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        is State.Verifying, is State.Installing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                if (!compact) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (st is State.Verifying) stringResource(R.string.verifying) else stringResource(R.string.installing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { InstallManager.cancel(app.packageName) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
        is State.Failed -> {
            if (compact) {
                FilledTonalButton(onClick = { vm.primaryAction(app) }) { Text(stringResource(R.string.retry)) }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        installErrorText(st.error, st.detail),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { vm.primaryAction(app) }) { Text(stringResource(R.string.retry)) }
                }
            }
        }
        null -> {
            val label = when (status) {
                AppStatus.NotInstalled -> stringResource(R.string.install)
                AppStatus.Update -> stringResource(R.string.update)
                AppStatus.Installed -> stringResource(R.string.open)
                AppStatus.Incompatible -> stringResource(
                    R.string.err_incompatible,
                    sdkToAndroid(app.release?.minSdk ?: 0)
                )
                AppStatus.Unavailable -> stringResource(R.string.err_no_release)
            }
            val enabled = status == AppStatus.NotInstalled || status == AppStatus.Update || status == AppStatus.Installed
            if (compact) {
                if (status == AppStatus.Installed) {
                    OutlinedButton(onClick = { vm.primaryAction(app) }, enabled = enabled) { Text(label) }
                } else if (enabled) {
                    Button(onClick = { vm.primaryAction(app) }) { Text(label) }
                }
            } else {
                Button(
                    onClick = { vm.primaryAction(app) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label) }
            }
        }
    }
}

@Composable
fun AppRow(vm: StoreViewModel, app: CatalogApp, onClick: () -> Unit) {
    val fa = isFarsi()
    val status = vm.statusOf(app)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.displayName(fa),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                append(categoryLabel(app.category))
                app.release?.let {
                    append(" · ")
                    append(it.versionName)
                    append(" · ")
                    append(formatSize(it.size))
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status == AppStatus.Update) {
                Text(
                    stringResource(R.string.update),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        ActionArea(vm, app, compact = true)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun CenterMessage(title: String, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) action()
    }
}
