package com.example.lrcfetcher

/** Enlaces del proyecto (un solo sitio para cambiarlos). */
object AppInfo {
    /** Página de Ko-fi del autor (botón "Buy a small coffee"). */
    const val KOFI_URL = "https://ko-fi.com/A0A618Z66T"

    /** Contacto que se envía en el User-Agent a MusicBrainz, como pide su API. */
    const val CONTACT_URL = KOFI_URL

    val USER_AGENT = "Lyricota/${BuildConfig.VERSION_NAME} ( $CONTACT_URL )"
}
