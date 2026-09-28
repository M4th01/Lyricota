package com.example.lrcfetcher

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lrcfetcher.library.LibraryCache
import com.example.lrcfetcher.library.LibraryScanner
import com.example.lrcfetcher.library.LyricsWriter
import com.example.lrcfetcher.library.Identity
import com.example.lrcfetcher.library.IdentityResolver
import com.example.lrcfetcher.library.Track
import kotlinx.coroutines.coroutineScope
import com.example.lrcfetcher.metadata.CoverOption
import com.example.lrcfetcher.metadata.MetadataRepository
import com.example.lrcfetcher.metadata.MetadataResult
import com.example.lrcfetcher.tags.Cover
import com.example.lrcfetcher.tags.TagChanges
import com.example.lrcfetcher.tags.TrackTags
import com.example.lrcfetcher.lyrics.LrcWriter
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.LyricsRepository
import com.example.lrcfetcher.lyrics.Matching
import com.example.lrcfetcher.lyrics.OutputOptions
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.ProviderResult
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TextMode
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.romanization.JapaneseRomanizer
import com.example.lrcfetcher.romanization.Romanizer
import com.example.lrcfetcher.update.UpdateChecker
import com.example.lrcfetcher.update.UpdateInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mensaje traducible: se resuelve en la interfaz con el idioma actual. */
class UiText(@androidx.annotation.StringRes val id: Int, vararg val args: Any)

data class ScanState(
    val running: Boolean = false,
    /** Comprobando el orden título/artista de archivos sin etiquetas. */
    val identifying: Boolean = false,
    /** false: listando la carpeta; true: leyendo etiquetas ([done]/[total]). */
    val readingTags: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
)

enum class BatchKind { LYRICS, METADATA }

/** Alcance del lote de metadatos. */
enum class MetaScope { SELECTED, ALL, MISSING }

data class BatchState(
    val kind: BatchKind = BatchKind.LYRICS,
    /** Canciones sin coincidencia segura (sólo metadatos). */
    val review: Int = 0,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val found: Int = 0,
    val notFound: Int = 0,
    val failed: Int = 0,
    val current: String = "",
)

sealed interface Screen {
    data object Library : Screen
    data class LyricsView(val session: LyricsSession) : Screen
    data class MetadataView(val session: MetadataSession) : Screen
}

/** Estado de la pantalla de letra de una canción. */
class LyricsSession(val track: Track?, query: TrackQuery, val initial: SongCandidate?) {
    var query by mutableStateOf(query)
    val results = mutableStateMapOf<ProviderId, ProviderResult>()
    var selected by mutableStateOf<ProviderId?>(null)
    /** Última fuente de búsqueda elegida (para volver a ella desde "En la canción"). */
    var lastSource by mutableStateOf<ProviderId?>(null)
    var userPicked by mutableStateOf(false)
    var offsetMs by mutableStateOf(0L)
    var showRaw by mutableStateOf(false)
    var romanizing by mutableStateOf(false)
    var saving by mutableStateOf(false)
    val romanized = mutableStateMapOf<String, Lyrics>()
    var job: Job? = null

    val current: ProviderResult.Found? get() = selected?.let { results[it] as? ProviderResult.Found }
}

/** Campos editables de la pantalla de metadatos (texto; las listas separadas por ";"). */
data class MetaFields(
    val title: String = "",
    val album: String = "",
    val artists: String = "",
    val albumArtist: String = "",
    val composers: String = "",
    val genres: String = "",
    val year: String = "",
    val track: String = "",
    val trackTotal: String = "",
    val disc: String = "",
    val discTotal: String = "",
) {
    fun toTags() = TrackTags(
        title = title, album = album, artists = TrackTags.splitList(artists), albumArtist = albumArtist,
        composers = TrackTags.splitList(composers), genres = TrackTags.splitList(genres), year = year,
        trackNumber = track.trim().toIntOrNull(), trackTotal = trackTotal.trim().toIntOrNull(),
        discNumber = disc.trim().toIntOrNull(), discTotal = discTotal.trim().toIntOrNull(),
    ).normalized()

    companion object {
        fun from(t: TrackTags) = MetaFields(
            title = t.title.orEmpty(), album = t.album.orEmpty(), artists = t.artists.joinToString("; "),
            albumArtist = t.albumArtist.orEmpty(), composers = t.composers.joinToString("; "),
            genres = t.genres.joinToString("; "), year = t.year.orEmpty(),
            track = t.trackNumber?.toString().orEmpty(), trackTotal = t.trackTotal?.toString().orEmpty(),
            disc = t.discNumber?.toString().orEmpty(), discTotal = t.discTotal?.toString().orEmpty(),
        )
    }
}

/** Estado de la pantalla "Metadatos" de una canción. */
class MetadataSession(track: Track) {
    var track by mutableStateOf(track)
    var fields by mutableStateOf(MetaFields.from(track.tags.let { t ->
        // Si el archivo no tiene título/artista, se proponen los deducidos del nombre.
        t.copy(title = t.title ?: track.title, artists = t.artists.ifEmpty { listOfNotNull(track.artist) })
    }))
    val original: TrackTags = track.tags
    var result by mutableStateOf<MetadataResult?>(null)
    var searching by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var currentCover by mutableStateOf<ByteArray?>(null)
    var newCover by mutableStateOf<Cover?>(null)
    var coverLoading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var job: Job? = null

    val changed: Boolean get() = fields.toTags() != original.normalized() || newCover != null
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val cache = LibraryCache(app)
    private val scanner = LibraryScanner(app)
    private val writer = LyricsWriter(app)

    // ---- ajustes observables
    var theme by mutableStateOf(settings.theme)
        private set
    var format by mutableStateOf(settings.format)
        private set
    var textMode by mutableStateOf(settings.textMode)
        private set
    var includeTranslation by mutableStateOf(settings.includeTranslation)
        private set
    var saveTarget by mutableStateOf(settings.saveTarget)
        private set
    var includeVoices by mutableStateOf(settings.includeVoices)
        private set
    var millis by mutableStateOf(settings.millis)
        private set
    val language: AppLanguage get() = settings.language
    var providerOrder by mutableStateOf(settings.providerOrder)
        private set
    var disabledProviders by mutableStateOf(settings.disabledProviders)
        private set

    // ---- biblioteca
    var folderUri by mutableStateOf<Uri?>(null)
        internal set // internal: capturas de pantalla en tests
    var tracks by mutableStateOf<List<Track>>(emptyList())
        internal set // internal: capturas de pantalla en tests
    var scan by mutableStateOf(ScanState())
        private set
    var filter by mutableStateOf(settings.filter)
        private set
    var sort by mutableStateOf(settings.sort)
        private set
    var query by mutableStateOf("")
    var section by mutableStateOf(Section.LYRICS)
        private set
    val onlineMode: Boolean get() = section == Section.ONLINE
    var metaFilter by mutableStateOf(settings.metaFilter)
        private set

    fun changeSection(s: Section) {
        section = s
        if (s == Section.ONLINE) searchOnline()
    }

    fun updateMetaFilter(f: MetaFilter) { metaFilter = f; settings.metaFilter = f }

    // ---- búsqueda en línea
    var onlineResults by mutableStateOf<List<SongCandidate>>(emptyList())
        private set
    var onlineLoading by mutableStateOf(false)
        private set
    var onlineError by mutableStateOf<UiText?>(null)
        private set
    private var onlineJob: Job? = null

    // ---- navegación, lote, mensajes
    var screen by mutableStateOf<Screen>(Screen.Library)
        private set
    private var batchState by mutableStateOf(BatchState())
    private val notifier = BatchNotifier(app)
    var batch: BatchState
        get() = batchState
        private set(v) {
            val old = batchState
            batchState = v
            notifier.onBatch(old, v)
        }
    private var batchJob: Job? = null
    private var scanJob: Job? = null
    var message by mutableStateOf<UiText?>(null)

    // ---- selección múltiple en la biblioteca
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    val selectionMode: Boolean get() = selected.isNotEmpty()

    fun toggleSelection(track: Track) {
        selected = if (track.uri in selected) selected - track.uri else selected + track.uri
    }

    fun selectAll(visible: List<Track>) {
        selected = if (visible.all { it.uri in selected }) emptySet() else selected + visible.map { it.uri }
    }

    fun clearSelection() { selected = emptySet() }

    val reviewCount: Int get() = tracks.count { it.needsReview }

    val enabledProviders: List<ProviderId> get() = providerOrder.filter { it !in disabledProviders }

    init {
        BatchNotifier.onCancel = { cancelBatch() }
        com.example.lrcfetcher.lyrics.providers.AmllProvider.cacheDir = app.cacheDir
        // Cargar el diccionario japonés en segundo plano: la primera romanización es instantánea.
        viewModelScope.launch(Dispatchers.Default) { runCatching { JapaneseRomanizer.warmUp() } }
        settings.folderUri?.let { saved ->
            val uri = Uri.parse(saved)
            val permitted = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (permitted) openFolder(uri, fromUser = false) else settings.folderUri = null
        }
    }

    // ================================================================= biblioteca

    fun onFolderPicked(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags) }
        settings.folderUri = uri.toString()
        openFolder(uri, fromUser = true)
    }

    private fun openFolder(uri: Uri, fromUser: Boolean) {
        folderUri = uri
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { cache.load(uri) }
            if (cached.isNotEmpty()) tracks = cached.values.toList()
            refresh(full = false, announce = fromUser)
        }
    }

    /**
     * Escaneo incremental: lista la carpeta (rápido) y sólo lee etiquetas de lo nuevo o
     * modificado. Con [full] se ignora la caché y se relee todo.
     */
    fun rescan(full: Boolean = false) {
        if (folderUri == null) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch { refresh(full, announce = true) }
    }

    private suspend fun refresh(full: Boolean, announce: Boolean) {
        val uri = folderUri ?: return
        scan = ScanState(running = true)
        try {
            val cached = if (full) emptyMap() else withContext(Dispatchers.IO) { cache.load(uri) }
            val listing = scanner.list(uri, settings.skipNoMedia)
            val (ready, pending) = withContext(Dispatchers.Default) { scanner.diff(uri, listing, cached) }
            val present = HashSet<String>(ready.size + pending.size).apply { ready.forEach { add(it.uri) }; pending.forEach { add(it.uri) } }
            val removed = cached.keys.count { it !in present }
            tracks = ready + pending

            if (pending.isNotEmpty()) {
                scan = scan.copy(readingTags = true, done = 0, total = pending.size)
                val loaded = ConcurrentHashMap<String, Track>()
                scanner.readAll(pending) { done, batchResult ->
                    batchResult.forEach { loaded[it.uri] = it }
                    viewModelScope.launch(Dispatchers.Main) {
                        scan = scan.copy(done = done)
                        tracks = tracks.map { loaded[it.uri] ?: it }
                    }
                }
                tracks = tracks.map { loaded[it.uri] ?: it }
            }
            resolveIdentities()
            withContext(Dispatchers.IO) { cache.save(uri, tracks) }
            val added = if (cached.isNotEmpty()) pending.size else 0
            scan = ScanState()
            if (announce && (added > 0 || removed > 0)) message = UiText(R.string.msg_library_updated, added, removed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            scan = ScanState()
            message = UiText(R.string.msg_folder_error, e.message.orEmpty())
        }
    }

    /**
     * Título/artista correctos cuando el archivo no los trae (o los trae intercambiados):
     *  - Nombre "A - B" sin etiquetas: decide el orden con los artistas de la biblioteca y, si
     *    no basta, preguntando a Deezer/iTunes (sólo una vez por archivo).
     *  - Etiquetas intercambiadas: se muestran corregidas y la canción queda "por aprobar"
     *    para que el usuario arregle el archivo desde Metadatos.
     */
    private suspend fun resolveIdentities() {
        val index = withContext(Dispatchers.Default) { IdentityResolver.ArtistIndex(tracks) }
        val updates = HashMap<String, Track>()
        for (t in tracks) {
            if (t.identity == Identity.TAGS) {
                val swapped = IdentityResolver.looksSwapped(t, index)
                if (swapped != t.swapSuspected) {
                    updates[t.uri] = if (swapped) {
                        t.copy(title = t.tags.artist ?: t.title, artist = t.tags.title, swapSuspected = true, needsReview = true)
                    } else {
                        t.copy(title = t.tags.title ?: t.title, artist = t.tags.artist, swapSuspected = false)
                    }
                }
            } else if (t.identity == Identity.GUESSED) {
                IdentityResolver.resolveOffline(t, index)?.let { (title, artist) ->
                    updates[t.uri] = t.copy(title = title, artist = artist, identity = Identity.VERIFIED)
                }
            }
        }
        if (updates.isNotEmpty()) tracks = tracks.map { updates[it.uri] ?: it }

        val online = tracks.filter { it.identity == Identity.GUESSED }
        if (online.isEmpty()) return
        scan = scan.copy(identifying = true, readingTags = false, done = 0, total = online.size)
        val resolved = ConcurrentHashMap<String, Track>()
        val done = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            online.forEach { t ->
                launch(Dispatchers.IO) {
                    gate.acquire()
                    try {
                        val r = runCatching { IdentityResolver.resolveOnline(t.fileName) }.getOrNull()
                        resolved[t.uri] = if (r != null) t.copy(title = r.first, artist = r.second, identity = Identity.VERIFIED)
                        else t.copy(identity = Identity.CHECKED)
                    } finally {
                        gate.release()
                    }
                    val n = done.incrementAndGet()
                    withContext(Dispatchers.Main) { scan = scan.copy(done = n) }
                }
            }
        }
        tracks = tracks.map { resolved[it.uri] ?: it }
    }

    fun updateFilter(f: LibraryFilter) { filter = f; settings.filter = f }
    fun updateSort(s: SortMode) { sort = s; settings.sort = s }

    // Texto normalizado de cada pista para filtrar rápido al escribir (se recalcula si cambia la lista).
    private var searchKeys: Pair<List<Track>, Map<String, String>> = emptyList<Track>() to emptyMap()

    private fun searchKey(t: Track): String {
        if (searchKeys.first !== tracks) {
            searchKeys = tracks to tracks.associate { it.uri to Matching.normalize("${it.title} ${it.artist.orEmpty()} ${it.album.orEmpty()} ${it.fileName}") }
        }
        return searchKeys.second[t.uri].orEmpty()
    }

    fun visibleTracks(): List<Track> {
        val q = Matching.normalize(query)
        val base = tracks.asSequence()
            .filter {
                if (section == Section.METADATA) {
                    when (metaFilter) {
                        MetaFilter.ALL -> true
                        MetaFilter.INCOMPLETE -> it.metadataIncomplete || it.identity != com.example.lrcfetcher.library.Identity.TAGS
                        MetaFilter.REVIEW -> it.needsReview
                        MetaFilter.NO_COVER -> it.metadataLoaded && !it.hasCover
                    }
                } else {
                    when (filter) {
                        LibraryFilter.ALL -> true
                        LibraryFilter.MISSING -> !it.hasLyrics
                        LibraryFilter.HAS -> it.hasLyrics
                    }
                }
            }
            .filter { q.isEmpty() || searchKey(it).contains(q) }
        return when (sort) {
            SortMode.TITLE -> base.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> base.sortedWith(compareBy({ it.artist?.lowercase() ?: "￿" }, { it.album?.lowercase() }, { it.title.lowercase() }))
            SortMode.RECENT -> base.sortedByDescending { it.lastModified }
        }.toList()
    }

    // ================================================================= búsqueda en línea

    fun onQueryChange(value: String) {
        query = value
        if (onlineMode) searchOnline()
    }

    private fun searchOnline() {
        onlineJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            onlineResults = emptyList(); onlineLoading = false; onlineError = null
            return
        }
        onlineJob = viewModelScope.launch {
            delay(450)
            onlineLoading = true
            onlineError = null
            val providers = enabledProviders
            val lists = providers.map { id ->
                async(Dispatchers.IO) { runCatching { LyricsProvider.of(id).search(q).take(8) }.getOrNull() }
            }.awaitAll()
            val all = lists.filterNotNull().flatten()
            onlineResults = all.sortedByDescending { Matching.similarity(q, "${it.artist} ${it.title}").coerceAtLeast(Matching.similarity(q, it.title)) }
            if (all.isEmpty()) onlineError = if (lists.all { it == null }) UiText(R.string.online_offline) else null
            onlineLoading = false
        }
    }

    // ================================================================= pantalla de letra

    fun openTrack(track: Track) = openSession(LyricsSession(track, track.toQuery(), null))

    fun openCandidate(c: SongCandidate) =
        openSession(LyricsSession(null, TrackQuery(c.title, c.artist, c.album, c.durationMs), c))

    fun closeLyrics() {
        (screen as? Screen.LyricsView)?.session?.job?.cancel()
        screen = Screen.Library
    }

    private fun openSession(session: LyricsSession) {
        screen = Screen.LyricsView(session)
        search(session)
    }

    fun editQuery(session: LyricsSession, title: String, artist: String) {
        session.query = session.query.copy(title = title.trim(), artist = artist.trim().ifBlank { null })
        session.results.clear()
        session.selected = null
        session.userPicked = false
        search(session, ignoreInitial = true)
    }

    private fun search(session: LyricsSession, ignoreInitial: Boolean = false) {
        session.job?.cancel()
        val providers = enabledProviders
        providers.forEach { session.results[it] = ProviderResult.Loading }
        session.job = viewModelScope.launch {
            // La letra que ya tiene la canción se muestra aunque ninguna fuente la encuentre.
            val track = session.track
            val tree = folderUri
            if (track != null && tree != null && track.hasLyrics && !session.results.containsKey(ProviderId.LOCAL)) {
                val local = withContext(Dispatchers.IO) { runCatching { loadLocalLyrics(tree, track) }.getOrNull() }
                if (local != null) {
                    session.results[ProviderId.LOCAL] = local
                    if (!session.userPicked) session.selected = ProviderId.LOCAL
                    ensureRomanized(session)
                }
            }
            val initial = session.initial.takeUnless { ignoreInitial }
            if (initial != null) {
                val lyrics = runCatching { LyricsRepository.fetch(initial) }.getOrNull()
                session.results[initial.provider] =
                    if (lyrics != null) ProviderResult.Found(initial, lyrics, 1.0) else ProviderResult.NotFound
                if (lyrics != null) {
                    session.selected = initial.provider
                    session.userPicked = true
                    ensureRomanized(session)
                }
            }
            val others = providers.filter { initial == null || it != initial.provider }
            LyricsRepository.findAll(session.query, others) { id, r ->
                viewModelScope.launch(Dispatchers.Main) {
                    session.results[id] = r
                    if (!session.userPicked) session.selected = autoPick(session) ?: session.selected
                    ensureRomanized(session)
                }
            }
        }
    }

    /**
     * Qué mostrar sin que el usuario elija: la mejor fuente sólo si está mejor sincronizada que
     * la letra que ya tiene la canción; si no, la de la canción.
     */
    private fun autoPick(session: LyricsSession): ProviderId? {
        val sources = session.results.filterKeys { it.searchable }
        val best = LyricsRepository.best(sources, providerOrder)
        val local = session.results[ProviderId.LOCAL] as? ProviderResult.Found
        return when {
            local == null -> best?.lyrics?.source
            best != null && best.lyrics.sync.rank > local.lyrics.sync.rank -> best.lyrics.source
            else -> ProviderId.LOCAL
        }
    }

    /** La letra de la canción, leída y convertida. Bloqueante. */
    private fun loadLocalLyrics(tree: Uri, track: Track): ProviderResult.Found? {
        val text = writer.readLyrics(tree, track) ?: return null
        val parsed = com.example.lrcfetcher.lyrics.LyricsParsers.parseLrc(text)
        val lyrics = Lyrics(parsed.lines, parsed.sync, ProviderId.LOCAL, track.uri).withRealSync()
        if (lyrics.isEmpty) return null
        val candidate = SongCandidate(ProviderId.LOCAL, track.uri, track.title, track.artist.orEmpty(), track.album, track.durationMs)
        return ProviderResult.Found(candidate, lyrics, 1.0)
    }

    /** Cambia entre la letra de la canción y la mejor encontrada por las fuentes. */
    fun showLocal(session: LyricsSession, local: Boolean) {
        val target = if (local) ProviderId.LOCAL
        else session.lastSource?.takeIf { session.results[it] is ProviderResult.Found }
            ?: LyricsRepository.best(session.results.filterKeys { it.searchable }, providerOrder)?.lyrics?.source
        session.selected = target
        session.userPicked = true
        ensureRomanized(session)
    }

    fun selectProvider(session: LyricsSession, id: ProviderId) {
        if (session.results[id] !is ProviderResult.Found) return
        session.selected = id
        if (id.searchable) session.lastSource = id
        session.userPicked = true
        ensureRomanized(session)
    }

    /** Letra a mostrar/guardar según las opciones actuales (romanizada si hace falta). */
    fun displayLyrics(session: LyricsSession): Lyrics? {
        val found = session.current ?: return null
        if (textMode == TextMode.ORIGINAL) return found.lyrics
        return session.romanized[found.candidate.key] ?: found.lyrics
    }

    fun outputOptions(session: LyricsSession) =
        OutputOptions(format, textMode, includeTranslation, session.offsetMs, includeVoices, millis)

    fun lrcText(session: LyricsSession): String? = displayLyrics(session)?.let { LrcWriter.write(it, outputOptions(session)) }

    private fun ensureRomanized(session: LyricsSession) {
        val found = session.current ?: return
        if (textMode == TextMode.ORIGINAL || session.romanized.containsKey(found.candidate.key)) return
        if (!Romanizer.needsRomanization(found.lyrics)) return
        session.romanizing = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { Romanizer.romanize(found.lyrics) }.getOrNull() }
            if (r != null) session.romanized[found.candidate.key] = r
            session.romanizing = false
        }
    }

    fun setTextModeFor(session: LyricsSession?, mode: TextMode) {
        textMode = mode; settings.textMode = mode
        session?.let { ensureRomanized(it) }
    }

    fun updateFormat(f: SyncType) { format = f; settings.format = f }
    fun setTranslation(v: Boolean) { includeTranslation = v; settings.includeTranslation = v }
    fun updateTheme(t: ThemeMode) { theme = t; settings.theme = t }
    fun updateSaveTarget(t: SaveTarget) { saveTarget = t; settings.saveTarget = t }
    fun updateVoices(v: Boolean) { includeVoices = v; settings.includeVoices = v }
    fun updateMillis(v: Boolean) { millis = v; settings.millis = v }

    fun setProviderEnabled(id: ProviderId, enabled: Boolean) {
        disabledProviders = if (enabled) disabledProviders - id else disabledProviders + id
        settings.disabledProviders = disabledProviders
    }

    fun moveProvider(id: ProviderId, delta: Int) {
        val list = providerOrder.toMutableList()
        val i = list.indexOf(id)
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.removeAt(i); list.add(j, id)
        providerOrder = list
        settings.providerOrder = list
    }

    /** Guarda la letra actual en la canción local según el destino elegido en ajustes. */
    fun saveToTrack(session: LyricsSession) {
        val track = session.track ?: return
        val tree = folderUri ?: return
        val text = lrcText(session) ?: return
        session.saving = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { saveLyrics(tree, track, text, saveTarget) } }
            session.saving = false
            result.onSuccess { updated ->
                replaceTrack(updated)
                // "En la canción" pasa a ser lo que se acaba de guardar.
                withContext(Dispatchers.IO) { runCatching { loadLocalLyrics(tree, updated) }.getOrNull() }
                    ?.let { session.results[ProviderId.LOCAL] = it; session.romanized.remove(it.candidate.key) }
                message = UiText(
                    when (saveTarget) {
                        SaveTarget.EMBED -> R.string.msg_saved_embed
                        SaveTarget.LRC -> R.string.msg_saved_lrc
                        SaveTarget.BOTH -> R.string.msg_saved_both
                    },
                )
            }.onFailure { message = UiText(R.string.msg_save_error, it.message.orEmpty()) }
        }
    }

    /** Devuelve la pista actualizada (marcas de letra). Bloqueante. */
    private fun saveLyrics(tree: Uri, track: Track, text: String, target: SaveTarget): Track {
        var t = track
        val canEmbed = track.ext in LyricsWriter.EMBEDDABLE
        if (target == SaveTarget.EMBED || target == SaveTarget.BOTH) {
            if (canEmbed) {
                writer.embed(track, text)
                t = t.copy(hasEmbeddedLyrics = true)
            } else if (target == SaveTarget.EMBED) {
                // Formato sin etiqueta de letra: se guarda como .lrc para no perderla.
                t = t.copy(lrcDocId = writer.writeSidecar(tree, track, text))
            }
        }
        if (target == SaveTarget.LRC || target == SaveTarget.BOTH) {
            t = t.copy(lrcDocId = writer.writeSidecar(tree, track, text))
        }
        // El archivo cambió de tamaño/fecha: se relee en el próximo escaneo, pero las marcas quedan.
        return t
    }

    private fun replaceTrack(updated: Track) {
        tracks = tracks.map { if (it.uri == updated.uri) updated else it }
        val tree = folderUri ?: return
        viewModelScope.launch(Dispatchers.IO) { cache.save(tree, tracks) }
    }

    /** Exporta a un .lrc elegido por el usuario (para letras de la búsqueda en línea). */
    fun exportTo(uri: Uri, session: LyricsSession) {
        val text = lrcText(session) ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            message = UiText(if (ok) R.string.msg_exported else R.string.msg_export_error)
        }
    }

    // ================================================================= lote

    /** Busca y guarda letras para muchas canciones a la vez (por defecto, las que no tienen). */
    fun startBatch(onlyMissing: Boolean, onlySelected: Boolean = false) {
        val tree = folderUri ?: return
        if (batch.running) return
        val pool = if (onlySelected) tracks.filter { it.uri in selected } else tracks
        val targets = pool.filter { !onlyMissing || !it.hasLyrics }
        if (onlySelected) clearSelection()
        if (targets.isEmpty()) {
            message = UiText(R.string.msg_no_pending)
            return
        }
        val target = saveTarget
        val opts = OutputOptions(format, textMode, includeTranslation, 0, includeVoices, millis)
        val order = enabledProviders
        batch = BatchState(running = true, total = targets.size)
        batchJob = viewModelScope.launch {
            var found = 0; var notFound = 0; var failed = 0
            for ((i, track) in targets.withIndex()) {
                if (!isActive) break
                batch = batch.copy(current = track.title, done = i)
                val best = runCatching { LyricsRepository.findBestQuick(track.toQuery(), order) }.getOrNull()
                if (best == null) {
                    notFound++
                } else {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val lyrics = if (opts.textMode != TextMode.ORIGINAL) Romanizer.romanize(best.lyrics) else best.lyrics
                            saveLyrics(tree, track, LrcWriter.write(lyrics, opts), target)
                        }
                    }
                    result.onSuccess { found++; replaceTrackQuiet(it) }.onFailure { failed++ }
                }
                batch = batch.copy(done = i + 1, found = found, notFound = notFound, failed = failed)
                if ((i + 1) % 10 == 0) withContext(Dispatchers.IO) { cache.save(tree, tracks) }
            }
            withContext(Dispatchers.IO) { cache.save(tree, tracks) }
            batch = batch.copy(running = false, current = "")
            message = if (failed > 0) UiText(R.string.msg_batch_done_errors, found, notFound, failed)
            else UiText(R.string.msg_batch_done, found, notFound)
        }
    }

    private fun replaceTrackQuiet(updated: Track) {
        tracks = tracks.map { if (it.uri == updated.uri) updated else it }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        batch = batch.copy(running = false, current = "")
        folderUri?.let { tree -> viewModelScope.launch(Dispatchers.IO) { cache.save(tree, tracks) } }
    }

    fun dismissBatchSummary() {
        if (!batch.running) batch = BatchState()
    }

    // ================================================================= metadatos

    fun openMetadata(track: Track) {
        val session = MetadataSession(track)
        screen = Screen.MetadataView(session)
        searchMetadata(session)
        session.coverLoading = true
        viewModelScope.launch {
            session.currentCover = withContext(Dispatchers.IO) { writer.readCover(track.androidUri, track.ext)?.data }
            session.coverLoading = false
        }
    }

    fun closeMetadata() {
        (screen as? Screen.MetadataView)?.session?.job?.cancel()
        screen = Screen.Library
    }

    /** Busca con el título y artista actuales de los campos (el usuario puede corregirlos). */
    fun searchMetadata(session: MetadataSession) {
        session.job?.cancel()
        val t = session.fields.toTags()
        val query = TrackQuery(t.title ?: session.track.title, t.artist ?: session.track.artist, t.album, session.track.durationMs)
        session.searching = true
        session.job = viewModelScope.launch {
            session.result = runCatching { MetadataRepository.search(query) }.getOrNull()
            session.searching = false
            session.searched = true
        }
    }

    /** Aplica los datos de un resultado a los campos (lo que el resultado no trae se conserva). */
    fun applyMetadata(session: MetadataSession, tags: TrackTags) {
        session.fields = MetaFields.from(tags.fillFrom(session.fields.toTags()))
    }

    fun chooseCover(session: MetadataSession, option: CoverOption) {
        session.coverLoading = true
        viewModelScope.launch {
            val bytes = MetadataRepository.downloadImage(option.url)
            session.coverLoading = false
            if (bytes == null || bytes.size < 1000) {
                message = UiText(R.string.msg_cover_error)
            } else {
                session.newCover = Cover(bytes, Cover.detectMime(bytes))
            }
        }
    }

    fun saveMetadata(session: MetadataSession) {
        val track = session.track
        if (track.ext !in LyricsWriter.EMBEDDABLE) {
            message = UiText(R.string.msg_meta_unsupported, track.ext.uppercase())
            return
        }
        session.saving = true
        val changes = TagChanges(tags = session.fields.toTags(), cover = session.newCover)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    writer.apply(track, changes)
                    rereadTrack(track).copy(needsReview = false)
                }
            }
            session.saving = false
            result.onSuccess { updated ->
                replaceTrack(updated)
                session.track = updated
                session.newCover?.let { session.currentCover = it.data }
                session.newCover = null
                message = UiText(R.string.msg_meta_saved)
                closeMetadata()
            }.onFailure { message = UiText(R.string.msg_save_error, it.message.orEmpty()) }
        }
    }

    /** El usuario descarta la revisión pendiente sin cambiar nada. */
    fun dismissReview(track: Track) {
        replaceTrack(track.copy(needsReview = false))
        closeMetadata()
    }

    /** Rechaza los cambios pendientes de todas las canciones: quedan como estaban (no se escribe nada). */
    fun dismissAllReviews() {
        val count = reviewCount
        if (count == 0) return
        tracks = tracks.map { if (it.needsReview) it.copy(needsReview = false) else it }
        folderUri?.let { tree -> viewModelScope.launch(Dispatchers.IO) { cache.save(tree, tracks) } }
        if (metaFilter == MetaFilter.REVIEW) updateMetaFilter(MetaFilter.ALL)
        message = UiText(R.string.msg_review_dismissed, count)
    }

    /** Relee un archivo recién escrito (tamaño, fecha y etiquetas nuevas). Bloqueante. */
    private fun rereadTrack(track: Track): Track {
        val tree = folderUri
        val meta = scanner.readMetadata(track)
        val stat = tree?.let { runCatching { scanner.stat(it, track.docId) }.getOrNull() }
        return if (stat != null) meta.copy(size = stat.first, lastModified = stat.second) else meta
    }

    /**
     * Completa metadatos de muchas canciones. Sólo se aceptan los cambios si el título y el
     * artista del resultado coinciden con los de la canción (y la duración, si se conoce);
     * si no, la canción queda marcada para que el usuario la revise y acepte a mano.
     */
    fun startMetadataBatch(scope: MetaScope, overwrite: Boolean) {
        val tree = folderUri ?: return
        if (batch.running) return
        val targets = when (scope) {
            MetaScope.SELECTED -> tracks.filter { it.uri in selected }
            MetaScope.ALL -> tracks
            MetaScope.MISSING -> tracks.filter { it.metadataIncomplete }
        }.filter { it.ext in LyricsWriter.EMBEDDABLE }
        clearSelection()
        if (targets.isEmpty()) {
            message = UiText(R.string.msg_no_pending)
            return
        }
        batch = BatchState(kind = BatchKind.METADATA, running = true, total = targets.size)
        batchJob = viewModelScope.launch {
            var updated = 0; var review = 0; var failed = 0
            for ((i, track) in targets.withIndex()) {
                if (!isActive) break
                batch = batch.copy(current = track.title, done = i)
                val query = TrackQuery(track.tags.title ?: track.title, track.tags.artist ?: track.artist, track.album, track.durationMs)
                val result = runCatching { MetadataRepository.search(query) }.getOrNull()
                val merged = result?.merged
                if (result == null || !result.strictMatch || merged == null) {
                    review++
                    replaceTrackQuiet(track.copy(needsReview = true))
                } else {
                    // Etiquetas al revés: se corrigen título y artista (coinciden, pero cruzados).
                    val base = if (result.swapped) {
                        track.tags.copy(title = merged.title, artists = merged.artists)
                    } else {
                        track.tags.copy(title = track.tags.title ?: query.title, artists = track.tags.artists.ifEmpty { listOfNotNull(query.artist) })
                    }
                    val newTags = MetadataRepository.batchTags(base, merged, overwrite)
                    if (newTags == track.tags.normalized()) {
                        updated++
                        if (track.needsReview) replaceTrackQuiet(track.copy(needsReview = false))
                    } else {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                writer.apply(track, TagChanges(tags = newTags))
                                rereadTrack(track).copy(needsReview = false)
                            }
                        }
                        r.onSuccess { updated++; replaceTrackQuiet(it) }.onFailure { failed++ }
                    }
                }
                batch = batch.copy(done = i + 1, found = updated, review = review, failed = failed)
                if ((i + 1) % 10 == 0) withContext(Dispatchers.IO) { cache.save(tree, tracks) }
            }
            withContext(Dispatchers.IO) { cache.save(tree, tracks) }
            batch = batch.copy(running = false, current = "")
            message = UiText(R.string.msg_meta_batch_done, updated, review)
        }
    }

    override fun onCleared() {
        // La app se cerró a mitad de un lote: se detiene y se avisa de hasta dónde llegó.
        if (batch.running) notifier.finished(batch.copy(running = false))
        BatchNotifier.onCancel = null
    }

    // ================================================================= actualizaciones
    /** Versión nueva disponible (se muestra el diálogo mientras no sea null). */
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    /** Progreso de la descarga (0..1); null si no se está descargando. */
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    /** El APK ya está descargado y se puede instalar. */
    var updateReady by mutableStateOf(false)
        private set
    var checkingUpdate by mutableStateOf(false)
        private set
    private var updateJob: Job? = null

    private val updatesDir get() = java.io.File(getApplication<Application>().cacheDir, "updates")
    private fun apkFile(info: UpdateInfo) = java.io.File(updatesDir, "Lyricota-${info.version}.apk")

    /**
     * Consulta la última Release de GitHub. Automática: como mucho cada 12 h y respetando
     * "omitir esta versión"; manual: siempre, y avisa también si ya está al día.
     */
    fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate || updateProgress != null) return
        val now = System.currentTimeMillis()
        if (!manual && (!settings.checkUpdates || now - settings.lastUpdateCheck < 12 * 3600_000L)) return
        checkingUpdate = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // APKs de actualizaciones ya instaladas.
                updatesDir.listFiles()?.forEach { f ->
                    val v = f.name.removePrefix("Lyricota-").substringBefore(".apk")
                    if (!UpdateChecker.isNewer(v, BuildConfig.VERSION_NAME)) f.delete()
                }
                runCatching { UpdateChecker.fetchLatest() }
            }
            checkingUpdate = false
            val info = result.getOrNull()
            if (result.isSuccess) settings.lastUpdateCheck = now
            when {
                info != null && UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME) &&
                    (manual || info.version != settings.skippedVersion) -> {
                    update = info
                    updateReady = apkFile(info).exists()
                }
                !manual -> Unit
                result.isFailure -> message = UiText(R.string.msg_update_error)
                else -> message = UiText(R.string.msg_update_latest, BuildConfig.VERSION_NAME)
            }
        }
    }

    /** Descarga el APK de la versión nueva y abre el instalador. */
    fun downloadUpdate() {
        val info = update ?: return
        val url = info.apkUrl ?: return
        if (updateReady) { installUpdate(); return }
        if (updateProgress != null) return
        updateProgress = 0f
        updateJob = viewModelScope.launch {
            val file = apkFile(info)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    updatesDir.mkdirs()
                    var shown = -1
                    UpdateChecker.download(url, file) { p ->
                        if (updateJob?.isActive == false) throw java.io.IOException("cancelled")
                        val pct = (p * 100).toInt()
                        if (pct != shown) { shown = pct; viewModelScope.launch { updateProgress = p } }
                    }
                }
            }
            updateProgress = null
            r.onSuccess { updateReady = true; installUpdate() }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) message = UiText(R.string.msg_update_failed) }
        }
    }

    /** Abre el instalador de Android (el usuario confirma; se conservan ajustes y biblioteca). */
    fun installUpdate() {
        val info = update ?: return
        val app = getApplication<Application>()
        val file = apkFile(info)
        if (!file.exists()) { updateReady = false; return }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            // Android pide permitir "instalar apps desconocidas" a Lyricota una sola vez.
            message = UiText(R.string.msg_update_allow)
            runCatching {
                app.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { message = UiText(R.string.msg_update_failed) }
    }

    fun skipUpdate() {
        update?.let { settings.skippedVersion = it.version }
        dismissUpdate()
    }

    fun dismissUpdate() {
        updateJob?.cancel()
        updateProgress = null
        update = null
    }

    fun updateCheckUpdates(on: Boolean) { settings.checkUpdates = on }

    /** Cambiar si se respetan los ".nomedia" vuelve a listar la carpeta (lo ya leído sigue en caché). */
    fun updateSkipNoMedia(on: Boolean) {
        if (settings.skipNoMedia == on) return
        settings.skipNoMedia = on
        rescan()
    }
}
