package com.kavir.store.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kavir.store.data.Catalog
import com.kavir.store.data.CatalogApp
import com.kavir.store.data.CatalogRepository
import com.kavir.store.data.Prefs
import com.kavir.store.install.InstallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { Home, Categories, Search, MyApps, Settings }

sealed interface Route {
    data class Detail(val pkg: String) : Route
    data class Category(val id: String) : Route
    data object Admin : Route
}

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(val catalog: Catalog, val offline: Boolean) : CatalogState
    data class Error(val message: String) : CatalogState
}

enum class AppStatus { NotInstalled, Update, Installed, Incompatible, Unavailable }

class StoreViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = CatalogRepository(application)
    val prefs = Prefs(application)

    var catalog by mutableStateOf<CatalogState>(CatalogState.Loading)
    var installed by mutableStateOf<Map<String, Long>>(emptyMap())
    var refreshing by mutableStateOf(false)

    var language by mutableStateOf(prefs.language)
    var theme by mutableStateOf(prefs.theme)

    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Route>()
    var query by mutableStateOf("")
    var showPermissionDialog by mutableStateOf(false)

    init {
        InstallManager.init(application)
        loadCatalog()
        refreshInstalled()
        viewModelScope.launch {
            InstallManager.installedChanged.collect { refreshInstalled() }
        }
    }

    // ---- catalog --------------------------------------------------------------------------------

    val apps: List<CatalogApp>
        get() = (catalog as? CatalogState.Ready)?.catalog?.apps
            ?.filter { it.enabled && it.release != null } ?: emptyList()

    val recent: List<CatalogApp>
        get() = apps.sortedByDescending { it.release?.publishedAt ?: "" }

    val featured: List<CatalogApp>
        get() = apps.filter { it.featured }.ifEmpty { recent.take(5) }

    fun appByPackage(pkg: String): CatalogApp? = apps.firstOrNull { it.packageName == pkg }

    fun loadCatalog() {
        viewModelScope.launch {
            refreshing = true
            try {
                val r = repo.load()
                catalog = CatalogState.Ready(r.catalog, r.fromCache)
            } catch (e: Exception) {
                if (catalog !is CatalogState.Ready) catalog = CatalogState.Error(e.message ?: "")
            } finally {
                refreshing = false
            }
            refreshInstalled()
        }
    }

    // ---- installed state ------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    fun refreshInstalled() {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                try {
                    pm.getInstalledPackages(0).associate { it.packageName to PackageInfoCompat.getLongVersionCode(it) }
                } catch (e: Exception) {
                    emptyMap()
                }
            }
            installed = map
        }
    }

    fun statusOf(app: CatalogApp): AppStatus {
        val r = app.release ?: return AppStatus.Unavailable
        val have = installed[app.packageName]
        val compatible = Build.VERSION.SDK_INT >= r.minSdk
        return when {
            have == null -> if (compatible) AppStatus.NotInstalled else AppStatus.Incompatible
            have < r.versionCode -> if (compatible) AppStatus.Update else AppStatus.Incompatible
            else -> AppStatus.Installed
        }
    }

    val updatable: List<CatalogApp>
        get() = apps.filter { statusOf(it) == AppStatus.Update }

    val installedApps: List<CatalogApp>
        get() = apps.filter { installed.containsKey(it.packageName) }

    // ---- actions --------------------------------------------------------------------------------

    fun primaryAction(app: CatalogApp) {
        when (statusOf(app)) {
            AppStatus.Installed -> open(app.packageName)
            AppStatus.NotInstalled, AppStatus.Update -> {
                InstallManager.dismiss(app.packageName)
                if (!InstallManager.canInstall()) showPermissionDialog = true
                else InstallManager.start(app)
            }
            else -> Unit
        }
    }

    fun updateAll() {
        val list = updatable
        if (list.isEmpty()) return
        if (!InstallManager.canInstall()) showPermissionDialog = true
        else InstallManager.startAll(list)
    }

    fun open(pkg: String) {
        val ctx = getApplication<Application>()
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun uninstall(pkg: String) {
        val ctx = getApplication<Application>()
        val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    // ---- navigation / settings ------------------------------------------------------------------

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun setLanguage(value: String) {
        prefs.language = value
        language = value
    }

    fun setTheme(value: String) {
        prefs.theme = value
        theme = value
    }
}
