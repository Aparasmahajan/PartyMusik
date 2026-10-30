package io.mahajan.partymusik

import android.content.Intent
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.mahajan.partymusik.ports.PlaybackPort
import io.mahajan.partymusik.ports.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * MED-003: exposes app playback to the system (notification, lock screen, Bluetooth AVRCP buttons, car head
 * units) through a Media3 MediaSession. Media3 also keeps the process in the foreground while audio plays.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        val app = application as PartyMusikApplication
        session = MediaSession.Builder(this, PlaybackPortPlayer(app.platform.output)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * Adapts [PlaybackPort] to a Media3 [Player]. Commands from Media3 controllers go to the port; the port's
 * status is the single source of truth, so the in-app UI and the system controls never disagree.
 */
@UnstableApi
class PlaybackPortPlayer(private val playback: PlaybackPort) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // Position ticks are read live by the position supplier; only re-publish state on real changes.
        playback.status
            .map { Triple(it.item?.id, it.playing, it.error) }
            .distinctUntilChanged()
            .onEach { invalidateState() }
            .launchIn(scope)
    }

    override fun getState(): State {
        val status = playback.status.value
        val playlist = playback.library.map { item ->
            MediaItemData.Builder(item.id)
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(item.id)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(item.title)
                                .setArtist("PartyMusik")
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .build(),
                        )
                        .build(),
                )
                .setDurationUs(item.durationMs * 1_000)
                .setIsSeekable(true)
                .build()
        }
        val index = playback.library.indexOfFirst { it.id == status.item?.id }.coerceAtLeast(0)
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setPlayWhenReady(status.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState(status))
            .setContentPositionMs { playback.status.value.positionMs }
            .build()
    }

    private fun playbackState(status: PlaybackStatus): Int = when {
        status.error != null -> Player.STATE_IDLE
        status.buffering -> Player.STATE_BUFFERING
        else -> Player.STATE_READY
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) playback.play() else playback.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = playback.library.getOrNull(mediaItemIndex) ?: return Futures.immediateVoidFuture()
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (target.id == playback.status.value.item?.id) {
            playback.seekTo(position)
            return Futures.immediateVoidFuture()
        }
        val wasPlaying = playback.status.value.playing
        scope.launch {
            playback.load(target)
            playback.seekTo(position)
            if (wasPlaying) playback.play()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playback.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
