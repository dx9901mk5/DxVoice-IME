package com.dx9901mk5.pixelvoiceime.updater

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.dx9901mk5.pixelvoiceime.BuildConfig
import com.dx9901mk5.pixelvoiceime.data.VoiceImeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val body: String,
    val apkDownloadUrl: String?,
    val htmlUrl: String,
    val hasUpdate: Boolean
)

sealed interface UpdateCheckResult {
    data class Success(val info: ReleaseInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Checks a user-configurable GitHub repository's Releases for newer APK versions,
 * downloads the APK with progress, and launches Android's system package installer.
 */
class GitHubUpdater(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    /**
     * Parses https://github.com/owner/repo (with or without .git or trailing slash)
     * into Pair(owner, repo).
     */
    fun parseOwnerRepo(repoUrl: String): Pair<String, String>? {
        val cleaned = repoUrl.trim()
            .removeSuffix("/")
            .removeSuffix(".git")
        val regex = Regex("""^https?://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""")
        val match = regex.matchEntire(cleaned) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    suspend fun checkLatestRelease(
        repoUrl: String = VoiceImeConfig.DEFAULT_GITHUB_REPO
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val ownerRepo = parseOwnerRepo(repoUrl)
            ?: return@withContext UpdateCheckResult.Error("GitHub 連結格式無效，請輸入 https://github.com/帳號/專案名稱")

        val apiUrl = "https://api.github.com/repos/${ownerRepo.first}/${ownerRepo.second}/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "DxVoice-IME/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val hint = when (response.code) {
                        404 -> "該 GitHub 專案尚未發佈任何 Release，或連結不存在"
                        403 -> "GitHub API 請求頻率受限，請稍後再試"
                        else -> "HTTP ${response.code}"
                    }
                    return@withContext UpdateCheckResult.Error(hint)
                }

                val json = JSONObject(raw)
                val tag = json.optString("tag_name")
                val title = json.optString("name").ifBlank { tag }
                val body = json.optString("body")
                val htmlUrl = json.optString("html_url").ifBlank { repoUrl }

                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                UpdateCheckResult.Success(
                    ReleaseInfo(
                        tagName = tag,
                        title = title,
                        body = body,
                        apkDownloadUrl = apkUrl,
                        htmlUrl = htmlUrl,
                        hasUpdate = isNewerVersion(tag, BuildConfig.VERSION_NAME)
                    )
                )
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.localizedMessage ?: "檢查更新失敗")
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> {
        val downloadResult = downloadApkOnly(context, downloadUrl, onProgress)
        return downloadResult.mapCatching { apkFile ->
            val pkgInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            val remoteCode = if (pkgInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode
                else @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
            } else {
                Long.MAX_VALUE
            }
            if (remoteCode <= BuildConfig.VERSION_CODE.toLong()) {
                apkFile.delete()
                throw IllegalStateException("遠端 APK 版本 (${pkgInfo?.versionName ?: "?"}) 與目前版本相同或較舊，已略過安裝")
            }
            withContext(Dispatchers.Main) {
                launchApkInstaller(context, apkFile)
            }
        }
    }

    suspend fun downloadApkOnly(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val tempFile = File(updatesDir, "DxVoice-IME-update.apk.tmp")
            val apkFile = File(updatesDir, "DxVoice-IME-update.apk")
            if (tempFile.exists()) tempFile.delete()
            if (apkFile.exists()) apkFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("User-Agent", "DxVoice-IME/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("下載失敗 HTTP ${response.code}"))
                }
                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("伺服器未回傳檔案內容"))
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (!tempFile.renameTo(apkFile)) {
                tempFile.copyTo(apkFile, overwrite = true)
                tempFile.delete()
            }
            Result.success(apkFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File) {
        context.startActivity(buildInstallIntent(context, apkFile))
    }

    internal fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val remoteParts = parseSemver(remoteTag)
        val currentParts = parseSemver(currentVersion)
        val count = maxOf(remoteParts.size, currentParts.size, 3)
        for (i in 0 until count) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun parseSemver(raw: String): List<Int> {
        val clean = raw.trim().substringBefore('-').substringBefore('+')
        return Regex("""\d+""").findAll(clean).map { it.value.toIntOrNull() ?: 0 }.toList()
    }
}
