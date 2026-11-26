package dev.spatialfin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.jdtech.jellyfin.player.xr.MultitaskPlayerActivity
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.presentation.player.PlayRequest
import dev.spatialfin.fcast.session.LocalFCastSession
import dev.spatialfin.fcast.session.launchPlayback
import kotlinx.coroutines.CoroutineScope

/**
 * The app-side implementation of the [PlayRequest] seam. Browse screens emit a
 * [PlayRequest]; this maps it to the right `:player:xr` Activity intent and FCast
 * routing. Keeping it here means the screens (and the feature modules they will
 * move into) never reference `XrPlayerActivity` / `MultitaskPlayerActivity` /
 * the FCast session launcher directly.
 *
 * The logic below was lifted verbatim from the per-screen play handlers
 * (Movie/Episode/Show/Season/Home + Local/Network video) so playback behavior —
 * immersive-vs-multitask, stereo/projection extras, FCast Split-A/V routing,
 * resume position — is unchanged.
 */
@Composable
fun rememberPlaybackLauncher(): (PlayRequest) -> Unit {
    val context = LocalContext.current
    val fcastSession = LocalFCastSession.current
    val scope = rememberCoroutineScope()
    return remember(context, fcastSession, scope) {
        { request -> launchPlayRequest(context, fcastSession, scope, request) }
    }
}

private fun launchPlayRequest(
    context: Context,
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager?,
    scope: CoroutineScope,
    rawRequest: PlayRequest,
) {
    // When Helix hosts SpatialFin in a SpatialActivityPanel, never start the
    // FULL_SPACE_MANAGED XrPlayerActivity: it would steal the host's Full Space.
    // Play via MultitaskPlayerActivity instead. Watch-party (SyncPlay) and FCast
    // Split-A/V (splitAvIntentBuilder) have no multitask variant and still
    // launch XrPlayerActivity.
    val embedded = EmbeddedHostMode.isActive(context)
    val request = if (embedded) rawRequest.asNonImmersive() else rawRequest
    when (request) {
        is PlayRequest.LibraryItem -> launchLibraryItem(context, fcastSession, scope, request)
        is PlayRequest.LocalMedia -> {
            val intent = if (request.immersive) {
                XrPlayerActivity.createIntentForLocalMedia(
                    context = context,
                    mediaStoreId = request.mediaStoreId,
                    startFromBeginning = request.startFromBeginning,
                    stereoMode = request.stereoMode,
                    projection = request.projection ?: "flat",
                )
            } else {
                MultitaskPlayerActivity.createIntentForLocalMedia(
                    context = context,
                    mediaStoreId = request.mediaStoreId,
                    startFromBeginning = request.startFromBeginning,
                )
            }
            context.startActivity(intent)
        }
        is PlayRequest.NetworkMedia -> {
            val intent = if (request.immersive) {
                XrPlayerActivity.createIntentForNetworkMedia(
                    context = context,
                    networkVideoId = request.networkVideoId,
                    startFromBeginning = request.startFromBeginning,
                    stereoMode = request.stereoMode,
                    projection = request.projection ?: "flat",
                )
            } else {
                MultitaskPlayerActivity.createIntentForNetworkMedia(
                    context = context,
                    networkVideoId = request.networkVideoId,
                    startFromBeginning = request.startFromBeginning,
                )
            }
            context.startActivity(intent)
        }
        is PlayRequest.UniversalMedia -> {
            val intent = if (embedded) {
                MultitaskPlayerActivity.createIntentForUniversalMedia(
                    context = context,
                    pluginId = request.pluginId,
                    itemId = request.id,
                    videoUrl = request.videoUrl,
                    title = request.name,
                )
            } else {
                XrPlayerActivity.createIntentForUniversalMedia(
                    context,
                    request.pluginId,
                    request.id,
                    request.videoUrl,
                    request.name,
                    stereoMode = request.stereoMode,
                    projection = request.projection,
                )
            }
            context.startActivity(intent)
        }
        is PlayRequest.UriMedia -> {
            val intent = if (request.immersive) {
                XrPlayerActivity.createIntentForUri(
                    context = context,
                    uri = request.uri,
                    title = request.title,
                    startFromBeginning = request.startFromBeginning,
                    stereoMode = request.stereoMode,
                    projection = request.projection ?: "flat",
                )
            } else {
                MultitaskPlayerActivity.createIntentForUri(
                    context = context,
                    uri = request.uri,
                    title = request.title,
                    startFromBeginning = request.startFromBeginning,
                )
            }
            context.startActivity(intent)
        }
    }
}

private fun launchLibraryItem(
    context: Context,
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager?,
    scope: CoroutineScope,
    request: PlayRequest.LibraryItem,
) {
    val targetActivity = if (request.immersive) {
        XrPlayerActivity::class.java
    } else {
        MultitaskPlayerActivity::class.java
    }

    val buildLocalIntent: () -> Intent = {
        if (request.openSyncPlayDialogOnStart) {
            // Watch-party path: always immersive, no FCast routing.
            XrPlayerActivity.createIntent(
                context = context,
                itemId = request.itemId,
                itemKind = request.itemKind,
                startFromBeginning = request.startFromBeginning,
                stereoMode = request.stereoMode,
                projection = request.projection ?: "flat",
                openSyncPlayDialogOnStart = true,
            )
        } else {
            Intent(context, targetActivity).apply {
                putExtra("itemId", request.itemId.toString())
                putExtra("itemKind", request.itemKind)
                putExtra("startFromBeginning", request.startFromBeginning)
                request.mediaSourceIndex?.let { putExtra("mediaSourceIndex", it) }
                request.maxBitrate?.let { putExtra("maxBitrate", it) }
                request.audioStreamIndex?.let { putExtra("audioStreamIndex", it) }
                request.subtitleStreamIndex?.let { putExtra("subtitleStreamIndex", it) }
                if (request.subtitlesDisabled) putExtra("subtitlesDisabled", true)
                if (request.immersive) {
                    putExtra("stereoMode", request.stereoMode)
                    request.projection?.let { putExtra("projection", it) }
                }
            }
        }
    }

    val item = request.item
    if (request.allowFcastRouting && fcastSession != null && item != null) {
        val resumeMs = request.resumePositionMs
        launchPlayback(
            context = context,
            sessionManager = fcastSession,
            scope = scope,
            item = item,
            startPositionMs = resumeMs?.takeIf { it > 0 },
            splitAvIntentBuilder = {
                XrPlayerActivity.createIntent(
                    context = context,
                    itemId = request.itemId,
                    itemKind = request.itemKind,
                    startFromBeginning = request.startFromBeginning,
                    stereoMode = request.stereoMode,
                    projection = request.projection ?: "flat",
                    mediaSourceIndex = request.mediaSourceIndex,
                    maxBitrate = request.maxBitrate,
                    splitAvVideoRole = true,
                )
            },
            intentBuilder = buildLocalIntent,
        )
    } else {
        context.startActivity(buildLocalIntent())
    }
}

/** Watch/remote launch while the browse activity is visible; no player must already be running. */
internal suspend fun launchWearLibraryItem(
    context: Context,
    repository: dev.jdtech.jellyfin.repository.JellyfinRepository,
    deviceClass: dev.spatialfin.unified.DeviceClass,
    itemId: String,
    positionMs: Long,
    canLaunch: () -> Boolean,
): String {
    val item = repository.getItem(java.util.UUID.fromString(itemId)) ?: error("Item not found")
    check(canLaunch()) { "Open and unlock SpatialFin on the target device" }
    val intent = when (deviceClass) {
        dev.spatialfin.unified.DeviceClass.XR -> XrPlayerActivity.createIntentForItem(context, item)
        dev.spatialfin.unified.DeviceClass.TV -> dev.jdtech.jellyfin.player.tv.TvPlayerActivity.createIntentForSpatialItem(context, item, startPositionMs = positionMs.takeIf { it > 0 })
        dev.spatialfin.unified.DeviceClass.PHONE -> dev.jdtech.jellyfin.player.beam.BeamPlayerActivity.createIntentForSpatialItem(
            context, item, startPositionMs = positionMs.takeIf { it > 0 })
    } ?: error("This item cannot be played on the target")
    context.startActivity(intent)
    return "Opening ${item.name}"
}

/**
 * True when Helix launched [dev.spatialfin.unified.UnifiedMainActivity] inside a
 * SpatialActivityPanel with [EXTRA_EMBEDDED_HOST]. Activities implementing [EmbeddedHostState]
 * latch the flag across `onCreate`/`onNewIntent`/`onSaveInstanceState` so subsequent intents
 * (such as `spatialfin://ma/...` deep links or `ACTION_VIEW`) cannot clear embedded mode.
 * Fallback reads `activity.intent` directly. It only ever downgrades playback to the
 * non-Full-Space player, so an untrusted caller setting it gains nothing.
 */
object EmbeddedHostMode {
    const val EXTRA_EMBEDDED_HOST = "dev.spatialfin.extra.EMBEDDED_HOST"

    interface EmbeddedHostState {
        val isEmbeddedHost: Boolean
    }

    fun hasExtra(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_EMBEDDED_HOST, false) == true

    fun isActive(context: Context): Boolean {
        var current: Context? = context
        while (current != null) {
            if (current is EmbeddedHostState) {
                return current.isEmbeddedHost
            }
            if (current is Activity) {
                return hasExtra(current.intent)
            }
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }
}

internal fun PlayRequest.asNonImmersive(): PlayRequest = when (this) {
    is PlayRequest.LibraryItem -> if (openSyncPlayDialogOnStart) this else copy(immersive = false)
    is PlayRequest.LocalMedia -> copy(immersive = false)
    is PlayRequest.NetworkMedia -> copy(immersive = false)
    is PlayRequest.UriMedia -> copy(immersive = false)
    is PlayRequest.UniversalMedia -> this
}
