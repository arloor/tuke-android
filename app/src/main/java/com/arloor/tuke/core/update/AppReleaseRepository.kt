package com.arloor.tuke.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long,
    val contentType: String? = null,
)

data class AppReleaseCheckResult(
    val currentVersion: String,
    val currentVersionCode: Long,
    val latestVersion: String,
    val latestVersionCode: Long?,
    val latestTagName: String,
    val releaseName: String,
    val releasePageUrl: String,
    val publishedAt: String?,
    val body: String,
    val hasUpdate: Boolean,
    val apkAsset: AppReleaseAsset?,
)

class AppReleaseRepository(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val latestReleaseApiUrl: String,
) {
    suspend fun check(
        currentVersion: String,
        currentVersionCode: Long,
    ): AppReleaseCheckResult {
        val request = Request.Builder()
            .url(latestReleaseApiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = if (response.code == 404) {
                    "暂未发布可用版本"
                } else {
                    "检查更新失败：HTTP ${response.code}"
                }
                throw IllegalStateException(message)
            }
            val raw = response.body?.string()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("检查更新失败：响应为空")
            val release = runCatching { json.decodeFromString<GitHubRelease>(raw) }
                .getOrElse { throw IllegalStateException("检查更新失败：版本信息格式错误", it) }
            val version = parseReleaseVersion(release.tagName)
                ?: throw IllegalStateException("检查更新失败：无法识别版本 ${release.tagName}")
            val apk = release.assets
                .firstOrNull { it.name.equals(PREFERRED_APK_NAME, ignoreCase = true) }
                ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

            return AppReleaseCheckResult(
                currentVersion = currentVersion,
                currentVersionCode = currentVersionCode,
                latestVersion = version.versionName,
                latestVersionCode = version.versionCode,
                latestTagName = release.tagName,
                releaseName = release.name?.takeIf { it.isNotBlank() } ?: release.tagName,
                releasePageUrl = release.htmlUrl,
                publishedAt = release.publishedAt,
                body = release.body.orEmpty(),
                hasUpdate = isReleaseNewer(
                    currentVersion = currentVersion,
                    currentVersionCode = currentVersionCode,
                    latestVersion = version,
                ),
                apkAsset = apk?.let {
                    AppReleaseAsset(
                        name = it.name,
                        browserDownloadUrl = it.browserDownloadUrl,
                        size = it.size,
                        contentType = it.contentType,
                    )
                },
            )
        }
    }

    private companion object {
        const val PREFERRED_APK_NAME = "release.apk"
    }
}

internal data class ReleaseVersion(
    val versionName: String,
    val versionCode: Long?,
    val semanticParts: List<Long>,
)

internal fun parseReleaseVersion(tag: String): ReleaseVersion? {
    val normalized = tag.trim().removePrefix("v").removePrefix("V")
    val versionName = normalized.substringBefore("+code.").trim()
    val semanticCore = versionName.substringBefore('-')
    val semanticParts = semanticCore.split('.').map { it.toLongOrNull() ?: return null }
    if (semanticParts.isEmpty()) return null
    val versionCode = Regex("""\+code\.(\d+)""")
        .find(normalized)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
    return ReleaseVersion(versionName, versionCode, semanticParts)
}

internal fun isReleaseNewer(
    currentVersion: String,
    currentVersionCode: Long,
    latestVersion: ReleaseVersion,
): Boolean {
    latestVersion.versionCode?.let { return it > currentVersionCode }
    val current = parseReleaseVersion(currentVersion)?.semanticParts ?: return false
    val size = maxOf(current.size, latestVersion.semanticParts.size)
    for (index in 0 until size) {
        val currentPart = current.getOrElse(index) { 0L }
        val latestPart = latestVersion.semanticParts.getOrElse(index) { 0L }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
    @SerialName("content_type") val contentType: String? = null,
)
