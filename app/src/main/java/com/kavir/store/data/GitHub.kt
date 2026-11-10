package com.kavir.store.data

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

data class RepoPreview(
    val repo: String,
    val name: String,
    val description: String?,
    val avatar: String,
    val tag: String?,
    val apks: List<String>,
    val archived: Boolean,
)

/** GitHub REST calls used by the admin panel. All blocking: call from Dispatchers.IO. */
object GitHub {
    private const val API = "https://api.github.com"
    private val REPO_RE = Regex(
        "^(?:https?://)?(?:www\\.)?(?:github\\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/.*)?$"
    )

    /** Accepts "https://github.com/o/n", "github.com/o/n" or "o/n". Returns "o/n" or null. */
    fun parseRepo(input: String): String? {
        val m = REPO_RE.matchEntire(input.trim()) ?: return null
        return "${m.groupValues[1]}/${m.groupValues[2]}"
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun arr(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** True when [token] belongs to someone with push access to [repo]. */
    fun canPush(token: String, repo: String): Boolean {
        val info = obj(Http.request("$API/repos/$repo", token = token))
        return info["permissions"]?.jsonObject?.get("push")?.jsonPrimitive?.booleanOrNull == true
    }

    fun getSources(token: String, repo: String): Pair<Sources, String> {
        val res = obj(Http.request("$API/repos/$repo/contents/catalog/sources.json?ref=main", token = token))
        val content = res.str("content") ?: ""
        val sha = res.str("sha") ?: ""
        val text = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        return AppJson.decodeFromString(Sources.serializer(), text) to sha
    }

    fun putSources(token: String, repo: String, sources: Sources, sha: String, message: String) {
        val text = AppJson.encodeToString(Sources.serializer(), sources) + "\n"
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            put("sha", sha)
            put("branch", "main")
        }.toString()
        Http.request("$API/repos/$repo/contents/catalog/sources.json", method = "PUT", body = body, token = token)
    }

    /** Starts the catalog sync workflow. Needs a token with Actions: write. */
    fun dispatchSync(token: String, repo: String) {
        val body = buildJsonObject { put("ref", "main") }.toString()
        Http.request(
            "$API/repos/$repo/actions/workflows/sync-catalog.yml/dispatches",
            method = "POST", body = body, token = token,
        )
    }

    /** Looks up public info for a repository: description, latest release with an APK. */
    fun preview(repo: String, token: String?): RepoPreview {
        val info = obj(Http.request("$API/repos/$repo", token = token))
        val releases = arr(Http.request("$API/repos/$repo/releases?per_page=10", token = token))
        var tag: String? = null
        var apks: List<String> = emptyList()
        for (r in releases) {
            val o = r.jsonObject
            if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) continue
            if (o["prerelease"]?.jsonPrimitive?.booleanOrNull == true) continue
            val names = o["assets"]?.jsonArray
                ?.mapNotNull { it.jsonObject.str("name") }
                ?.filter { it.endsWith(".apk", ignoreCase = true) }
                ?: emptyList()
            if (names.isNotEmpty()) {
                tag = o.str("tag_name")
                apks = names
                break
            }
        }
        return RepoPreview(
            repo = info.str("full_name") ?: repo,
            name = info.str("name") ?: repo.substringAfter('/'),
            description = info.str("description"),
            avatar = info["owner"]?.jsonObject?.str("avatar_url") ?: "",
            tag = tag,
            apks = apks,
            archived = info["archived"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }
}
