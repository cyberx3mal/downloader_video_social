package com.cyberx3mal.videodownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ExtractError(message: String) : Exception(message)

object YtdlpHelper {

    private val QUALITY_STEPS = listOf(2160, 1440, 1080, 720, 480, 360)
    private val MEDIA_EXTS = setOf("mp4", "m4a", "webm", "mkv", "mov", "opus", "mp3")
    private val SHARE_RE = Regex("instagram\\.com/share/|(?:vm|vt)\\.tiktok\\.com/")

    @Volatile
    private var initialized = false

    // Однократно пробуем обновить yt-dlp до nightly: платформы часто ломают извлечение,
    // свежий билд это чинит. Ошибка обновления не критична.
    @Volatile
    private var updateAttempted = false

    @Synchronized
    private fun updateYtdlpOnce() {
        if (updateAttempted) return
        updateAttempted = true
        try {
            val app = appContextRef?.get() ?: return
            android.util.Log.i("YtdlpHelper", "yt-dlp nightly update check...")
            val status = com.yausername.youtubedl_android.YoutubeDL.getInstance()
                .updateYoutubeDL(app, com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.NIGHTLY)
            android.util.Log.i("YtdlpHelper", "yt-dlp update: ${status?.name}")
        } catch (e: Exception) {
            android.util.Log.w("YtdlpHelper", "yt-dlp update skipped: ${e.message}")
        }
    }

    private var appContextRef: java.lang.ref.WeakReference<android.content.Context>? = null

    // Локальный прокси VPN-клиента (Киберпортал X / INCY / ByeDPI и т.п.).
    // Автоматически находится при старте: socks5 на 1080 или http на 7890.
    @Volatile
    private var proxyUrl: String? = null

    @Volatile
    private var proxyChecked = false

    /**
     * Ищет локальный прокси и проверяет, что через него доступен youtube.com.
     * Так приложение работает даже когда обычный DNS/TLS режется провайдером.
     */
    fun detectProxy() {
        val candidates = listOf(
            "http://127.0.0.1:7890",
            "socks5h://127.0.0.1:1080",
            "http://127.0.0.1:8888",
            "socks5h://127.0.0.1:9050"
        )
        for (candidate in candidates) {
            if (proxyWorks(candidate)) {
                proxyUrl = candidate
                proxyChecked = true
                android.util.Log.i("YtdlpHelper", "proxy detected: $candidate")
                return
            }
        }
        proxyUrl = null
        proxyChecked = true
        android.util.Log.i("YtdlpHelper", "proxy not found — работаем напрямую")
    }

    fun currentProxy(): String? = proxyUrl

    fun refreshProxy() {
        proxyChecked = false
        detectProxyIfNeeded()
    }

    @Synchronized
    private fun detectProxyIfNeeded() {
        if (!proxyChecked) detectProxy()
    }

    private fun proxyWorks(proxy: String): Boolean {
        // Порт должен быть открыт локально.
        val port = proxy.substringAfterLast(':').toIntOrNull() ?: return false
        if (!isPortOpen(port)) return false
        // Проверяем доступ и к YouTube, и к TikTok: разные площадки могут
        // ходить через разные режимы локального прокси VPN-клиента.
        val ok = listOf("https://www.youtube.com/generate_204", "https://www.tiktok.com/")
        return try {
            val proxyType = if (proxy.startsWith("socks")) java.net.Proxy.Type.SOCKS
            else java.net.Proxy.Type.HTTP
            val addr = java.net.InetSocketAddress("127.0.0.1", port)
            var anyOk = false
            for (target in ok) {
                try {
                    val conn = URL(target).openConnection(
                        java.net.Proxy(proxyType, addr)
                    ) as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 8000
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..499) anyOk = true
                } catch (ignored: Exception) {
                }
            }
            anyOk
        } catch (e: Exception) {
            false
        }
    }

    private fun isPortOpen(port: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1500)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** Список прокси-кандидатов в порядке приоритета. */
    private fun proxyCandidates(): List<String> = listOf(
        "socks5h://127.0.0.1:1080",
        "http://127.0.0.1:7890"
    )

    /**
     * Повторяет запрос, перебирая варианты маршрута: выбранный прокси,
     * альтернативные локальные прокси, прямое подключение.
     * Возвращает первый успешный ответ, иначе кидает последнюю ошибку.
     */
    // Запоминаем удачный маршрут для каждого хоста: youtube.com -> socks://...
    // Следующие запросы к этому хосту идут сразу по нему, без перебора.
    private val routeCache = java.util.concurrent.ConcurrentHashMap<String, String?>()

    private fun hostOf(url: String): String =
        try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }

    /**
     * Запускает запрос параллельно на всех доступных маршрутах и возвращает
     * первый успешный ответ. Если для хоста уже известен удачный маршрут —
     * сначала пробуем только его (мгновенный ответ), и лишь при неудаче
     * запускаем перебор остальных.
     */
    private fun executeParallel(
        build: (String?) -> YoutubeDLRequest,
        url: String
    ): com.yausername.youtubedl_android.YoutubeDLResponse {
        detectProxyIfNeeded()
        val host = hostOf(url)
        val cached = routeCache[host]
        if (cached != null && (host != "")) {
            // Быстрый путь: известный рабочий маршрут.
            try {
                val request = build(cached)
                android.util.Log.i("YtdlpHelper", "cached route=$cached for $host")
                val resp = YoutubeDL.getInstance().execute(request)
                return resp
            } catch (e: Exception) {
                android.util.Log.w("YtdlpHelper", "cached route failed: ${e.message?.take(110)}")
                routeCache.remove(host)
            }
        }
        val routes = mutableListOf<String?>()
        for (alt in proxyCandidates()) {
            if (alt !in routes && isPortOpen(alt.substringAfterLast(':').toInt())) routes.add(alt)
        }
        routes.add(null) // прямое подключение

        android.util.Log.i("YtdlpHelper", "parallel routes: ${routes.joinToString { it ?: "direct" }}")

        val executor = java.util.concurrent.Executors.newFixedThreadPool(routes.size)
        try {
            val futures = routes.map { route ->
                executor.submit(java.util.concurrent.Callable {
                    try {
                        val request = build(route)
                        android.util.Log.i("YtdlpHelper", "start route=${route ?: "direct"}")
                        val resp = YoutubeDL.getInstance().execute(request)
                        android.util.Log.i("YtdlpHelper", "ok route=${route ?: "direct"}")
                        route to resp
                    } catch (e: Exception) {
                        android.util.Log.w("YtdlpHelper", "fail route=${route ?: "direct"}: ${e.message?.take(110)}")
                        null
                    }
                })
            }
            while (futures.isNotEmpty()) {
                val done = futures.firstOrNull { it.isDone && it.get() != null }
                if (done != null) {
                    val (route, resp) = done.get()!!
                    if (host != "") routeCache[host] = route
                    futures.forEach { it.cancel(true) }
                    return resp
                }
                if (futures.all { it.isDone }) {
                    throw ExtractError("Не удалось получить данные (все маршруты недоступны)")
                }
                Thread.sleep(250)
            }
            throw ExtractError("Не удалось выполнить запрос")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun executeWithFallbackProgress(
        build: (String?) -> YoutubeDLRequest,
        onProgress: (Float, String) -> Unit,
        url: String
    ): com.yausername.youtubedl_android.YoutubeDLResponse {
        detectProxyIfNeeded()
        val host = hostOf(url)
        val cached = routeCache[host]
        if (cached != null && host != "") {
            try {
                val request = build(cached)
                android.util.Log.i("YtdlpHelper", "cached route=$cached for $host")
                return YoutubeDL.getInstance().execute(request, null) { p, _, line ->
                    onProgress(p, line ?: "")
                }
            } catch (e: Exception) {
                android.util.Log.w("YtdlpHelper", "cached route failed: ${e.message?.take(110)}")
                routeCache.remove(host)
            }
        }
        val routes = mutableListOf<String?>()
        for (alt in proxyCandidates()) {
            if (alt !in routes && isPortOpen(alt.substringAfterLast(':').toInt())) routes.add(alt)
        }
        routes.add(null)

        android.util.Log.i("YtdlpHelper", "parallel routes: ${routes.joinToString { it ?: "direct" }}")

        val executor = java.util.concurrent.Executors.newFixedThreadPool(routes.size)
        try {
            val futures = routes.map { route ->
                executor.submit(java.util.concurrent.Callable {
                    try {
                        val request = build(route)
                        val resp = YoutubeDL.getInstance().execute(request, null) { p, _, line ->
                            onProgress(p, line ?: "")
                        }
                        android.util.Log.i("YtdlpHelper", "ok route=${route ?: "direct"}")
                        route to resp
                    } catch (e: Exception) {
                        android.util.Log.w("YtdlpHelper", "fail route=${route ?: "direct"}: ${e.message?.take(110)}")
                        null
                    }
                })
            }
            while (futures.isNotEmpty()) {
                val done = futures.firstOrNull { it.isDone && it.get() != null }
                if (done != null) {
                    val (route, resp) = done.get()!!
                    if (host != "") routeCache[host] = route
                    futures.forEach { it.cancel(true) }
                    return resp
                }
                if (futures.all { it.isDone }) {
                    throw ExtractError("Не удалось скачать видео (все маршруты недоступны)")
                }
                Thread.sleep(250)
            }
            throw ExtractError("Не удалось выполнить запрос")
        } finally {
            executor.shutdownNow()
        }
    }

    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        appContextRef = java.lang.ref.WeakReference(app)
        YoutubeDL.getInstance().init(app)
        try {
            FFmpeg.getInstance().init(app)
        } catch (ignored: Exception) {
        }
        initialized = true
        Thread { updateYtdlpOnce() }.start()
    }

    // ---------- helpers ----------

    private fun codec(o: JSONObject, name: String): String =
        if (o.isNull(name)) "" else o.optString(name, "")

    private fun safeStr(o: JSONObject, name: String, def: String = ""): String =
        if (o.isNull(name)) def else o.optString(name, def)

    private fun fmtSize(o: JSONObject): Long {
        val exact = o.optLong("filesize", 0L)
        if (exact > 0) return exact
        return o.optLong("filesize_approx", 0L)
    }

    private fun formatsOf(info: JSONObject): List<JSONObject> {
        val arr = info.optJSONArray("formats") ?: return emptyList()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun hasAudio(formats: List<JSONObject>): Boolean {
        for (f in formats) {
            val v = codec(f, "vcodec")
            val a = codec(f, "acodec")
            if (v.isEmpty() && a.isNotEmpty()) return true
            if (a.isNotEmpty() && f.optInt("height", 0) > 0) return true
        }
        return false
    }

    private fun estimateSize(formats: List<JSONObject>, cap: Int?, audioOnly: Boolean): Long? {
        if (audioOnly) {
            val sizes = formats
                .filter { codec(it, "vcodec").isEmpty() && codec(it, "acodec").isNotEmpty() }
                .map { fmtSize(it) }
                .filter { it > 0 }
            return sizes.maxOrNull()
        }
        val videos = formats.filter {
            codec(it, "vcodec").isNotEmpty() && (cap == null || it.optInt("height", 0) <= cap)
        }
        if (videos.isEmpty()) return null
        val best = videos.maxWithOrNull(
            compareBy({ it.optInt("height", 0) }, { fmtSize(it) })
        ) ?: return null
        var total = fmtSize(best)
        if (codec(best, "acodec").isEmpty()) {
            val audios = formats
                .filter { codec(it, "vcodec").isEmpty() && codec(it, "acodec").isNotEmpty() }
                .map { fmtSize(it) }
            total += audios.maxOrNull() ?: 0L
        }
        return if (total > 0) total else null
    }

    /**
     * Ищет прямую ссылку на поток, который умеет играть <video> в WebView:
     * сначала progressive mp4 (видео+звук одним файлом), затем HLS-манифест.
     * Возвращает "" если прямой ссылки нет (тогда используем копию через preparePreview).
     */
    private fun pickPlayUrl(info: JSONObject, formats: List<JSONObject>): String {
        fun urlOf(o: JSONObject) = safeStr(o, "url")
        fun protoOf(o: JSONObject) = safeStr(o, "protocol").lowercase()

        // 1. Progressive mp4 с видео и звуком — самый надёжный вариант.
        val progressive = formats.filter {
            codec(it, "vcodec").isNotEmpty() && codec(it, "acodec").isNotEmpty() &&
                urlOf(it).isNotEmpty() &&
                (protoOf(it).isEmpty() || protoOf(it).contains("http"))
        }
        val best = progressive.maxWithOrNull(
            compareBy({ it.optInt("height", 0) }, { fmtSize(it) })
        )
        if (best != null) return urlOf(best)

        // 2. HLS-манифест (m3u8) — WebView умеет стримить.
        val hls = formats.firstOrNull {
            urlOf(it).isNotEmpty() &&
                (protoOf(it).contains("m3u8") || urlOf(it).contains(".m3u8"))
        }
        if (hls != null) return urlOf(hls)

        // 3. Единственный формат без разделения на видео/звук.
        val url = safeStr(info, "url")
        if (url.isNotEmpty() && codec(info, "vcodec").isNotEmpty()) return url

        return ""
    }

    fun buildResult(srcUrl: String, info: JSONObject): JSONObject {
        val formats = formatsOf(info)

        val heights = formats
            .filter { codec(it, "vcodec").isNotEmpty() && it.optInt("height", 0) > 0 }
            .map { it.getInt("height") }
            .distinct()
            .sortedDescending()

        var qualities = QUALITY_STEPS.filter { it in heights }
        if (qualities.isEmpty()) qualities = heights.take(3)

        val options = JSONArray()
        options.put(
            JSONObject()
                .put("id", "best")
                .put("label", "Лучшее качество")
                .put("size", estimateSize(formats, null, false) ?: JSONObject.NULL)
        )
        for (h in qualities) {
            options.put(
                JSONObject()
                    .put("id", h.toString())
                    .put("label", "${h}p")
                    .put("size", estimateSize(formats, h, false) ?: JSONObject.NULL)
            )
        }
        if (hasAudio(formats)) {
            options.put(
                JSONObject()
                    .put("id", "audio")
                    .put("label", "Только звук (m4a)")
                    .put("size", estimateSize(formats, null, true) ?: JSONObject.NULL)
            )
        }

        val uploader = safeStr(info, "uploader")
            .ifBlank { safeStr(info, "channel") }
            .ifBlank { safeStr(info, "uploader_id") }
        val platform = safeStr(info, "extractor_key").ifBlank { safeStr(info, "extractor") }

        return JSONObject().apply {
            put("url", srcUrl)
            put("title", safeStr(info, "title", "video").trim().ifBlank { "video" })
            put("uploader", uploader)
            put("duration", info.optInt("duration", 0))
            put("platform", platform)
            put("thumbnail", safeStr(info, "thumbnail"))
            put("playUrl", pickPlayUrl(info, formats))
            put("options", options)
        }
    }

    fun checkUrl(url: String): String {
        val u = url.trim()
        val lower = u.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw ExtractError("Некорректная ссылка")
        }
        return u
    }

    fun expandUrl(url: String): String {
        if (!SHARE_RE.containsMatchIn(url)) return url
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.requestMethod = "GET"
            conn.responseCode
            val finalUrl = conn.url.toString()
            conn.disconnect()
            finalUrl
        } catch (e: Exception) {
            url
        }
    }

    fun friendly(message: String): String {
        val low = message.lowercase()
        return when {
            low.contains("not a bot") || low.contains("botguard") ->
                "YouTube заблокировал запрос: требуется подтверждение, что вы не бот. " +
                    "Это срабатывает для части IP (и особенно для VPN/облачных адресов). " +
                    "Обновите yt-dlp кнопкой ниже, смените сеть/VPN или попробуйте другую ссылку."

            listOf("login", "cookies", "rate-limit", "rate limit", "sign in", "logged")
                .any { low.contains(it) } ->
                "Платформа требует авторизацию или временно ограничила доступ " +
                    "с этого адреса. Попробуйте позже или другую ссылку."

            low.contains("unsupported url") ->
                "Ссылка не поддерживается. Нужна ссылка на конкретное видео, рилс или клип."

            low.contains("private") ->
                "Видео приватное — скачать без доступа к аккаунту нельзя."

            listOf(
                "connection reset", "timed out", "unable to download api page",
                "failed to resolve", "network is unreachable", "name or service",
                "connection refused", "ssl", "handshake"
            ).any { low.contains(it) } ->
                "Сайт платформы недоступен из вашей сети. " +
                    "Включите VPN и попробуйте снова."

            low.contains("http error") || low.contains("unable to download") ->
                "Платформа не отдала видео. Попробуйте ещё раз позже."

            low.contains("po token") || low.contains("missing pot") ->
                "Видео требует PO-токен, который нельзя сгенерировать без браузера. " +
                    "Попробуйте другую ссылку или обновите yt-dlp."

            message.isBlank() -> "Не удалось получить видео"
            else -> "Не удалось получить видео: " + message.take(300)
        }
    }

    private fun friendlyOf(e: Exception): String {
        val raw = (e.message ?: "").ifBlank { e.toString() }
        android.util.Log.e("YtdlpHelper", "raw error: $raw", e)
        return friendly(raw)
    }

    // ---------- resolve ----------

    fun resolve(context: Context, rawUrl: String): String {
        ensureInit(context)
        val url = expandUrl(checkUrl(rawUrl))
        android.util.Log.i("YtdlpHelper", "resolve url=$url")

        val response = try {
            executeParallel({ route ->
                val request = YoutubeDLRequest(url)
                request.addOption("--dump-single-json")
                request.addOption("--no-warnings")
                request.addOption("--no-playlist")
                request.addOption("--socket-timeout", "12")
                request.addOption("--retries", "1")
                request.addOption("--extractor-retries", "0")
                request.addOption("--extractor-args", "youtube:player_client=default,mweb,web_safari;formats=missing_pot")
                route?.let { request.addOption("--proxy", it) }
                request
            }, url)
        } catch (e: Exception) {
            android.util.Log.e("YtdlpHelper", "execute failed", e)
            throw ExtractError(friendlyOf(e))
        }

        val raw = response.out.trim()
        val start = raw.indexOf('{')
        if (start < 0) throw ExtractError("Не удалось получить данные по ссылке")
        val json = try {
            JSONObject(raw.substring(start))
        } catch (e: Exception) {
            throw ExtractError("Не удалось разобрать ответ платформы")
        }

        val type = json.optString("_type", "")
        if (type == "playlist" || type == "multi_video") {
            val entries = json.optJSONArray("entries")
            if (entries == null || entries.length() == 0) {
                throw ExtractError("По ссылке не нашлось видео")
            }
            val first = entries.optJSONObject(0)
                ?: throw ExtractError("По ссылке не нашлось видео")
            return buildResult(url, first).toString()
        }

        return buildResult(url, json).toString().also {
            android.util.Log.i("YtdlpHelper", "resolve done, json length=${it.length}")
        }
    }

    // ---------- preview ----------

    fun deleteQuietly(file: File) {
        try {
            file.delete()
            file.parentFile?.let { p ->
                if (p.name.startsWith("preview_") && (p.listFiles()?.isEmpty() != false)) {
                    p.delete()
                }
            }
        } catch (ignored: Exception) {
        }
    }

    fun preparePreview(context: Context, rawUrl: String, onProgress: (Float) -> Unit): File {
        ensureInit(context)
        val url = expandUrl(checkUrl(rawUrl))
        android.util.Log.i("YtdlpHelper", "preview url=$url")

        val dir = File(context.cacheDir, "preview_" + System.currentTimeMillis())
        dir.mkdirs()

        try {
            executeWithFallbackProgress({ route ->
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--socket-timeout", "12")
                request.addOption("--retries", "1")
                request.addOption("--format-sort", "vcodec:h264,acodec:aac")
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--extractor-args", "youtube:player_client=default,mweb,web_safari;formats=missing_pot")
                request.addOption("-f", "bv[height<=480]+ba/b[height<=480]/b")
                request.addOption("--max-filesize", "40M")
                request.addOption("-o", dir.absolutePath + "/%(id)s.%(ext)s")
                request.addOption("--paths", dir.absolutePath)
                route?.let { request.addOption("--proxy", it) }
                request
            }, { progress, _ ->
                onProgress(progress)
            }, url)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw ExtractError(friendlyOf(e))
        }

        val file = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXTS && it.length() > 0 }
            ?.maxByOrNull { it.length() }
        if (file == null) {
            dir.deleteRecursively()
            throw ExtractError(
                "Не удалось получить видео для просмотра. Попробуйте скачать файл напрямую."
            )
        }
        return file
    }

    // ---------- download ----------

    fun download(
        context: Context,
        rawUrl: String,
        quality: String,
        onProgress: (Float, String) -> Unit
    ): String {
        ensureInit(context)
        val url = expandUrl(checkUrl(rawUrl))
        android.util.Log.i("YtdlpHelper", "download url=$url quality=$quality")
        val work = File(context.cacheDir, "dl_" + System.currentTimeMillis())
        work.mkdirs()

        try {
            val resp = executeWithFallbackProgress({ route ->
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--socket-timeout", "12")
                request.addOption("--retries", "1")

                if (quality == "audio") {
                    request.addOption("-f", "ba/b")
                    request.addOption("--extract-audio")
                    request.addOption("--audio-format", "m4a")
                } else {
                    val fmt = if (quality == "best" || quality.isBlank()) {
                        "bv*+ba/b"
                    } else {
                        val h = quality.toIntOrNull() ?: 1080
                        "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b"
                    }
                    request.addOption("-f", fmt)
                    request.addOption("--format-sort", "vcodec:h264,acodec:aac")
                    request.addOption("--merge-output-format", "mp4")
                }

                request.addOption("--extractor-args", "youtube:player_client=default,mweb,web_safari;formats=missing_pot")
                request.addOption("-o", work.absolutePath + "/%(id)s.%(ext)s")
                request.addOption("--paths", work.absolutePath)
                route?.let { request.addOption("--proxy", it) }
                request
            }, { progress, _line ->
                onProgress(progress, _line)
            }, url)
            android.util.Log.i(
                "YtdlpHelper",
                "download ok exit=${resp.exitCode} out='${resp.out.take(400)}' err='${resp.err.take(400)}'"
            )
        } catch (e: Exception) {
            work.deleteRecursively()
            android.util.Log.e("YtdlpHelper", "download failed", e)
            throw ExtractError(friendlyOf(e))
        }

        val files = work.listFiles()
        android.util.Log.i("YtdlpHelper", "workdir=${work.absolutePath} files=${files?.joinToString { "${it.name}:${it.length()}" }}")
        val file = files
            ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXTS }
            ?.maxByOrNull { it.length() }
        if (file == null) {
            work.deleteRecursively()
            throw ExtractError("Файл не найден после скачивания")
        }

        val mime = mimeFor(file.extension.lowercase())
        val savedName = saveToDownloads(context, file, file.name, mime)
        android.util.Log.i("YtdlpHelper", "saved as $savedName ($mime)")
        work.deleteRecursively()
        return savedName
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun saveToDownloads(context: Context, src: File, name: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/VideoDownloader"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values)
                ?: throw ExtractError("Не удалось сохранить файл в «Загрузки»")
            try {
                resolver.openOutputStream(uri).use { out ->
                    src.inputStream().use { input -> input.copyTo(out!!) }
                }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw ExtractError("Не удалось записать файл: " + (e.message ?: ""))
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return name
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "VideoDownloader"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw ExtractError("Нет доступа к папке «Загрузки»")
        }
        var dest = File(dir, name)
        if (dest.exists()) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 1
            while (dest.exists()) {
                dest = File(dir, "$base ($i)$ext")
                i++
            }
        }
        src.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(
            context, arrayOf(dest.absolutePath), arrayOf(mime), null
        )
        return dest.name
    }
}
