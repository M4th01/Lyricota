package com.example.lrcfetcher

/** Enlaces del proyecto (un solo sitio para cambiarlos). */
object AppInfo {
    /** Repositorio de GitHub (código y Releases con el APK). */
    const val REPO = "M4th01/Lyricota"
    const val REPO_URL = "https://github.com/$REPO"

    /** Página de Ko-fi del autor (botón "Buy a small coffee"). */
    const val KOFI_URL = "https://ko-fi.com/A0A618Z66T"

    /** Correo para "Deja tu comentario" ("" = sólo GitHub). */
    const val FEEDBACK_EMAIL = ""

    /** Contacto que se envía en el User-Agent a MusicBrainz y GitHub, como piden sus APIs. */
    const val CONTACT_URL = REPO_URL

    /**
     * Correo de contacto del proyecto. lrc.red y MusicBrainz piden que las apps que usan su API
     * se identifiquen con enlace y correo; quedará visible en el User-Agent.
     */
    const val CONTACT_EMAIL = ""

    val USER_AGENT = "Lyricota/${BuildConfig.VERSION_NAME} ( $CONTACT_URL" +
        (if (CONTACT_EMAIL.isNotBlank()) "; $CONTACT_EMAIL" else "") + " )"
}
