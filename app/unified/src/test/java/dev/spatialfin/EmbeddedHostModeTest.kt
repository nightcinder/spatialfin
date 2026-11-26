package dev.spatialfin

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import dev.jdtech.jellyfin.presentation.player.PlayRequest
import dev.spatialfin.test.SpatialFinTestApplication
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class EmbeddedHostModeTest {
    private class LatchedActivity(private val latched: Boolean) :
        Activity(), EmbeddedHostMode.EmbeddedHostState {
        override val isEmbeddedHost: Boolean
            get() = latched
    }

    @Test
    fun `isActive returns false for non-activity context`() {
        val appContext = RuntimeEnvironment.getApplication()
        assertFalse(EmbeddedHostMode.isActive(appContext))
    }

    @Test
    fun `isActive unwraps ContextWrapper chain to read Activity intent extra`() {
        val intent = Intent().putExtra(EmbeddedHostMode.EXTRA_EMBEDDED_HOST, true)
        val activity = Robolectric.buildActivity(Activity::class.java, intent).get()
        val wrapped = ContextWrapper(ContextWrapper(activity))

        assertTrue(EmbeddedHostMode.isActive(wrapped))
    }

    @Test
    fun `isActive returns false when Activity intent lacks extra`() {
        val activity = Robolectric.buildActivity(Activity::class.java, Intent()).get()
        val wrapped = ContextWrapper(activity)

        assertFalse(EmbeddedHostMode.isActive(wrapped))
    }

    @Test
    fun `isActive prefers EmbeddedHostState even after intent is replaced`() {
        val activity = LatchedActivity(latched = true)
        activity.intent = Intent() // Simulates onNewIntent/setIntent without the extra
        val wrapped = ContextWrapper(ContextWrapper(activity))

        assertTrue(EmbeddedHostMode.isActive(wrapped))
    }

    @Test
    fun `asNonImmersive downgrades standard play requests and preserves SyncPlay and UniversalMedia`() {
        val id = UUID.randomUUID()
        val library = PlayRequest.LibraryItem(itemId = id, itemKind = "Movie", immersive = true)
        assertFalse((library.asNonImmersive() as PlayRequest.LibraryItem).immersive)

        val syncPlay = PlayRequest.LibraryItem(
            itemId = id,
            itemKind = "Movie",
            immersive = true,
            openSyncPlayDialogOnStart = true,
        )
        assertTrue((syncPlay.asNonImmersive() as PlayRequest.LibraryItem).immersive)

        val local = PlayRequest.LocalMedia(mediaStoreId = 42L, startFromBeginning = false, immersive = true)
        assertFalse((local.asNonImmersive() as PlayRequest.LocalMedia).immersive)

        val network = PlayRequest.NetworkMedia(networkVideoId = "smb://1", startFromBeginning = false, immersive = true)
        assertFalse((network.asNonImmersive() as PlayRequest.NetworkMedia).immersive)

        val uri = PlayRequest.UriMedia(uri = Uri.parse("https://example.com/v.mp4"), immersive = true)
        assertFalse((uri.asNonImmersive() as PlayRequest.UriMedia).immersive)

        val universal = PlayRequest.UniversalMedia(
            pluginId = "p",
            id = "1",
            videoUrl = "https://example.com/u.mp4",
            name = "U",
        )
        assertEquals(universal, universal.asNonImmersive())
    }
}
