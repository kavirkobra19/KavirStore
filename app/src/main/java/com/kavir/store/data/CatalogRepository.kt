package com.kavir.store.data

import android.content.Context
import com.kavir.store.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CatalogRepository(context: Context) {
    private val cacheFile = File(context.filesDir, "apps.json")

    data class Result(val catalog: Catalog, val fromCache: Boolean)

    /** Loads the public catalog. Falls back to the last saved copy when the network fails. */
    suspend fun load(): Result = withContext(Dispatchers.IO) {
        try {
            // The query parameter defeats the raw.githubusercontent.com edge cache (~1 minute buckets).
            val url = BuildConfig.CATALOG_URL + "?t=" + System.currentTimeMillis() / 60_000
            val text = Http.request(url, accept = "application/json")
            val catalog = AppJson.decodeFromString(Catalog.serializer(), text)
            runCatching { cacheFile.writeText(text) }
            Result(catalog, false)
        } catch (e: Exception) {
            val cached = readCache() ?: throw e
            Result(cached, true)
        }
    }

    private fun readCache(): Catalog? = try {
        if (cacheFile.exists()) AppJson.decodeFromString(Catalog.serializer(), cacheFile.readText()) else null
    } catch (e: Exception) {
        null
    }
}
