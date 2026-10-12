package moe.shizuku.manager.module

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** A repository in the community's module index, as the index lists it. */
@Serializable
data class ModuleRepo(
    @SerialName("full_name") val fullName: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val description: String = "",
    @SerialName("stargazers_count") val stars: Int = 0,
    val language: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val owner: Owner? = null,
    // The rest is what the directory publishes beyond GitHub's own fields, and it is what its
    // filters and sort orders are built from: a category, when the app was added, and its topics.
    val category: String = "",
    @SerialName("added_at") val addedAt: String = "",
    val topics: List<String> = emptyList(),
    val release: RepoRelease? = null,
) {
    @Serializable
    data class Owner(
        val login: String = "",
        @SerialName("avatar_url") val avatarUrl: String = "",
    )

    @Serializable
    data class RepoRelease(
        val tag: String = "",
        val name: String = "",
        @SerialName("published_at") val publishedAt: String = "",
        /** The apk of that release, when it has one: what an app is installed from. */
        @SerialName("apk_url") val apkUrl: String = "",
    )

    /** The half of a `owner/name` a list shows, the owner being the less interesting one. */
    val name: String get() = fullName.substringAfter('/')

    val avatarUrl: String? get() = owner?.avatarUrl?.takeIf { it.isNotEmpty() }

    /** When the newest release was published, which is what the site sorts "Latest release" by. */
    val releasedAt: String get() = release?.publishedAt.orEmpty()
}

/** The shape GitHub's repository search answers in. */
@Serializable
private data class RepoSearch(val items: List<ModuleRepo> = emptyList())

/** One file of a release. A module is the one among them that is a zip. */
@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String = "",
    val name: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** The module zip this release offers, when it offers one. */
    val moduleZip: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }

    /** What a row calls this release: its name if it has one, its tag otherwise. */
    val label: String get() = name.ifEmpty { tag }
}

/**
 * Where modules to install are found.
 *
 * Two sources, and neither of them needs a GitHub token. The list of repositories is a plain file
 * over https - the community's own index - so reading it costs nothing and never runs into a rate
 * limit. A repository's releases do come from the GitHub API, which without a token is rate limited
 * to sixty calls an hour: that is a limit on *browsing*, which a person does slowly, and it is
 * reported as what it is rather than hidden behind a token nobody asked for.
 */
object ModuleCatalog {

    /** The community index: repositories worth looking at, in the shape the index publishes. */
    const val INDEX_URL =
        "https://raw.githubusercontent.com/rushiranpise/shizuku-modules/main/data/repos.json"

    /**
     * What a module is, as far as anything on the internet can tell: a repository whose owner put
     * the modules' topic on it.
     *
     * This is the whole of the module list's source, and it is the same way the format's own
     * implementations find modules. A topic search is a claim by the author rather than a proof -
     * anyone can add a topic - which is why installing still goes through the format's checks, and
     * why the list says where it came from instead of pretending to know better.
     */
    const val MODULE_TOPIC = "shevery-modules"

    /**
     * The modules written for this format, most starred first.
     *
     * The search API is unauthenticated here and rate limited to ten calls a minute, which a person
     * browsing a list will never reach: a token would raise a limit nobody is close to.
     */
    suspend fun modules(topic: String = MODULE_TOPIC): List<ModuleRepo> =
        withContext(Dispatchers.IO) {
            val search = json.decodeFromString<RepoSearch>(
                get("https://api.github.com/search/repositories?q=topic:$topic&sort=stars&per_page=50")
            )

            search.items
        }

    /** The newest release of [repoFullName], or null when it has never published one. */
    suspend fun latestRelease(repoFullName: String): Release? =
        withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<Release>(get("https://api.github.com/repos/$repoFullName/releases/latest")) }
                .getOrNull()
        }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun index(url: String = INDEX_URL): List<ModuleRepo> =
        withContext(Dispatchers.IO) { json.decodeFromString(get(url)) }

    /** The releases of [repoFullName], newest first, as GitHub lists them. */
    suspend fun releases(repoFullName: String): List<Release> =
        withContext(Dispatchers.IO) {
            json.decodeFromString(get("https://api.github.com/repos/$repoFullName/releases"))
        }

    /**
     * The manifest a module's `update.json` points at.
     *
     * Null for anything that is not one: a module whose update URL answers with an error, or with
     * something that is not an update, simply has no update to offer.
     */
    suspend fun updateManifest(url: String): ModuleUpdate? =
        withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<ModuleUpdate>(get(url)) }.getOrNull()
        }

    /**
     * Writes [url] to [file], a piece at a time rather than holding it.
     *
     * [download] is for a module, which is a few hundred kilobytes and can be held whole; an app's
     * release is tens of megabytes, so it goes through to the file as it arrives.
     */
    suspend fun downloadTo(url: String, file: File): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ModuleCatalogException(httpMessage(response.code))

                val body = response.body ?: throw ModuleCatalogException("The download came back empty.")
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
            }

            file
        }

    /** The bytes of an asset. A module is a few hundred kilobytes at most, so it is held whole. */
    suspend fun download(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ModuleCatalogException(httpMessage(response.code))
                response.body?.bytes() ?: throw ModuleCatalogException("The download came back empty.")
            }
        }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ModuleCatalogException(httpMessage(response.code))
            return response.body?.string() ?: throw ModuleCatalogException("The answer came back empty.")
        }
    }

    private const val USER_AGENT = "Shizuku-Next"

    /**
     * What a failed call means to the person who made it.
     *
     * The one worth spelling out is 403: the API says that both for a token that is not good enough
     * and for having asked too often without one, and only the second can happen here.
     */
    private fun httpMessage(code: Int): String = when (code) {
        403, 429 -> "GitHub is rate limiting this device. Try again in a little while."
        404 -> "That repository is not there any more."
        else -> "GitHub answered $code."
    }
}

class ModuleCatalogException(message: String) : Exception(message)
