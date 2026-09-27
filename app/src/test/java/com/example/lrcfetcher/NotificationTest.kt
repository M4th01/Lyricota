package com.example.lrcfetcher

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)

    private fun shown(id: Int): Notification? = shadowOf(nm).getNotification(id)

    @Test
    fun progressThenFinished() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = BatchNotifier(app)
        val start = BatchState(kind = BatchKind.LYRICS, running = true, total = 4)
        n.onBatch(BatchState(), start)
        // Arranca el servicio en primer plano.
        assertEquals(BatchService::class.java.name, shadowOf(app).nextStartedService.component!!.className)

        n.onBatch(start, start.copy(done = 1, current = "Canción"))
        val p = shown(BatchNotifier.ID_PROGRESS)!!
        assertEquals("25% · 1/4 · Canción", p.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(1, p.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(4, p.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(p.flags and Notification.FLAG_ONGOING_EVENT != 0)

        val end = start.copy(running = false, done = 4, found = 3, notFound = 1)
        n.onBatch(start.copy(done = 4), end)
        assertNull(shown(BatchNotifier.ID_PROGRESS))
        val d = shown(BatchNotifier.ID_DONE)!!
        assertEquals("Finding lyrics · finished (100%)", d.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("3 found · 1 without lyrics", d.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun cancelledShowsWhereItStopped() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = BatchNotifier(app)
        val run = BatchState(kind = BatchKind.METADATA, running = true, total = 10, done = 3, found = 2, review = 1)
        n.onBatch(BatchState(), run)
        n.onBatch(run, run.copy(running = false))
        val d = shown(BatchNotifier.ID_DONE)!!
        assertEquals("Completing metadata · stopped at 30%", d.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun nothingIsPostedWithoutPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = BatchNotifier(app)
        val run = BatchState(running = true, total = 2)
        n.onBatch(BatchState(), run)
        n.onBatch(run, run.copy(done = 1))
        n.onBatch(run, run.copy(running = false, done = 2))
        assertNull(shown(BatchNotifier.ID_DONE))
        assertNotNull(nm)
    }
}
