package com.cyberx3mal.videodownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: NativeBridge
    private var pendingShareUrl: String? = null
    private var pageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                android.util.Log.i(
                    "WebView",
                    "${cm.message()} [${cm.lineNumber()}@${cm.sourceId()}]"
                )
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                webView.evaluateJavascript("1+1") { res ->
                    android.util.Log.i("MainActivity", "js probe result=$res")
                }
                webView.evaluateJavascript(
                    "typeof window.__native+'|'+typeof window.prefillUrl+'|'+typeof AndroidDownloader",
                    { res -> android.util.Log.i("MainActivity", "js types=$res") }
                )
                // Р•СЃР»Рё Р¶РґС‘С‚ СЃСЃС‹Р»РєР° РёР· "РџРѕРґРµР»РёС‚СЊСЃСЏ" вЂ” РїРѕРґСЃС‚Р°РІР»СЏРµРј РµС‘.
                if (pendingShareUrl != null) {
                    injectShareUrl(120)
                } else {
                    probeClipboard()
                }
            }
        }

        bridge = NativeBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidDownloader")

        webView.loadUrl("file:///android_asset/www/index.html")

        requestLegacyStoragePermission()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = pickSiteLink(text)
        if (url.isBlank()) return
        pendingShareUrl = url
        // Р•СЃР»Рё СЃС‚СЂР°РЅРёС†Р° СѓР¶Рµ Р·Р°РіСЂСѓР¶РµРЅР° вЂ” РїРѕРґСЃС‚Р°РІР»СЏРµРј СЃСЂР°Р·Сѓ, РёРЅР°С‡Рµ РґРѕР¶РґС‘РјСЃСЏ onPageFinished.
        if (pageReady) injectShareUrl(60)
    }

    private fun probeClipboard() {
        val url = pickSiteLink(readClipboardText())
        if (url.isBlank()) return
        webView.postDelayed({
            try {
                webView.evaluateJavascript(
                    "window.autoClipboard && window.autoClipboard(${JSONObject.quote(url)});",
                    null
                )
            } catch (ignored: Exception) {
            }
        }, 400)
    }

    // Р”РѕСЃС‚Р°С‘С‚ РёР· С‚РµРєСЃС‚Р° РїРµСЂРІСѓСЋ СЃСЃС‹Р»РєСѓ. РџСЂРёРѕСЂРёС‚РµС‚ вЂ” РїРѕРґРґРµСЂР¶РёРІР°РµРјС‹Рµ СЃРѕС†СЃРµС‚Рё,
    // РЅРѕ Р»СЋР±Р°СЏ РґСЂСѓРіР°СЏ http(s)-СЃСЃС‹Р»РєР° С‚РѕР¶Рµ РїРѕРґРѕР№РґС‘С‚ (yt-dlp СЃРѕРѕР±С‰РёС‚, РµСЃР»Рё РЅРµ СЃРјРѕР¶РµС‚).
    private fun pickSiteLink(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val trimmed = text.trim()
        val any = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
            .find(trimmed)
        val social = Regex(
            "(?:https?://)?(?:www\\.)?(?:instagram\\.com|tiktok\\.com|vm\\.tiktok\\.com|vt\\.tiktok\\.com|" +
                "vk\\.com|vkvideo\\.ru|vk\\.clip|youtube\\.com|youtu\\.be|m\\.youtube\\.com)/[^\\s\"'<>]+",
            RegexOption.IGNORE_CASE
        ).find(trimmed)

        val raw = (social?.value ?: any?.value ?: "").trim()
        if (raw.isBlank()) return ""
        var u = raw.trimEnd(']', ')', '}', '>', '"', '\'', ',', ';', '!', '.')
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "https://$u"
        }
        return u
    }

    private fun readClipboardText(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun injectShareUrl(delayMs: Long = 200L) {
        val url = pendingShareUrl ?: return
        webView.postDelayed({
            if (pendingShareUrl == null) return@postDelayed
            val js = "window.prefillUrl && window.prefillUrl(${JSONObject.quote(url)});"
            try {
                webView.evaluateJavascript(js) { applied ->
                    if ("true" == applied) pendingShareUrl = null
                }
            } catch (ignored: Exception) {
            }
        }, delayMs)
    }

    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Р‘РµР· РґРѕСЃС‚СѓРїР° Рє РїР°РјСЏС‚Рё СЃРєР°С‡РёРІР°РЅРёРµ РІ В«Р—Р°РіСЂСѓР·РєРёВ» РјРѕР¶РµС‚ РЅРµ СЂР°Р±РѕС‚Р°С‚СЊ",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Плеер поверх приложения не должен жить после возврата.
        if (Player.isShowing()) Player.closeCurrent()
        if (pageReady) {
            webView.evaluateJavascript("window.onAppResume && window.onAppResume();", null)
        }
    }

    override fun onPause() {
        if (Player.isShowing()) Player.closeCurrent()
        super.onPause()
    }

    override fun onBackPressed() {
        if (Player.isShowing()) {
            Player.closeCurrent()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        bridge.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
