package com.sudhanshu.aiiptv

import android.content.Context
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val name: String, val group: String, val logo: String, val url: String, val tvgId: String = "")

fun parseM3u(text: String): List<Channel> {
    val out = mutableListOf<Channel>()
    var name = ""
    var group = "Other"
    var logo = ""
    var tvg = ""
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF")) {
            group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                ?.ifBlank { "Other" } ?: "Other"
            logo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
            name = line.substringAfterLast(",", "").trim()
            tvg = Regex("tvg-id=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
        } else if (!line.startsWith("#")) {
            out.add(Channel(name.ifBlank { line }, group, logo, line, tvg))
            name = ""; group = "Other"; logo = ""; tvg = ""
        }
    }
    return out
}

fun loadPlaylist(ctx: Context): List<Channel> {
    val f = File(ctx.filesDir, "playlist.m3u")
    return if (f.exists()) parseM3u(f.readText()) else emptyList()
}

fun savePlaylist(ctx: Context, text: String): List<Channel> {
    File(ctx.filesDir, "playlist.m3u").writeText(text)
    return parseM3u(text)
}

suspend fun fetchText(u: String): String = withContext(Dispatchers.IO) {
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 30000
    c.inputStream.bufferedReader().use { it.readText() }
}

fun applyCats(channels: List<Channel>, json: String): List<Channel> {
    if (json.isBlank()) return channels
    val o = try { JSONObject(json) } catch (e: Exception) { return channels }
    return channels.map { if (o.has(it.url)) it.copy(group = o.getString(it.url)) else it }
}

fun importPlaylist(ctx: Context, text: String): List<Channel> {
    val p = ctx.getSharedPreferences("p", 0)
    if (p.getString("epg", "").isNullOrBlank()) {
        Regex("url-tvg=\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.split(",")?.firstOrNull()
            ?.let { p.edit().putString("epg", it.trim()).apply() }
    }
    return applyCats(savePlaylist(ctx, text), p.getString("cats", "") ?: "")
}

private suspend fun gemini(apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
    val body = JSONObject().put(
        "contents", JSONArray().put(
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )
    )
    val conn = URL(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
    ).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.connectTimeout = 15000
    conn.readTimeout = 60000
    conn.outputStream.use { it.write(body.toString().toByteArray()) }
    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
    val resp = stream.bufferedReader().use { it.readText() }
    JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
}

/** Natural-language channel search (e.g. "sirf HD wale"). */
suspend fun aiSearch(apiKey: String, query: String, channels: List<Channel>): List<Channel> {
    val pool = channels.take(400)
    val list = pool.mapIndexed { i, c -> "$i|${c.name}|${c.group}" }.joinToString("\n")
    val text = gemini(
        apiKey,
        "You are a channel finder for a media player. Channels (index|name|group):\n" + list +
            "\n\nUser request: " + query +
            "\nReply ONLY with a JSON array of up to 15 best matching indices, e.g. [3,7]."
    )
    val arr = JSONArray(Regex("\\[.*?]", RegexOption.DOT_MATCHES_ALL).find(text)!!.value)
    return (0 until arr.length()).map { arr.getInt(it) }.filter { it in pool.indices }.map { pool[it] }
}

/** AI se har channel ko short category do. Returns url -> category. */
suspend fun aiCategorize(apiKey: String, channels: List<Channel>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (chunk in channels.chunked(150).take(4)) {
        val list = chunk.mapIndexed { i, c -> "$i|${c.name}" }.joinToString("\n")
        val text = gemini(
            apiKey,
            "Assign each channel a short category (1-2 words, Title Case, at most 8 distinct categories overall) " +
                "based on its name. Channels (index|name):\n" + list +
                "\nReply ONLY with a JSON array of strings, same length and order as the channels."
        )
        val arr = JSONArray(Regex("\\[.*]", RegexOption.DOT_MATCHES_ALL).find(text)!!.value)
        for (i in 0 until minOf(arr.length(), chunk.size)) result[chunk[i].url] = arr.getString(i)
    }
    return result
}

/** "Star Sports HD" / "Star Sports 1080p" -> same key, fallback ke liye. */
fun normName(n: String): String = n.lowercase()
    .replace(Regex("\\b(hd|sd|fhd|uhd|4k|hevc|720p?|1080p?|2160p?)\\b"), " ")
    .replace(Regex("[\\[\\]()|_\\-]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

// ---------- EPG (XMLTV) ----------
data class Prog(val start: Long, val stop: Long, val title: String)

object Epg {
    var map by mutableStateOf<Map<String, List<Prog>>>(emptyMap())

    fun nowNext(id: String): Pair<Prog?, Prog?> {
        if (id.isEmpty()) return Pair(null, null)
        val l = map[id] ?: return Pair(null, null)
        val t = System.currentTimeMillis()
        val i = l.indexOfFirst { t >= it.start && t < it.stop }
        return if (i < 0) Pair(null, null) else Pair(l[i], l.getOrNull(i + 1))
    }
}

private fun parseXmltvTime(raw: String?): Long {
    if (raw == null) return 0L
    var s = raw.trim()
    if (s.length == 14) s += " +0000"
    return try { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(s)?.time ?: 0L } catch (e: Exception) { 0L }
}

fun parseXmltv(stream: InputStream): Map<String, List<Prog>> {
    val p = Xml.newPullParser()
    p.setInput(stream, null)
    val out = HashMap<String, MutableList<Prog>>()
    val now = System.currentTimeMillis()
    val limit = now + 24 * 3600_000L
    var ch = ""
    var st = 0L
    var en = 0L
    var inTitle = false
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> {
                if (p.name == "programme") {
                    ch = p.getAttributeValue(null, "channel") ?: ""
                    st = parseXmltvTime(p.getAttributeValue(null, "start"))
                    en = parseXmltvTime(p.getAttributeValue(null, "stop"))
                } else if (p.name == "title") inTitle = true
            }
            XmlPullParser.TEXT -> {
                if (inTitle && ch.isNotEmpty() && en > now && st < limit) {
                    out.getOrPut(ch) { mutableListOf() }.add(Prog(st, en, p.text.trim()))
                    ch = ""
                }
            }
            XmlPullParser.END_TAG -> {
                if (p.name == "title") inTitle = false
                else if (p.name == "programme") ch = ""
            }
        }
        ev = p.next()
    }
    return out.mapValues { e -> e.value.sortedBy { it.start } }
}

fun epgStale(ctx: Context): Boolean {
    val f = File(ctx.filesDir, "epg.xml")
    return !f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * 3600_000L
}

suspend fun loadEpgCache(ctx: Context) = withContext(Dispatchers.IO) {
    val f = File(ctx.filesDir, "epg.xml")
    if (f.exists()) Epg.map = f.inputStream().use { parseXmltv(it) }
}

suspend fun refreshEpg(ctx: Context, url: String) = withContext(Dispatchers.IO) {
    val f = File(ctx.filesDir, "epg.xml")
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 60000
    var ins: InputStream = c.inputStream
    if (url.endsWith(".gz")) ins = GZIPInputStream(ins)
    ins.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    Epg.map = f.inputStream().use { parseXmltv(it) }
}
