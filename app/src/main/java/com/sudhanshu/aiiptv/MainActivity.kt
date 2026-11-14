package com.sudhanshu.aiiptv

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Pip {
    var active by mutableStateOf(false)
    var inPip by mutableStateOf(false)
}

fun enterPip(a: Activity) {
    if (Build.VERSION.SDK_INT >= 26) {
        a.enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Pip.active) enterPip(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.inPip = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Privacy: recents me preview / screenshot band
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("p", 0) }
    val scope = rememberCoroutineScope()

    var adult by remember { mutableStateOf(prefs.getBoolean("adult", false)) }
    var channels by remember { mutableStateOf(applyCats(loadPlaylist(ctx), prefs.getString("cats", "") ?: "")) }
    var favs by remember { mutableStateOf(prefs.getStringSet("favs", emptySet())!!.toSet()) }
    var query by remember { mutableStateOf("") }
    var aiResult by remember { mutableStateOf<List<Channel>?>(null) }
    var group by remember { mutableStateOf("All") }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    SideEffect { Pip.active = playing != null }

    LaunchedEffect(Unit) {
        try {
            loadEpgCache(ctx)
            val u = prefs.getString("epg", "") ?: ""
            if (u.isNotBlank() && epgStale(ctx)) refreshEpg(ctx, u)
        } catch (e: Exception) { }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val t = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                channels = importPlaylist(ctx, t)
                msg = "${channels.size} channels import hue"
                showImport = false
            } catch (e: Exception) { msg = "File error: ${e.message}" }
        }
    }

    if (!adult) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("18+ confirm") },
            text = { Text("Ye app adult content stream kar sakta hai. Kya tumhari umar 18 saal ya usse zyada hai?") },
            confirmButton = {
                TextButton(onClick = { prefs.edit().putBoolean("adult", true).apply(); adult = true }) { Text("Haan, 18+") }
            },
            dismissButton = { TextButton(onClick = { (ctx as Activity).finish() }) { Text("Exit") } }
        )
        return
    }

    val current = playing
    if (current != null) {
        BackHandler { playing = null }
        PlayerScreen(current, channels.filter { normName(it.name) == normName(current.name) }) { playing = null }
    } else {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI IPTV", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(enabled = !busy && channels.isNotEmpty(), onClick = {
                    val key = prefs.getString("key", "") ?: ""
                    if (key.isBlank()) { showKey = true } else {
                        busy = true; msg = "AI categorize ho raha hai..."
                        scope.launch {
                            try {
                                val cats = aiCategorize(key, channels)
                                val old = try { org.json.JSONObject(prefs.getString("cats", "") ?: "") } catch (e: Exception) { org.json.JSONObject() }
                                cats.forEach { (u, c) -> old.put(u, c) }
                                prefs.edit().putString("cats", old.toString()).apply()
                                channels = applyCats(channels, old.toString())
                                group = "All"
                                msg = "${cats.size} channels categorize hue"
                            } catch (e: Exception) { msg = "Categorize error: ${e.message}" }
                            busy = false
                        }
                    }
                }) { Icon(Icons.Default.List, "AI Categorize") }
                IconButton(onClick = { showKey = true }) { Icon(Icons.Default.Settings, "Settings") }
                IconButton(onClick = { showImport = true }) { Icon(Icons.Default.Add, "Import") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; aiResult = null },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search ya AI se poocho...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && query.isNotBlank() && channels.isNotEmpty(), onClick = {
                    val key = prefs.getString("key", "") ?: ""
                    if (key.isBlank()) { showKey = true } else {
                        busy = true; msg = null
                        scope.launch {
                            try {
                                aiResult = aiSearch(key, query, channels)
                                msg = "AI ne ${aiResult?.size ?: 0} match diye"
                            } catch (e: Exception) { msg = "AI error: ${e.message}" }
                            busy = false
                        }
                    }
                }) { Text(if (busy) "..." else "AI") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }

            val groups = listOf("All", "★ Fav") + channels.map { it.group }.distinct()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                items(groups) { g ->
                    FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
                }
            }

            val base = aiResult ?: channels.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            val shown = base.filter {
                when (group) { "All" -> true; "★ Fav" -> it.url in favs; else -> it.group == group }
            }

            if (channels.isEmpty()) {
                Text("Koi playlist nahi hai. Upar + dabake M3U link, text ya file import karo.")
            }
            LazyColumn {
                items(shown) { ch ->
                    Row(
                        Modifier.fillMaxWidth().clickable { playing = ch }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name)
                            Text(Epg.nowNext(ch.tvgId).first?.title ?: ch.group, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {
                            favs = if (ch.url in favs) favs - ch.url else favs + ch.url
                            prefs.edit().putStringSet("favs", favs).apply()
                        }) {
                            Icon(if (ch.url in favs) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Fav")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Playlist import") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("M3U link ya poora M3U text paste karo") },
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                    TextButton(onClick = { picker.launch("*/*") }) { Text("File se chuno") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        busy = true
                        scope.launch {
                            try {
                                val raw = if (t.startsWith("http")) fetchText(t) else t
                                channels = importPlaylist(ctx, raw)
                                msg = "${channels.size} channels import hue"
                                showImport = false
                            } catch (e: Exception) { msg = "Import error: ${e.message}" }
                            busy = false
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } }
        )
    }

    if (showKey) {
        var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
        var epg by remember { mutableStateOf(prefs.getString("epg", "") ?: "") }
        AlertDialog(
            onDismissRequest = { showKey = false },
            title = { Text("Settings") },
            text = {
                Column {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true,
                        label = { Text("Gemini API key (AI ke liye)") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = epg, onValueChange = { epg = it }, singleLine = true,
                        label = { Text("EPG (XMLTV) link - optional") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("key", key.trim()).putString("epg", epg.trim()).apply()
                    showKey = false
                    if (epg.isNotBlank()) scope.launch {
                        try { refreshEpg(ctx, epg.trim()); msg = "EPG load ho gaya" }
                        catch (e: Exception) { msg = "EPG error: ${e.message}" }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showKey = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun CastButton() {
    AndroidView(
        factory = { c ->
            MediaRouteButton(android.view.ContextThemeWrapper(c, androidx.appcompat.R.style.Theme_AppCompat)).also { b ->
                CastButtonFactory.setUpMediaRouteButton(c.applicationContext, b)
            }
        },
        modifier = Modifier.size(48.dp)
    )
}

@Composable
fun PlayerScreen(start: Channel, alts: List<Channel>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember(start) { listOf(start) + alts.filter { it.url != start.url } }
    var idx by remember { mutableStateOf(0) }
    val ch = list[idx]
    var retries by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var useCast by remember { mutableStateOf(false) }

    val exo = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(start.url)); prepare(); playWhenReady = true
        }
    }
    val castCtx = remember { try { CastContext.getSharedInstance(ctx) } catch (e: Exception) { null } }
    val cast = remember(castCtx) { castCtx?.let { CastPlayer(it) } }

    fun castItem(c: Channel) = MediaItem.Builder()
        .setUri(c.url)
        .setMimeType(if (c.url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(c.name).build())
        .build()

    fun load(c: Channel) {
        exo.setMediaItem(MediaItem.fromUri(c.url)); exo.prepare(); exo.playWhenReady = true
        if (useCast) cast?.let { it.setMediaItem(castItem(c)); it.prepare(); it.playWhenReady = true }
    }

    // Auto-fallback: pehle same naam ki dusri quality/link, phir 2 retry
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (idx + 1 < list.size) {
                    idx++; status = "Fallback: ${list[idx].name}"; load(list[idx])
                } else if (retries < 2) {
                    retries++; status = "Retry $retries/2..."
                    scope.launch { delay(2000); exo.prepare() }
                } else status = "Stream chalu nahi hui: ${error.errorCodeName}"
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { status = null; retries = 0 }
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }

    // Chromecast
    DisposableEffect(cast) {
        fun startCast(c: CastPlayer) {
            useCast = true; exo.pause()
            c.setMediaItem(castItem(list[idx])); c.prepare(); c.playWhenReady = true
        }
        cast?.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() { cast.let { startCast(it) } }
            override fun onCastSessionUnavailable() { useCast = false; exo.playWhenReady = true }
        })
        if (cast?.isCastSessionAvailable == true) startCast(cast)
        onDispose { cast?.setSessionAvailabilityListener(null); cast?.release() }
    }

    // Sleep timer
    var sleepMin by remember { mutableStateOf(0) }
    var sleepLeft by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(sleepMin) {
        if (sleepMin > 0) {
            sleepLeft = sleepMin * 60
            while (sleepLeft > 0) { delay(1000); sleepLeft-- }
            exo.pause(); cast?.pause()
            onClose()
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!Pip.inPip) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Back") }
            Text(ch.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (castCtx != null) CastButton()
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(if (sleepLeft > 0) "⏱ ${sleepLeft / 60 + 1}m" else "⏱ Sleep")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(0, 15, 30, 60).forEach { m ->
                        DropdownMenuItem(
                            text = { Text(if (m == 0) "Off" else "$m min") },
                            onClick = { sleepMin = m; if (m == 0) sleepLeft = 0; menu = false }
                        )
                    }
                }
            }
            IconButton(onClick = { enterPip(ctx as Activity) }) { Icon(Icons.Default.Share, "PiP") }
        }
        AndroidView(
            factory = { c -> PlayerView(c).also { v -> v.player = exo } },
            update = { v -> v.player = if (useCast && cast != null) cast else exo },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        if (!Pip.inPip) {
            val (now, next) = Epg.nowNext(ch.tvgId)
            if (now != null) Text("Abhi: ${now.title}", modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            if (next != null) Text("Aage: ${next.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
            status?.let { Text(it, modifier = Modifier.padding(12.dp)) }
        }
    }
}
