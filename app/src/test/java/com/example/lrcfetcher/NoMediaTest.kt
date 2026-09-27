package com.example.lrcfetcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.example.lrcfetcher.library.LibraryScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Árbol falso: música/ (raíz), música/Album/, música/Grabaciones/.nomedia, música/Grabaciones/Sub/. */
class FakeTreeProvider : ContentProvider() {
    private val dir = DocumentsContract.Document.MIME_TYPE_DIR
    private val tree = mapOf(
        "root" to listOf(Triple("root/a.mp3", "a.mp3", "audio/mpeg"), Triple("root/Album", "Album", dir), Triple("root/Rec", "Grabaciones", dir)),
        "root/Album" to listOf(Triple("root/Album/b.flac", "b.flac", "audio/flac"), Triple("root/Album/b.lrc", "b.lrc", "text/plain")),
        "root/Rec" to listOf(Triple("root/Rec/.nomedia", ".nomedia", "application/octet-stream"), Triple("root/Rec/voz.m4a", "voz.m4a", "audio/mp4"), Triple("root/Rec/Sub", "Sub", dir)),
        "root/Rec/Sub" to listOf(Triple("root/Rec/Sub/c.ogg", "c.ogg", "audio/ogg")),
    )

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val parent = DocumentsContract.getDocumentId(uri)
        val c = MatrixCursor(projection)
        tree[parent].orEmpty().forEach { (id, name, mime) -> c.addRow(arrayOf(id, name, mime, 1L, 1L)) }
        return c
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoMediaTest {
    private val tree = DocumentsContract.buildTreeDocumentUri("test.tree", "root")

    private fun names(skip: Boolean): List<String> {
        Robolectric.setupContentProvider(FakeTreeProvider::class.java, "test.tree")
        val scanner = LibraryScanner(ApplicationProvider.getApplicationContext())
        return runBlocking { scanner.list(tree, skip) }.audio.map { it.name }.sorted()
    }

    @Test
    fun foldersWithNoMediaAreSkipped() {
        assertEquals(listOf("a.mp3", "b.flac"), names(skip = true))
    }

    @Test
    fun canBeTurnedOff() {
        assertEquals(listOf("a.mp3", "b.flac", "c.ogg", "voz.m4a"), names(skip = false))
    }
}
