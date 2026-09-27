package com.example.lrcfetcher

import android.content.Context
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TextMode

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SaveTarget { EMBED, LRC, BOTH }
enum class LibraryFilter { ALL, MISSING, HAS }

/** Filtros de la sección Metadatos. */
enum class MetaFilter { ALL, INCOMPLETE, REVIEW, NO_COVER }

/** Secciones de la pantalla principal (navegación inferior). */
enum class Section { LYRICS, METADATA, ONLINE }
enum class SortMode { TITLE, ARTIST, RECENT }

/** Idiomas de la interfaz ("" = el del sistema). El nombre se muestra en su propio idioma. */
enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", ""),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    PORTUGUESE("pt", "Português"),
    JAPANESE("ja", "日本語"),
}

/** Preferencias persistentes (SharedPreferences, valores pequeños). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("lyricota_prefs", Context.MODE_PRIVATE)

    init {
        // Limpieza de la versión anterior: la caché de carpeta ahora vive en un archivo.
        val stale = prefs.all.keys.filter { it.startsWith("folder_cache") || it.startsWith("folder_fingerprint") || it == "musixmatch_api_key" }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    var folderUri: String?
        get() = prefs.getString("folder_uri", null)
        set(v) = prefs.edit().putString("folder_uri", v).apply()

    var theme: ThemeMode
        get() = enumOr(prefs.getString("theme", null), ThemeMode.SYSTEM)
        set(v) = prefs.edit().putString("theme", v.name).apply()

    /** Orden de preferencia de proveedores (los WbW primero por defecto). */
    var providerOrder: List<ProviderId>
        get() {
            val saved = prefs.getString("provider_order", null)?.split(',')?.mapNotNull { n -> ProviderId.entries.firstOrNull { it.name == n } }
                .orEmpty()
            // Proveedores nuevos (p. ej. AMLL) se insertan en su posición por defecto.
            val result = saved.toMutableList()
            ProviderId.entries.forEachIndexed { i, id -> if (id !in result) result.add(i.coerceAtMost(result.size), id) }
            return result
        }
        set(v) = prefs.edit().putString("provider_order", v.joinToString(",") { it.name }).apply()

    var disabledProviders: Set<ProviderId>
        get() = prefs.getStringSet("providers_off", emptySet()).orEmpty()
            .mapNotNull { n -> ProviderId.entries.firstOrNull { it.name == n } }.toSet()
        set(v) = prefs.edit().putStringSet("providers_off", v.map { it.name }.toSet()).apply()

    val enabledProviders: List<ProviderId> get() = providerOrder.filter { it !in disabledProviders }

    var saveTarget: SaveTarget
        get() = enumOr(prefs.getString("save_target", null), SaveTarget.EMBED)
        set(v) = prefs.edit().putString("save_target", v.name).apply()

    var format: SyncType
        get() = enumOr(prefs.getString("format", null), SyncType.WORD)
        set(v) = prefs.edit().putString("format", v.name).apply()

    var textMode: TextMode
        get() = enumOr(prefs.getString("text_mode", null), TextMode.ORIGINAL)
        set(v) = prefs.edit().putString("text_mode", v.name).apply()

    var includeTranslation: Boolean
        get() = prefs.getBoolean("translation", false)
        set(v) = prefs.edit().putBoolean("translation", v).apply()

    var includeVoices: Boolean
        get() = prefs.getBoolean("voices", true)
        set(v) = prefs.edit().putBoolean("voices", v).apply()

    var millis: Boolean
        get() = prefs.getBoolean("millis", true)
        set(v) = prefs.edit().putBoolean("millis", v).apply()

    var language: AppLanguage
        get() = enumOr(prefs.getString("language", null), AppLanguage.SYSTEM)
        // commit(): la actividad se recrea justo después y debe leer el valor nuevo.
        set(v) { prefs.edit().putString("language", v.name).commit() }

    var filter: LibraryFilter
        get() = enumOr(prefs.getString("filter", null), LibraryFilter.ALL)
        set(v) = prefs.edit().putString("filter", v.name).apply()

    var metaFilter: MetaFilter
        get() = enumOr(prefs.getString("meta_filter", null), MetaFilter.ALL)
        set(v) = prefs.edit().putString("meta_filter", v.name).apply()

    /** Ya se mostró el tutorial de la primera vez. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit().putBoolean("onboarding_done", v).apply()

    /** Buscar actualizaciones en GitHub al abrir la app. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean("check_updates", true)
        set(v) = prefs.edit().putBoolean("check_updates", v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(v) = prefs.edit().putLong("last_update_check", v).apply()

    /** Versión que el usuario pidió no volver a avisar. */
    var skippedVersion: String?
        get() = prefs.getString("skipped_version", null)
        set(v) = prefs.edit().putString("skipped_version", v).apply()

    var sort: SortMode
        get() = enumOr(prefs.getString("sort", null), SortMode.TITLE)
        set(v) = prefs.edit().putString("sort", v.name).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
