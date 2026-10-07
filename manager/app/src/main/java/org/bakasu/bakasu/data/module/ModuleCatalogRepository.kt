package org.bakasu.bakasu.data.module

import com.topjohnwu.superuser.io.SuFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.network.NetworkRequestRepository
import org.bakasu.bakasu.data.network.NetworkStatusRepository
import org.bakasu.bakasu.domain.model.CatalogAuthor
import org.bakasu.bakasu.domain.model.CatalogModule
import org.bakasu.bakasu.domain.model.ModuleCatalogFailure
import org.bakasu.bakasu.domain.model.ModuleCatalogResult
import org.bakasu.bakasu.domain.model.ModuleRelease
import org.bakasu.bakasu.domain.model.ModuleReleaseAsset
import org.bakasu.bakasu.domain.model.RepositoryError
import org.bakasu.bakasu.domain.model.RepositoryException
import org.bakasu.bakasu.domain.model.RepositorySource
import org.json.JSONArray
import org.json.JSONObject

data class RepositoryFeed(val source: RepositorySource, val modules: List<CatalogModule>)

class ModuleCatalogRepository(
    private val networkStatusRepository: NetworkStatusRepository,
    private val networkRequestRepository: NetworkRequestRepository,
    private val sourceStore: RepositorySourceStore,
) {
    private val refreshMutex = Mutex()
    private val mutableModules = MutableStateFlow<List<CatalogModule>>(emptyList())
    private val mutableRefreshingSources = MutableStateFlow<Set<String>>(emptySet())
    private val mutableSources = MutableStateFlow(sourceStore.load())
    private val mutableFailures = MutableStateFlow<Map<String, RepositoryError>>(emptyMap())
    private val cachedModules = mutableMapOf<String, List<CatalogModule>>()

    val modules: StateFlow<List<CatalogModule>> = mutableModules.asStateFlow()
    val refreshingSources: StateFlow<Set<String>> = mutableRefreshingSources.asStateFlow()
    val sources: StateFlow<List<RepositorySource>> = mutableSources.asStateFlow()
    val failures: StateFlow<Map<String, RepositoryError>> = mutableFailures.asStateFlow()

    suspend fun refresh(sourceUrl: String? = null, force: Boolean = true): ModuleCatalogResult<List<CatalogModule>> = refreshMutex.withLock {
        val targets = mutableSources.value.filter {
            (sourceUrl == null || it.url == sourceUrl) &&
                (force || (it.url !in cachedModules && it.url !in mutableFailures.value))
        }
        if (targets.isEmpty()) {
            publishModules()
            return@withLock ModuleCatalogResult.Success(mutableModules.value)
        }
        if (!networkStatusRepository.isAvailable()) {
            mutableFailures.value = mutableFailures.value + targets.associate { it.url to RepositoryError.OFFLINE }
            return@withLock ModuleCatalogResult.Failure(ModuleCatalogFailure.Offline)
        }
        mutableRefreshingSources.value = targets.map { it.url }.toSet()
        try {
            val results = coroutineScope {
                targets.map { source ->
                    async(Dispatchers.IO) {
                        source to attempt { loadSource(source) }
                    }
                }.awaitAll()
            }
            results.forEach { (source, result) ->
                result.onFailure { error ->
                    mutableFailures.value = mutableFailures.value + (source.url to reason(error))
                }
            }
            val feeds = results.mapNotNull { it.second.getOrNull() }.associateBy { it.source.url }
            if (feeds.isEmpty()) {
                return@withLock ModuleCatalogResult.Failure(ModuleCatalogFailure.Network(""))
            }
            val updated = mutableSources.value.map { feeds[it.url]?.source ?: it }
            val saved = attempt { sourceStore.save(updated) }
            if (saved.isFailure) {
                mutableFailures.value = mutableFailures.value + feeds.keys.associateWith { RepositoryError.STORAGE }
                return@withLock ModuleCatalogResult.Failure(ModuleCatalogFailure.Network(""))
            }
            feeds.forEach { (url, feed) -> cachedModules[url] = feed.modules }
            mutableSources.value = updated
            mutableFailures.value = mutableFailures.value - feeds.keys
            publishModules()
            ModuleCatalogResult.Success(mutableModules.value)
        } finally {
            mutableRefreshingSources.value = emptySet()
        }
    }

    suspend fun get(moduleId: String, sourceUrl: String): ModuleCatalogResult<CatalogModule> {
        val url = runCatching { MmrlRepositoryParser.canonicalUrl(sourceUrl) }.getOrNull()
            ?: return ModuleCatalogResult.Failure(ModuleCatalogFailure.NotFound)
        fun find() = mutableModules.value.firstOrNull { it.moduleId == moduleId && it.repositoryUrl == url }
        find()?.let {
            return ModuleCatalogResult.Success(it)
        }
        if (mutableSources.value.none { it.url == url }) {
            return ModuleCatalogResult.Failure(ModuleCatalogFailure.NotFound)
        }
        return when (val refreshed = refresh(url)) {
            is ModuleCatalogResult.Failure -> refreshed

            is ModuleCatalogResult.Success -> find()
                ?.let { ModuleCatalogResult.Success(it) }
                ?: ModuleCatalogResult.Failure(ModuleCatalogFailure.NotFound)
        }
    }

    suspend fun discover(): Result<List<RepositorySource>> = attempt {
        if (!networkStatusRepository.isAvailable()) throw RepositoryException(RepositoryError.OFFLINE)
        val body = networkRequestRepository.fetch("https://mmrl.dev/api/repositories.json").getOrThrow()
        MmrlRepositoryParser.discovery(body)
            .filterNot { it.isBuiltIn }
    }

    suspend fun add(url: String, metadata: RepositorySource? = null): Result<RepositorySource> = refreshMutex.withLock {
        attempt {
            val canonical = MmrlRepositoryParser.canonicalUrl(url)
            requireUserSource(canonical)
            mutableSources.value.firstOrNull { it.url == canonical }?.let { return@attempt it }
            if (!networkStatusRepository.isAvailable()) throw RepositoryException(RepositoryError.OFFLINE)
            val source = metadata?.takeIf { it.url == canonical } ?: RepositorySource(canonical, canonical)
            val feed = withContext(Dispatchers.IO) { loadSource(source) }
            val updated = mutableSources.value + feed.source
            sourceStore.save(updated)
            mutableSources.value = updated
            cachedModules[canonical] = feed.modules
            mutableFailures.value = mutableFailures.value - canonical
            publishModules()
            feed.source
        }
    }

    suspend fun rename(url: String, name: String) = refreshMutex.withLock {
        requireUserSource(url)
        val trimmed = name.trim()
        require(trimmed.length <= 100)
        if (mutableSources.value.none { it.url == url }) return@withLock
        val updated = mutableSources.value.map {
            if (it.url == url) it.copy(customName = trimmed) else it
        }
        sourceStore.save(updated)
        mutableSources.value = updated
        publishModules()
    }

    suspend fun remove(url: String) = refreshMutex.withLock {
        requireUserSource(url)
        val updated = mutableSources.value.filterNot { it.url == url }
        sourceStore.save(updated)
        mutableSources.value = updated
        cachedModules.remove(url)
        mutableFailures.value = mutableFailures.value - url
        publishModules()
    }

    private suspend fun publishModules() = withContext(Dispatchers.IO) {
        mutableModules.value = mutableSources.value.flatMap { source ->
            cachedModules[source.url].orEmpty().map { module ->
                val installed = runCatching {
                    SuFile.open("/data/adb/modules/${module.moduleId}/module.prop").exists()
                }.getOrDefault(false)
                module.copy(installed = installed, repositoryName = source.displayName)
            }
        }
    }

    private suspend fun loadSource(source: RepositorySource): RepositoryFeed {
        if (source.isBuiltIn) return loadKernelSuSource(source)
        val body = networkRequestRepository.fetch("${source.url}json/modules.json").getOrThrow()
        val feed = MmrlRepositoryParser.parse(source.url, body)
        return feed.copy(
            source = feed.source.copy(
                customName = source.customName,
            ),
        )
    }

    private fun requireUserSource(url: String) {
        if (url == RepositorySource.KERNEL_SU_URL) throw RepositoryException(RepositoryError.BUILT_IN)
    }

    private suspend fun loadKernelSuSource(source: RepositorySource): RepositoryFeed {
        val body = networkRequestRepository.fetch("${source.url}modules.json").getOrThrow()
        val json = JSONArray(body)
        val modules = coroutineScope {
            (0 until json.length()).map { index ->
                async(Dispatchers.IO) { json.optJSONObject(index)?.let { parseKernelSuModule(source, it) } }
            }.awaitAll().filterNotNull()
        }
        return RepositoryFeed(source, modules)
    }

    private suspend fun parseKernelSuModule(source: RepositorySource, item: JSONObject): CatalogModule? {
        val moduleId = item.optString("moduleId").takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) } ?: return null
        val authorList = item.optJSONArray("authors")?.let { authors ->
            (0 until authors.length()).mapNotNull { index ->
                authors.optJSONObject(index)?.let { author ->
                    author.optString("name").trim().takeIf(String::isNotBlank)?.let { name ->
                        CatalogAuthor(name, stripTicks(author.optString("link")))
                    }
                }
            }
        }.orEmpty()
        val latestReleaseObject = item.optJSONObject("latestRelease")
        val latestRelease = latestReleaseObject?.optString("name", latestReleaseObject.optString("version")).orEmpty()
        val detail = attempt {
            JSONObject(networkRequestRepository.fetch("${source.url}module/$moduleId.json").getOrThrow())
        }.getOrElse {
            cachedModules[source.url]?.firstOrNull { it.moduleId == moduleId }?.let { return it }
            null
        }
        val releases = detail?.optJSONArray("releases")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toKernelSuRelease() }
        }.orEmpty()
        return CatalogModule(
            moduleId = moduleId,
            moduleName = item.optString("moduleName"),
            authors = authorList.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: item.optString("authors"),
            authorList = authorList,
            summary = item.optString("summary"),
            metamodule = item.optBoolean("metamodule"),
            stargazerCount = item.optInt("stargazerCount"),
            updatedAt = item.optString("updatedAt"),
            createdAt = item.optString("createdAt"),
            latestRelease = latestRelease,
            latestReleaseTime = latestReleaseObject?.optString("time").orEmpty(),
            latestVersionCode = latestReleaseObject?.opt("versionCode").toIntCompat(),
            latestAsset = releases.firstOrNull { it.name == latestRelease },
            installed = false,
            readme = detail?.optString("readmeHTML").orEmpty(),
            sourceUrl = stripTicks(detail?.optString("sourceUrl").orEmpty()),
            releases = releases,
            repositoryUrl = source.url,
            repositoryName = source.name,
        )
    }

    private fun JSONObject.toKernelSuRelease(): ModuleRelease {
        val releaseName = optString("name", optString("tagName", optString("version")))
        val assets = optJSONArray("releaseAssets")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { asset ->
                    val name = asset.optString("name")
                    val url = stripTicks(asset.optString("downloadUrl"))
                    if (name.isBlank() || url.isBlank()) {
                        null
                    } else {
                        ModuleReleaseAsset(name, url, asset.optLong("size"), asset.opt("downloadCount").toIntCompat())
                    }
                }
            }
        }.orEmpty()
        return ModuleRelease(
            name = releaseName,
            tagName = optString("tagName", releaseName),
            publishedAt = optString("publishedAt"),
            descriptionHTML = optString("descriptionHTML"),
            assets = assets,
        )
    }

    private fun stripTicks(value: String): String = value.trim().removeSurrounding("`")

    private fun Any?.toIntCompat(): Int = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull() ?: 0
        else -> 0
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun reason(error: Throwable): RepositoryError = (error as? RepositoryException)?.reason ?: RepositoryError.NETWORK
}
