package com.example.lrcfetcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.lrcfetcher.library.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewTest {
    @Test
    fun rejectAllClearsPendingWithoutTouchingTags() {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.tracks = (1..4).map { i ->
            Track("u$i", "$i", "0", "$i.mp3", "mp3", 1, 1, "Song $i", "Artist", null, null, metadataLoaded = true, needsReview = i % 2 == 0)
        }
        vm.updateMetaFilter(MetaFilter.REVIEW)
        val before = vm.tracks.map { it.tags }
        assertEquals(2, vm.reviewCount)

        vm.dismissAllReviews()

        assertEquals(0, vm.reviewCount)
        assertEquals(before, vm.tracks.map { it.tags })
        assertEquals(MetaFilter.ALL, vm.metaFilter)
        assertTrue(vm.message != null)
    }
}
