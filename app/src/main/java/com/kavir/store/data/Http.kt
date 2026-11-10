package com.kavir.store.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP client with retries and GitHub rate-limit detection. Blocking: call from Dispatchers.IO. */
object Http {
    const val UA = "KavirStore-Android/1.0 (+https://github.com/kavirkobra19/KavirStore)"

    class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")
    class RateLimitException(val resetEpochSeconds: Long) : IOException("GitHub API rate limit exceeded")

    fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        token: String? = null,
        accept: String = "application/vnd.github+json",
        retries: Int = 2,
    ): String {
        var last: IOException? = null
        for (attempt in 0..retries) {
            try {
                return once(url, method, body, token, accept)
            } catch (e: RateLimitException) {
                throw e
            } catch (e: HttpException) {
                // Only server errors are worth retrying.
                if (e.code in 500..599 && attempt < retries) last = e else throw e
            } catch (e: IOException) {
                last = e
            }
            try {
                Thread.sleep(600L * (attempt + 1))
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        throw last ?: IOException("Request failed")
    }

    private fun once(url: String, method: String, body: String?, token: String?, accept: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", accept)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) return text
            if ((code == 403 || code == 429) && c.getHeaderField("X-RateLimit-Remaining") == "0") {
                throw RateLimitException(c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: 0L)
            }
            throw HttpException(code, text.take(300))
        } finally {
            c.disconnect()
        }
    }
}
