package com.example.lrcfetcher.library

import com.example.lrcfetcher.lyrics.Matching
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.metadata.DeezerSource
import com.example.lrcfetcher.metadata.ITunesSource

/** De dónde salen el título y el artista que se muestran. */
enum class Identity {
    /** Etiquetas del archivo. */
    TAGS,
    /** Nombre de archivo, orden aún sin comprobar. */
    GUESSED,
    /** Nombre de archivo, comprobado sin poder decidir: se deja "Artista - Título". */
    CHECKED,
    /** Nombre de archivo, orden confirmado (biblioteca o búsqueda en línea). */
    VERIFIED,
}

/**
 * Decide qué parte del nombre "A - B" es el título y cuál el artista cuando el archivo no
 * tiene etiquetas. Suponer siempre "Artista - Título" falla con "Título - Artista" y con
 * títulos que llevan guion ("Undone - The Sweater Song").
 */
object IdentityResolver {

    data class Split(val left: String, val right: String)

    private val trackNumber = Regex("""^\s*\d{1,3}\s*[.\-)]?\s+""")
    private val separator = Regex("""\s+[-–—]\s+""")

    /** Todas las formas de partir el nombre por " - " (de izquierda a derecha). */
    fun splits(fileName: String): List<Split> {
        val base = fileName.substringBeforeLast('.').replace('_', ' ').replace(trackNumber, "").trim()
        val seps = separator.findAll(base).toList()
        return seps.map { m -> Split(base.substring(0, m.range.first).trim(), base.substring(m.range.last + 1).trim()) }
            .filter { it.left.isNotBlank() && it.right.isNotBlank() }
    }

    private fun key(s: String) = Matching.normalize(Matching.cleanArtist(s))

    /**
     * Artistas "conocidos" de la biblioteca, con qué canciones los respaldan: los de las
     * etiquetas y los fragmentos de nombre que se repiten en varios archivos (un artista se
     * repite; un título casi nunca). Siempre se excluye la propia canción.
     */
    class ArtistIndex(tracks: List<Track>) {
        private val fromTags = HashMap<String, MutableSet<String>>()
        private val fromNames = HashMap<String, MutableSet<String>>()

        init {
            tracks.forEach { t ->
                (t.tags.artists + listOfNotNull(t.tags.albumArtist)).forEach { a ->
                    key(a).takeIf { it.isNotEmpty() }?.let { fromTags.getOrPut(it) { HashSet() } += t.uri }
                }
                if (!t.hasTaggedIdentity) splits(t.fileName).forEach { sp ->
                    listOf(sp.left, sp.right).map(::key).filter { it.isNotEmpty() }.distinct()
                        .forEach { fromNames.getOrPut(it) { HashSet() } += t.uri }
                }
            }
        }

        /** Nº de OTRAS canciones que respaldan que [name] es un artista. */
        fun support(name: String, excludingUri: String): Int {
            val k = key(name)
            return ((fromTags[k].orEmpty() + fromNames[k].orEmpty()) - excludingUri).size
        }

        fun isArtist(name: String, excludingUri: String) = support(name, excludingUri) >= 1
    }

    /** (título, artista) si la biblioteca permite decidirlo sin conexión. */
    fun resolveOffline(track: Track, index: ArtistIndex): Pair<String, String>? {
        for (sp in splits(track.fileName)) {
            val leftKnown = index.isArtist(sp.left, track.uri)
            val rightKnown = index.isArtist(sp.right, track.uri)
            if (leftKnown && !rightKnown) return Matching.cleanTitle(sp.right) to sp.left
            if (rightKnown && !leftKnown) return Matching.cleanTitle(sp.left) to sp.right
        }
        return null
    }

    /**
     * Pregunta a Deezer (y a iTunes si hace falta) con las dos partes y mira en qué orden
     * coinciden título y artista. Bloqueante.
     */
    fun resolveOnline(fileName: String): Pair<String, String>? {
        val all = splits(fileName)
        if (all.isEmpty()) return null
        val text = all.first().let { "${it.left} ${it.right}" }
        for (source in listOf(DeezerSource, ITunesSource)) {
            val results = runCatching { source.search(TrackQuery(text)) }.getOrDefault(emptyList()).take(10)
            for (r in results) {
                val title = r.tags.title ?: continue
                val artist = r.tags.artist ?: continue
                for (sp in all) {
                    val artistLeft = same(sp.left, artist, isArtist = true) && same(sp.right, title, isArtist = false)
                    val artistRight = same(sp.right, artist, isArtist = true) && same(sp.left, title, isArtist = false)
                    if (artistLeft && !artistRight) return Matching.cleanTitle(sp.right) to sp.left
                    if (artistRight && !artistLeft) return Matching.cleanTitle(sp.left) to sp.right
                }
            }
        }
        return null
    }

    private fun same(a: String, b: String, isArtist: Boolean): Boolean =
        if (isArtist) {
            Matching.similarity(a, b) >= 0.85 || Matching.similarity(Matching.cleanArtist(a), Matching.cleanArtist(b)) >= 0.9
        } else {
            Matching.similarity(Matching.cleanTitle(a), Matching.cleanTitle(b)) >= 0.9
        }

    /**
     * Etiquetas intercambiadas en el propio archivo: el "título" es el artista de al menos dos
     * canciones más de la biblioteca y el "artista" no aparece como artista en ninguna otra.
     */
    fun looksSwapped(track: Track, index: ArtistIndex): Boolean {
        if (!track.hasTaggedIdentity) return false
        val title = track.tags.title ?: return false
        val artist = track.tags.artists.firstOrNull() ?: return false
        if (key(title) == key(artist)) return false
        return index.support(title, track.uri) >= 2 && index.support(artist, track.uri) == 0
    }
}
