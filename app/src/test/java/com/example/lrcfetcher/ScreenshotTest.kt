package com.example.lrcfetcher

import android.app.Application
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import com.example.lrcfetcher.library.Track
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.ProviderResult
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.TextMode
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.lyrics.providers.AppleMusicProvider
import com.example.lrcfetcher.romanization.Romanizer
import com.example.lrcfetcher.ui.LibraryScreen
import com.example.lrcfetcher.ui.LyricotaTheme
import com.example.lrcfetcher.ui.LyricsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Capturas de la interfaz renderizada en la JVM (build/outputs/roborazzi). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun vm(): AppViewModel {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.folderUri = Uri.parse("content://test/tree/music")
        vm.tracks = listOf(
            Track("u1", "1", "0", "a.flac", "flac", 1, 3, "静かな夜", "Lyricota Band", "Demo", 213_000, hasEmbeddedLyrics = true, metadataLoaded = true),
            Track("u2", "2", "0", "b.mp3", "mp3", 1, 2, "Walking Home", "The Examples", "Sample Album", 200_000, metadataLoaded = true),
            Track("u3", "3", "0", "c.m4a", "m4a", 1, 1, "별빛", "예시 밴드", "샘플", 179_000, lrcDocId = "x", metadataLoaded = true),
            Track("u4", "4", "0", "d.opus", "opus", 1, 0, "夜空", "示例乐队", null, 325_000, metadataLoaded = true),
        )
        return vm
    }

    private fun appleLyrics(): Pair<SongCandidate, Lyrics> {
        val cand = SongCandidate(ProviderId.APPLE_MUSIC, "1", "静かな夜", "Lyricota Band")
        val json = javaClass.classLoader!!.getResourceAsStream("apple_sample.json")!!.bufferedReader().readText()
        return cand to AppleMusicProvider.parse(JSONObject(json), cand)!!
    }

    @Test
    fun library() {
        val vm = vm()
        compose.setContent { LyricotaTheme(false) { LibraryScreen(vm, SnackbarHostState(), {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/library_light.png")
    }

    @Test
    @Config(qualifiers = "es-w400dp-h860dp-xhdpi")
    fun librarySpanish() {
        val vm = vm()
        compose.setContent { LyricotaTheme(false) { LibraryScreen(vm, SnackbarHostState(), {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/library_es.png")
    }

    @Test
    fun librarySelection() {
        val vm = vm()
        vm.tracks = vm.tracks.mapIndexed { i, t -> if (i == 3) t.copy(needsReview = true) else t }
        vm.toggleSelection(vm.tracks[0]); vm.toggleSelection(vm.tracks[2])
        compose.setContent { LyricotaTheme(false) { LibraryScreen(vm, SnackbarHostState(), {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/library_selection.png")
    }

    @Test
    fun metadataSection() {
        val vm = vm()
        vm.changeSection(Section.METADATA)
        vm.tracks = vm.tracks.mapIndexed { i, t ->
            when (i) {
                0 -> t.copy(tags = com.example.lrcfetcher.tags.TrackTags(title = t.title, artists = listOfNotNull(t.artist), album = "Demo", year = "2020", trackNumber = 1, genres = listOf("Pop"), albumArtist = t.artist, composers = listOf("X")), hasCover = true)
                1 -> t.copy(tags = com.example.lrcfetcher.tags.TrackTags(title = t.title, artists = listOfNotNull(t.artist)), needsReview = true)
                2 -> t.copy(identity = com.example.lrcfetcher.library.Identity.CHECKED)
                else -> t.copy(tags = com.example.lrcfetcher.tags.TrackTags(title = t.title, artists = listOfNotNull(t.artist), album = "Álbum"), swapSuspected = true)
            }
        }
        compose.setContent { LyricotaTheme(false) { LibraryScreen(vm, SnackbarHostState(), {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/metadata_section.png")
    }

    @Test
    fun metadata() {
        val vm = vm()
        val track = vm.tracks[1].copy(tags = com.example.lrcfetcher.tags.TrackTags(title = "Walking Home", artists = listOf("The Examples")), needsReview = true)
        val session = MetadataSession(track)
        val q = TrackQuery("Walking Home", "The Examples", durationMs = 200_000)
        fun c(src: com.example.lrcfetcher.metadata.MetaSource, album: String, extra: (com.example.lrcfetcher.tags.TrackTags) -> com.example.lrcfetcher.tags.TrackTags) =
            com.example.lrcfetcher.metadata.MetadataCandidate(src, src.name, extra(com.example.lrcfetcher.tags.TrackTags(
                title = "Walking Home", artists = listOf("The Examples"), album = album, albumArtist = "The Examples",
                year = "2001-02-03", trackNumber = 3, trackTotal = 12, discNumber = 1, discTotal = 1, genres = listOf("Rock"))), 200_300)
        session.result = com.example.lrcfetcher.metadata.MetadataRepository.combine(q, listOf(
            c(com.example.lrcfetcher.metadata.MetaSource.DEEZER, "Sample Album") { it },
            c(com.example.lrcfetcher.metadata.MetaSource.MUSICBRAINZ, "Sample Album") { it.copy(composers = listOf("Jane Doe", "John Roe")) },
        ))
        session.searched = true
        vm.applyMetadata(session, session.result!!.merged!!)
        compose.setContent { LyricotaTheme(false) { com.example.lrcfetcher.ui.MetadataScreen(vm, session, SnackbarHostState()) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/metadata.png")
    }

    @Test
    fun about() {
        compose.setContent { LyricotaTheme(false) { com.example.lrcfetcher.ui.AboutScreen(onBack = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/about.png")
    }

    @Test
    @Config(qualifiers = "es-w400dp-h860dp-xhdpi")
    fun onboarding() {
        var page by androidx.compose.runtime.mutableStateOf(0)
        compose.setContent {
            LyricotaTheme(false) {
                androidx.compose.runtime.key(page) { com.example.lrcfetcher.ui.OnboardingScreen(page == 5, {}, {}, startPage = page) }
            }
        }
        listOf(0, 1, 4, 5).forEach { p ->
            page = p
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding_$p.png")
        }
    }

    @Test
    @Config(qualifiers = "es-w400dp-h860dp-xhdpi")
    fun updateDialog() {
        val vm = vm()
        val info = com.example.lrcfetcher.update.UpdateInfo(
            "2.5", "## Lyricota 2.5\n- Aviso de actualización dentro de la app\n- Correcciones", "https://github.com/M4th01/Lyricota/releases/tag/v2.5",
            "https://x/Lyricota-2.5.apk", 36_611_475,
        )
        compose.setContent { LyricotaTheme(false) { com.example.lrcfetcher.ui.UpdateDialog(vm, info) } }
        compose.onAllNodes(androidx.compose.ui.test.isRoot())[1].captureRoboImage("build/outputs/roborazzi/update_dialog.png")
    }

    @Test
    @Config(qualifiers = "es-w400dp-h860dp-xhdpi")
    fun reviewSection() {
        val vm = vm()
        vm.tracks = vm.tracks.mapIndexed { i, t -> if (i >= 2) t.copy(needsReview = true) else t }
        vm.changeSection(Section.METADATA)
        vm.updateMetaFilter(MetaFilter.REVIEW)
        compose.setContent { LyricotaTheme(false) { LibraryScreen(vm, SnackbarHostState(), {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/review_section.png")
    }

    @Test
    fun libraryDark() {
        val vm = vm()
        compose.setContent { LyricotaTheme(true) { LibraryScreen(vm, SnackbarHostState(), {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/library_dark.png")
    }

    @Test
    fun lyricsInSong() {
        val vm = vm()
        val (cand, lyrics) = appleLyrics()
        val session = LyricsSession(vm.tracks[1], TrackQuery("Walking Home", "The Examples"), null)
        val parsed = LyricsParsers.parseLrc("[00:01.00]A line that was already in the file\n[00:04.00]Second invented line\n[00:08.00]Third one")
        val local = Lyrics(parsed.lines, parsed.sync, ProviderId.LOCAL, "u2")
        session.results[ProviderId.LOCAL] = ProviderResult.Found(SongCandidate(ProviderId.LOCAL, "u2", "Walking Home", "The Examples"), local, 1.0)
        session.results[ProviderId.APPLE_MUSIC] = ProviderResult.Found(cand, lyrics, 1.0)
        session.results[ProviderId.LRCLIB] = ProviderResult.NotFound
        session.selected = ProviderId.LOCAL
        compose.setContent { LyricotaTheme(true) { LyricsScreen(vm, session, SnackbarHostState()) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/lyrics_in_song.png")
    }

    @Test
    @Config(qualifiers = "es-w400dp-h860dp-xhdpi")
    fun syncEditor() {
        val vm = vm()
        val session = LyricsSession(vm.tracks[1], TrackQuery("Walking Home", "The Examples"), null)
        val sync = SyncSession(
            session,
            listOf(
                SyncLine("Primera línea inventada", 1200), SyncLine("Segunda línea", 4800),
                SyncLine("Tercera, la que sigue", null), SyncLine("Cuarta", null), SyncLine("Quinta y última", null),
            ),
        )
        compose.setContent { LyricotaTheme(false) { com.example.lrcfetcher.ui.SyncScreen(vm, sync) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sync_editor.png")
    }

    @Test
    @Config(qualifiers = "es-w400dp-h860dp-xhdpi")
    fun renameDialog() {
        val vm = vm()
        vm.tracks = vm.tracks.map { it.copy(tags = com.example.lrcfetcher.tags.TrackTags(title = it.title, artists = listOfNotNull(it.artist), trackNumber = 1)) }
        compose.setContent { LyricotaTheme(false) { com.example.lrcfetcher.ui.RenameDialog(vm) {} } }
        compose.onAllNodes(androidx.compose.ui.test.isRoot())[1].captureRoboImage("build/outputs/roborazzi/rename.png")
    }

    @Test
    fun lyricsBoth() {
        val vm = vm()
        val (cand, lyrics) = appleLyrics()
        val session = LyricsSession(vm.tracks[0], TrackQuery("静かな夜", "Lyricota Band"), null)
        session.results[ProviderId.APPLE_MUSIC] = ProviderResult.Found(cand, lyrics, 1.0)
        session.results[ProviderId.QQ_MUSIC] = ProviderResult.Loading
        session.results[ProviderId.LRCLIB] = ProviderResult.NotFound
        session.selected = ProviderId.APPLE_MUSIC
        vm.setTextModeFor(null, TextMode.BOTH)
        vm.setTranslation(true)
        compose.setContent { LyricotaTheme(false) { LyricsScreen(vm, session, SnackbarHostState()) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/lyrics_both.png")
    }

    @Test
    fun lyricsLocalRomanizationDark() {
        val vm = vm()
        val (cand, raw) = appleLyrics()
        // Sin romanización oficial: usa el romanizador local sobre los mismos segmentos.
        val stripped = raw.copy(officialRomanization = false, lines = raw.lines.map { it.copy(romanWords = null, translation = null) })
        val roman = Romanizer.romanize(stripped)
        val session = LyricsSession(null, TrackQuery("静かな夜", "Lyricota Band"), cand)
        session.results[ProviderId.QQ_MUSIC] = ProviderResult.Found(cand.copy(provider = ProviderId.QQ_MUSIC), stripped.copy(source = ProviderId.QQ_MUSIC), 1.0)
        session.romanized[cand.copy(provider = ProviderId.QQ_MUSIC).key] = roman
        session.selected = ProviderId.QQ_MUSIC
        vm.setTextModeFor(null, TextMode.ROMANIZED)
        compose.setContent { LyricotaTheme(true) { LyricsScreen(vm, session, SnackbarHostState()) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/lyrics_romanized_dark.png")
        LyricsParsers.hashCode()
    }
}
