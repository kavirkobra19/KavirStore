package com.kavir.store.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.kavir.store.data.CatalogApp
import com.kavir.store.data.Http
import com.kavir.store.data.Release
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads APKs from GitHub Releases, verifies them and hands them to Android's PackageInstaller.
 * Android always shows its own confirmation screen; nothing here bypasses it.
 */
object InstallManager {
    const val ACTION_RESULT = "com.kavir.store.INSTALL_RESULT"
    const val EXTRA_PKG = "com.kavir.store.EXTRA_PKG"

    enum class InstallError {
        INCOMPATIBLE, NETWORK, HASH, PACKAGE, VERSION, SIGNATURE, STORAGE, INSTALL, NO_RELEASE, UNTRUSTED
    }

    sealed interface State {
        data class Downloading(val bytes: Long, val total: Long) : State {
            val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else -1f
        }

        data object Verifying : State
        data object Installing : State
        data class Failed(val error: InstallError, val detail: String? = null) : State
    }

    private class InstallException(val error: InstallError, val detail: String? = null) : Exception(detail)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val _installed = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val installedChanged: SharedFlow<String> = _installed.asSharedFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    fun dismiss(pkg: String) = set(pkg, null)

    private fun set(pkg: String, s: State?) {
        _states.update { m -> if (s == null) m - pkg else m + (pkg to s) }
    }

    fun start(a: CatalogApp) {
        val pkg = a.packageName
        if (jobs[pkg]?.isActive == true) return
        jobs[pkg] = scope.launch { process(a) }
    }

    /** Installs the given apps one after another (each still needs the user's confirmation). */
    fun startAll(list: List<CatalogApp>) {
        scope.launch {
            for (a in list) {
                if (_states.value[a.packageName] is State.Downloading) continue
                process(a)
            }
        }
    }

    fun cancel(pkg: String) {
        jobs.remove(pkg)?.cancel()
        waiters.remove(pkg)?.complete(false)
        set(pkg, null)
    }

    private suspend fun process(a: CatalogApp): Boolean {
        val pkg = a.packageName
        val r = a.release
        if (r == null) {
            set(pkg, State.Failed(InstallError.NO_RELEASE)); return false
        }
        if (Build.VERSION.SDK_INT < r.minSdk) {
            set(pkg, State.Failed(InstallError.INCOMPATIBLE, r.minSdk.toString())); return false
        }
        if (!r.apkUrl.startsWith("https://github.com/")) {
            set(pkg, State.Failed(InstallError.UNTRUSTED)); return false
        }
        return try {
            val dir = File(app.cacheDir, "apks").apply { mkdirs() }
            val file = File(dir, "$pkg-${r.versionCode}.apk")
            if (!(file.exists() && sha256(file).equals(r.sha256, ignoreCase = true))) {
                set(pkg, State.Downloading(0, r.size))
                download(r.apkUrl, file) { b, t -> set(pkg, State.Downloading(b, if (t > 0) t else r.size)) }
            }
            set(pkg, State.Verifying)
            verify(a, r, file)
            set(pkg, State.Installing)
            commit(a, file)
        } catch (e: CancellationException) {
            set(pkg, null)
            throw e
        } catch (e: InstallException) {
            set(pkg, State.Failed(e.error, e.detail)); false
        } catch (e: IOException) {
            val full = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true
            set(pkg, State.Failed(if (full) InstallError.STORAGE else InstallError.NETWORK, e.message)); false
        } catch (e: Exception) {
            set(pkg, State.Failed(InstallError.INSTALL, e.message)); false
        }
    }

    // ---- download -------------------------------------------------------------------------------

    private suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val part = File(dest.parentFile, dest.name + ".part")
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", Http.UA)
                val code = c.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = c.contentLengthLong
                var done = 0L
                var lastReport = 0L
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            ensureActive()
                            val now = System.nanoTime()
                            if (now - lastReport > 150_000_000L) {
                                lastReport = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
                onProgress(done, total)
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("Could not store the downloaded file")
            } catch (e: Exception) {
                part.delete()
                throw e
            } finally {
                c.disconnect()
            }
        }
    }

    // ---- verification ---------------------------------------------------------------------------

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        if (sigs == null) return emptySet()
        return sigs.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        pm.getPackageInfo(pkg, signingFlags())
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun verify(a: CatalogApp, r: Release, file: File) {
        val actual = sha256(file)
        if (!actual.equals(r.sha256, ignoreCase = true)) {
            file.delete()
            throw InstallException(InstallError.HASH)
        }
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, signingFlags())
            ?: throw InstallException(InstallError.PACKAGE, "unreadable APK")
        if (archive.packageName != a.packageName) {
            file.delete()
            throw InstallException(InstallError.PACKAGE, archive.packageName)
        }
        if (PackageInfoCompat.getLongVersionCode(archive) != r.versionCode) {
            file.delete()
            throw InstallException(InstallError.VERSION)
        }
        val installed = installedInfo(pm, a.packageName)
        if (installed != null) {
            // An update must be signed by the same key as the installed app, otherwise Android
            // would refuse it (or, worse, require uninstalling and losing the app's data).
            val newSigners = signerDigests(archive)
            val oldSigners = signerDigests(installed)
            if (newSigners.isEmpty() || newSigners != oldSigners) throw InstallException(InstallError.SIGNATURE)
        }
    }

    // ---- installation ---------------------------------------------------------------------------

    private suspend fun commit(a: CatalogApp, file: File): Boolean {
        val pkg = a.packageName
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(pkg)
        params.setSize(file.length())
        val sessionId = installer.createSession(params)
        val deferred = CompletableDeferred<Boolean>()
        waiters[pkg] = deferred
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, InstallResultReceiver::class.java)
                    .setAction(ACTION_RESULT)
                    .setPackage(app.packageName)
                    .putExtra(EXTRA_PKG, pkg)
                val pi = PendingIntent.getBroadcast(
                    app, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pi.intentSender)
            }
            return try {
                withTimeout(15 * 60 * 1000L) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                waiters.remove(pkg)
                set(pkg, State.Failed(InstallError.INSTALL, "timeout"))
                false
            }
        } catch (e: Exception) {
            waiters.remove(pkg)
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /** Called by [InstallResultReceiver] with the final status of an install session. */
    internal fun onResult(pkg: String, status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                set(pkg, null)
                runCatching { File(app.cacheDir, "apks").listFiles()?.filter { it.name.startsWith("$pkg-") }?.forEach { it.delete() } }
                waiters.remove(pkg)?.complete(true)
                _installed.tryEmit(pkg)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The user dismissed Android's confirmation screen.
                set(pkg, null)
                waiters.remove(pkg)?.complete(false)
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                set(pkg, State.Failed(InstallError.STORAGE, message))
                waiters.remove(pkg)?.complete(false)
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                set(pkg, State.Failed(InstallError.SIGNATURE, message))
                waiters.remove(pkg)?.complete(false)
            }
            else -> {
                set(pkg, State.Failed(InstallError.INSTALL, message))
                waiters.remove(pkg)?.complete(false)
            }
        }
    }
}
