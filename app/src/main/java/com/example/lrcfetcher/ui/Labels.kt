package com.example.lrcfetcher.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.lrcfetcher.AppLanguage
import com.example.lrcfetcher.LibraryFilter
import com.example.lrcfetcher.MetaFilter
import com.example.lrcfetcher.R
import com.example.lrcfetcher.SaveTarget
import com.example.lrcfetcher.SortMode
import com.example.lrcfetcher.UiText
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TextMode
import java.util.Locale

/** Textos traducidos para los enums del modelo (el modelo no depende de recursos). */

@Composable
fun SyncType.label(): String = stringResource(
    when (this) {
        SyncType.WORD -> R.string.sync_word
        SyncType.LINE -> R.string.sync_line
        SyncType.PLAIN -> R.string.sync_plain
    },
)

@Composable
fun TextMode.label(): String = stringResource(
    when (this) {
        TextMode.ORIGINAL -> R.string.text_original
        TextMode.ROMANIZED -> R.string.text_romanized
        TextMode.BOTH -> R.string.text_both
    },
)

@Composable
fun SaveTarget.label(): String = stringResource(
    when (this) {
        SaveTarget.EMBED -> R.string.target_embed
        SaveTarget.LRC -> R.string.target_lrc
        SaveTarget.BOTH -> R.string.target_both
    },
)

@Composable
fun LibraryFilter.label(): String = stringResource(
    when (this) {
        LibraryFilter.ALL -> R.string.filter_all
        LibraryFilter.MISSING -> R.string.filter_missing
        LibraryFilter.HAS -> R.string.filter_has
    },
)

@Composable
fun MetaFilter.label(): String = stringResource(
    when (this) {
        MetaFilter.ALL -> R.string.filter_all
        MetaFilter.INCOMPLETE -> R.string.filter_no_metadata
        MetaFilter.REVIEW -> R.string.filter_review
        MetaFilter.NO_COVER -> R.string.filter_no_cover
        MetaFilter.DUPLICATES -> R.string.filter_duplicates
    },
)

@Composable
fun SortMode.label(): String = stringResource(
    when (this) {
        SortMode.TITLE -> R.string.sort_title
        SortMode.ARTIST -> R.string.sort_artist
        SortMode.RECENT -> R.string.sort_recent
    },
)

@Composable
fun AppLanguage.label(): String = if (this == AppLanguage.SYSTEM) stringResource(R.string.language_system) else nativeName

fun UiText.resolve(context: Context): String = context.getString(id, *args)

/** Aplica el idioma elegido en la app a un Context (se usa en attachBaseContext). */
object LocaleHelper {
    fun wrap(base: Context, language: AppLanguage): Context {
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
