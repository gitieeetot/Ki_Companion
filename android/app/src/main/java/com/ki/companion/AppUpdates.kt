package com.ki.companion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Xml
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

private const val GITHUB_RELEASES_URL =
    "https://api.github.com/repos/gitieeetot/Ki_Companion/releases?per_page=20"
private const val GITHUB_RELEASES_FEED_URL =
    "https://github.com/gitieeetot/Ki_Companion/releases.atom"
private const val GITHUB_RELEASE_DOWNLOAD_BASE =
    "https://github.com/gitieeetot/Ki_Companion/releases/download/"
private const val UPDATE_APK_NAME = "KiCompanion.apk"
private const val MAX_UPDATE_SIZE = 100L * 1024 * 1024
private val RELEASE_VERSION_CODE_REGEX = Regex("""Ki-Companion-Version-Code:\s*(\d+)""", RegexOption.IGNORE_CASE)

data class AppUpdate(
    val versionName: String,
    val versionCode: Long?,
    val downloadUrl: String,
    val notes: String,
)

sealed interface UpdateCheckResult {
    data class Available(val update: AppUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
}

fun checkForAppUpdate(): UpdateCheckResult {
    val connection = openGitHubConnection(GITHUB_RELEASES_URL)
    try {
        val status = connection.responseCode
        if (status == 403 || status == 429) {
            return checkForAppUpdateFromFeed()
        }
        if (status !in 200..299) {
            throw IOException(githubErrorMessage(status))
        }
        val releases = JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        var foundAndroidRelease = false
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            if (!tag.startsWith("android-v")) continue
            foundAndroidRelease = true
            val version = tag.removePrefix("android-v")
            val notes = release.optString("body")
            val versionCode = RELEASE_VERSION_CODE_REGEX.find(notes)?.groupValues?.get(1)?.toLongOrNull()
            val hasNewerVersion = isNewerVersion(version, BuildConfig.VERSION_NAME)
            val hasNewerBuild = versionCode != null && versionCode > BuildConfig.VERSION_CODE
            if (!hasNewerVersion && !hasNewerBuild) continue
            val assets = release.optJSONArray("assets") ?: continue
            val apk = findApkAsset(assets)
                ?: throw IOException("GitHub release $tag is missing its KiCompanion.apk file.")
            return UpdateCheckResult.Available(
                AppUpdate(
                    versionName = version,
                    versionCode = versionCode,
                    downloadUrl = apk.optString("browser_download_url"),
                    notes = notes.take(4_000),
                ),
            )
        }
        if (!foundAndroidRelease) throw IOException("No Android update release is published on GitHub yet.")
        return UpdateCheckResult.UpToDate
    } finally {
        connection.disconnect()
    }
}

private fun checkForAppUpdateFromFeed(): UpdateCheckResult {
    val connection = openGitHubConnection(GITHUB_RELEASES_FEED_URL)
    try {
        val status = connection.responseCode
        if (status !in 200..299) throw IOException(githubErrorMessage(status))
        val parser = Xml.newPullParser()
        parser.setInput(connection.inputStream, Charsets.UTF_8.name())
        var event = parser.eventType
        var inEntry = false
        var inContent = false
        var releaseLink: String? = null
        val releaseContent = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        inEntry = true
                        releaseLink = null
                        releaseContent.setLength(0)
                    }
                    "link" -> if (
                        inEntry &&
                        (parser.getAttributeValue(null, "rel") == null ||
                            parser.getAttributeValue(null, "rel") == "alternate")
                    ) {
                        releaseLink = parser.getAttributeValue(null, "href")
                    }
                    "content" -> if (inEntry) inContent = true
                }
                XmlPullParser.TEXT -> if (inEntry && inContent) {
                    releaseContent.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "content" -> inContent = false
                    "entry" -> {
                        inEntry = false
                        val tag = releaseTagFromFeedLink(releaseLink) ?: continue
                        val version = tag.removePrefix("android-v")
                        if (!isReleaseNewer(version, releaseContent.toString())) continue
                        return UpdateCheckResult.Available(
                            AppUpdate(
                                versionName = version,
                                versionCode = RELEASE_VERSION_CODE_REGEX.find(releaseContent)
                                    ?.groupValues?.get(1)?.toLongOrNull(),
                                downloadUrl = "$GITHUB_RELEASE_DOWNLOAD_BASE$tag/$UPDATE_APK_NAME",
                                notes = releaseContent.toString()
                                    .replace(Regex("<[^>]*>"), " ")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                                    .take(4_000),
                            ),
                        )
                    }
                }
            }
            event = parser.next()
        }
        return UpdateCheckResult.UpToDate
    } finally {
        connection.disconnect()
    }
}

private fun releaseTagFromFeedLink(link: String?): String? {
    if (link == null) return null
    val uri = try {
        URI(link)
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme != "https" || uri.host != "github.com" ||
        uri.rawQuery != null || uri.rawFragment != null
    ) return null
    val match = Regex("^/gitieeetot/Ki_Companion/releases/tag/(android-v\\d+\\.\\d+\\.\\d+)$")
        .matchEntire(uri.path) ?: return null
    return match.groupValues[1]
}

private fun isReleaseNewer(version: String, notes: String): Boolean {
    if (isNewerVersion(version, BuildConfig.VERSION_NAME)) return true
    val versionCode = RELEASE_VERSION_CODE_REGEX.find(notes)?.groupValues?.get(1)?.toLongOrNull()
    return versionCode != null && versionCode > BuildConfig.VERSION_CODE
}

private fun findApkAsset(assets: JSONArray): JSONObject? {
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("name") == UPDATE_APK_NAME &&
            asset.optLong("size") in 1L..MAX_UPDATE_SIZE &&
            asset.optString("browser_download_url").startsWith("https://github.com/")
        ) {
            return asset
        }
    }
    return null
}

private fun isNewerVersion(remote: String, installed: String): Boolean {
    val remoteParts = remote.split('.').map { it.toIntOrNull() ?: return false }
    val installedParts = installed.split('.').map { it.toIntOrNull() ?: return false }
    if (remoteParts.size != 3 || installedParts.size != 3) return false
    for (index in 0..2) {
        if (remoteParts[index] != installedParts[index]) {
            return remoteParts[index] > installedParts[index]
        }
    }
    return false
}

private fun openGitHubConnection(address: String): HttpURLConnection {
    val connection = URL(address).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    connection.connectTimeout = 12_000
    connection.readTimeout = 20_000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    connection.setRequestProperty("User-Agent", "Ki-Companion-Android")
    return connection
}

private fun githubErrorMessage(status: Int): String = when (status) {
    404 -> "No Android update release is published on GitHub yet."
    403, 429 -> "GitHub is temporarily limiting update checks. Try again later."
    else -> "GitHub update check failed (HTTP $status)."
}

fun downloadAndVerifyUpdate(context: Context, update: AppUpdate): File {
    val connection = openGitHubConnection(update.downloadUrl)
    val updateDirectory = File(context.cacheDir, "updates")
    val apkFile = File(updateDirectory, UPDATE_APK_NAME)
    val partialFile = File(updateDirectory, "$UPDATE_APK_NAME.part")
    try {
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("GitHub APK download failed (HTTP $status).")
        val length = connection.contentLengthLong
        if (length !in 1..MAX_UPDATE_SIZE) throw IOException("The APK has an invalid or oversized download.")
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw IOException("Could not prepare storage for the update.")
        }
        connection.inputStream.use { input ->
            FileOutputStream(partialFile).use { output ->
                val buffer = ByteArray(16_384)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_UPDATE_SIZE || total > length) {
                        throw IOException("The APK download exceeded its declared size.")
                    }
                    output.write(buffer, 0, count)
                }
                if (total != length) throw IOException("The APK download was incomplete.")
            }
        }
        if (!partialFile.renameTo(apkFile)) {
            throw IOException("Could not finish saving the downloaded APK.")
        }
        verifyDownloadedUpdate(context, apkFile, update)
        return apkFile
    } catch (error: Exception) {
        partialFile.delete()
        apkFile.delete()
        throw error
    } finally {
        connection.disconnect()
    }
}

private fun verifyDownloadedUpdate(context: Context, apkFile: File, update: AppUpdate) {
    val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        ?: throw IOException("The downloaded file is not a valid Android app.")
    if (archiveInfo.packageName != context.packageName) {
        throw IOException("This GitHub release APK is for a different app.")
    }
    val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        archiveInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        archiveInfo.versionCode.toLong()
    }
    if (archiveVersionCode <= currentVersionCode(context)) {
        throw IOException("The downloaded APK is not newer than this installed version.")
    }
    if (update.versionCode != null && archiveVersionCode != update.versionCode) {
        throw IOException("The APK build number does not match its GitHub release.")
    }
    if (archiveInfo.versionName != update.versionName) {
        throw IOException("The APK version does not match its GitHub release.")
    }
}

private fun currentVersionCode(context: Context): Long {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
}

fun updateApkUri(context: Context, apkFile: File) = FileProvider.getUriForFile(
    context,
    "${context.packageName}.fileprovider",
    apkFile,
)
