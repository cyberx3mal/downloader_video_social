package com.cyberx3mal.videodownloader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class NativeBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var previewJob: Job? = null

    @JavascriptInterface
    fun resolve(url: String) {
        android.util.Log.i("NativeBridge", "resolve() called with: $url")
        scope.launch {
            try {
                val json = YtdlpHelper.resolve(activity, url)
                callJs("window.__native.onResolve(${JSONObject.quote(json)})")
            } catch (e: Exception) {
                val msg = extractErrorMessage(e)
                android.util.Log.e("NativeBridge", "resolve failed: $msg", e)
                callJs("window.__native.onResolveError(${JSONObject.quote(msg)})")
            }
        }
    }

    @JavascriptInterface
    fun download(url: String, quality: String) {
        android.util.Log.i("NativeBridge", "download() called url=$url quality=$quality")
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try {
                val name = YtdlpHelper.download(activity, url, quality) { percent, _ ->
                    val p = percent.toInt().coerceIn(0, 100)
                    callJs("window.__native.onProgress($p)")
                }
                callJs("window.__native.onDownloadDone(${JSONObject.quote(name)})")
            } catch (e: Exception) {
                val msg = (e as? ExtractError)?.message
                    ?: YtdlpHelper.friendly(e.message ?: e.toString())
                callJs("window.__native.onDownloadError(${JSONObject.quote(msg)})")
            }
        }
    }

    @JavascriptInterface
    fun cancel() {
        downloadJob?.cancel()
        previewJob?.cancel()
    }

    // Готовит превью-копию и отдаёт путь в JS — видео играем прямо в карточке.
    @JavascriptInterface
    fun preparePreview(url: String) {
        android.util.Log.i("NativeBridge", "preparePreview() called url=$url")
        if (previewJob?.isActive == true) return
        previewJob = scope.launch {
            try {
                callJs("window.__native.onPreviewStart()")
                val file = YtdlpHelper.preparePreview(activity, url) { percent ->
                    callJs("window.__native.onPreviewProgress(${percent.toInt().coerceIn(0, 100)})")
                }
                android.util.Log.i("NativeBridge", "preview ready: ${file.absolutePath}")
                callJs("window.__native.onPreviewPath(${JSONObject.quote(file.absolutePath)})")
                callJs("window.__native.onPreviewDone(${JSONObject.quote(file.name)})")
            } catch (e: Exception) {
                val msg = extractErrorMessage(e)
                android.util.Log.e("NativeBridge", "preview failed: $msg", e)
                callJs("window.__native.onPreviewError(${JSONObject.quote(msg)})")
            }
        }
    }

    @JavascriptInterface
    fun readClipboard(): String {
        return try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item: ClipData.Item? = cm.primaryClip?.getItemAt(0)
            item?.text?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun updateYtdlp() {
        scope.launch {
            try {
                callJs("window.__native.onUpdateProgress(\"Проверка обновления...\")")
                val status = com.yausername.youtubedl_android.YoutubeDL
                    .getInstance()
                    .updateYoutubeDL(activity, com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.NIGHTLY)
                callJs("window.__native.onUpdateDone(${org.json.JSONObject.quote(status?.name ?: "DONE")})")
            } catch (e: Exception) {
                callJs("window.__native.onUpdateError(${org.json.JSONObject.quote(e.message ?: "Ошибка обновления")})")
            }
        }
    }

    private fun extractErrorMessage(e: Exception): String =
        (e as? ExtractError)?.message
            ?: YtdlpHelper.friendly(e.message ?: e.toString())

    fun destroy() {
        scope.cancel()
    }

    private fun callJs(js: String, onResult: ((String?) -> Unit)? = null) {
        webView.post {
            try {
                if (onResult != null) {
                    webView.evaluateJavascript(js, onResult)
                } else {
                    webView.evaluateJavascript(js, null)
                }
            } catch (e: Exception) {
                android.util.Log.e("NativeBridge", "callJs failed", e)
            }
        }
    }
}
