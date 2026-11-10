package com.kavir.store.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kavir.store.BuildConfig
import com.kavir.store.R
import com.kavir.store.data.AppJson
import com.kavir.store.data.Catalog
import com.kavir.store.data.CatalogApp
import com.kavir.store.data.GitHub
import com.kavir.store.data.Http
import com.kavir.store.data.Prefs
import com.kavir.store.data.RepoPreview
import com.kavir.store.data.SourceApp
import com.kavir.store.data.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiMsg(@StringRes val res: Int, val arg: String? = null)

/**
 * Admin operations. Authorization is enforced by GitHub itself: every change is a commit made with the
 * admin's own token, which only works for people with write access to the store repository.
 */
class AdminViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    private val repo = BuildConfig.CATALOG_REPO

    var token by mutableStateOf<String?>(null)
    var authorized by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<UiMsg?>(null)
    var sources by mutableStateOf<List<SourceApp>>(emptyList())
    var published by mutableStateOf<Map<String, CatalogApp>>(emptyMap())
    var preview by mutableStateOf<RepoPreview?>(null)
    var previewError by mutableStateOf<UiMsg?>(null)

    init {
        val saved = prefs.adminToken
        if (saved != null) {
            token = saved
            viewModelScope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) { GitHub.canPush(saved, repo) }
                    if (ok) {
                        authorized = true
                        loadAll()
                    } else {
                        signOut()
                    }
                } catch (e: Http.HttpException) {
                    if (e.code == 401 || e.code == 403) signOut()
                } catch (e: Exception) {
                    // Offline: keep the token, the screen will retry when opened.
                }
            }
        }
    }

    private fun errorMsg(e: Exception): UiMsg = when (e) {
        is Http.RateLimitException -> UiMsg(R.string.admin_rate_limited)
        is Http.HttpException -> UiMsg(R.string.admin_error, "HTTP ${e.code}")
        else -> UiMsg(R.string.admin_error, e.message ?: e.javaClass.simpleName)
    }

    fun signIn(input: String) {
        val tk = input.trim()
        if (tk.isEmpty()) return
        viewModelScope.launch {
            busy = true
            message = null
            try {
                val ok = withContext(Dispatchers.IO) { GitHub.canPush(tk, repo) }
                if (!ok) {
                    message = UiMsg(R.string.admin_not_authorized)
                } else {
                    prefs.adminToken = tk
                    token = tk
                    authorized = true
                    loadAll()
                }
            } catch (e: Exception) {
                message = errorMsg(e)
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        prefs.adminToken = null
        token = null
        authorized = false
        sources = emptyList()
        published = emptyMap()
        preview = null
    }

    fun loadAll() {
        val tk = token ?: return
        viewModelScope.launch {
            busy = true
            try {
                val (src, pub) = withContext(Dispatchers.IO) {
                    val s = GitHub.getSources(tk, repo).first
                    val p = try {
                        val text = Http.request(
                            BuildConfig.CATALOG_URL + "?t=" + System.currentTimeMillis(),
                            accept = "application/json",
                        )
                        AppJson.decodeFromString(Catalog.serializer(), text).apps.associateBy { it.repo }
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    s to p
                }
                sources = src.apps
                published = pub
            } catch (e: Exception) {
                message = errorMsg(e)
            } finally {
                busy = false
            }
        }
    }

    /** Re-reads the file right before writing so edits made elsewhere are never overwritten. */
    private fun mutate(done: UiMsg, change: (List<SourceApp>) -> List<SourceApp>) {
        val tk = token ?: return
        viewModelScope.launch {
            busy = true
            try {
                val updated = withContext(Dispatchers.IO) {
                    val (cur, sha) = GitHub.getSources(tk, repo)
                    val next = cur.copy(apps = change(cur.apps))
                    GitHub.putSources(tk, repo, next, sha, "catalog: update sources (admin panel)")
                    next
                }
                sources = updated.apps
                message = done
            } catch (e: Exception) {
                message = errorMsg(e)
            } finally {
                busy = false
            }
        }
    }

    fun fetchPreview(input: String) {
        previewError = null
        preview = null
        val r = GitHub.parseRepo(input)
        if (r == null) {
            previewError = UiMsg(R.string.admin_invalid_repo)
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                preview = withContext(Dispatchers.IO) { GitHub.preview(r, token) }
            } catch (e: Exception) {
                previewError = errorMsg(e)
            } finally {
                busy = false
            }
        }
    }

    fun add(app: SourceApp) {
        mutate(UiMsg(R.string.admin_synced)) { list ->
            if (list.any { it.repo.equals(app.repo, ignoreCase = true) }) list else list + app
        }
    }

    fun update(oldRepo: String, app: SourceApp) {
        mutate(UiMsg(R.string.admin_synced)) { list ->
            list.map { if (it.repo == oldRepo) app else it }
        }
    }

    fun remove(repoName: String) {
        mutate(UiMsg(R.string.admin_synced)) { list -> list.filterNot { it.repo == repoName } }
    }

    fun setEnabled(repoName: String, value: Boolean) {
        mutate(UiMsg(R.string.admin_synced)) { list ->
            list.map { if (it.repo == repoName) it.copy(enabled = value) else it }
        }
    }

    fun setFeatured(repoName: String, value: Boolean) {
        mutate(UiMsg(R.string.admin_synced)) { list ->
            list.map { if (it.repo == repoName) it.copy(featured = value) else it }
        }
    }

    fun refreshReleases() {
        val tk = token ?: return
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { GitHub.dispatchSync(tk, repo) }
                message = UiMsg(R.string.admin_sync_started)
            } catch (e: Exception) {
                message = UiMsg(R.string.admin_sync_failed)
            } finally {
                busy = false
            }
        }
    }

    fun clearMessage() {
        message = null
    }
}
