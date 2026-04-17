package com.nuvio.app.features.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal interface DesktopMPVBridgeLib : Library {
    companion object {
        val INSTANCE: DesktopMPVBridgeLib by lazy {
            val libPath = resolveLibraryPath()
            if (libPath != null) {
                System.setProperty(
                    "jna.library.path",
                    (System.getProperty("jna.library.path") ?: "") + ":" + libPath,
                )
            }
            Native.load("DesktopMPVBridge", DesktopMPVBridgeLib::class.java)
        }

        private fun resolveLibraryPath(): String? {
            val candidates = listOf(
                "MPVKit/.build/arm64-apple-macosx/release",
                "MPVKit/.build/arm64-apple-macosx/debug",
                "../MPVKit/.build/arm64-apple-macosx/release",
                "../MPVKit/.build/arm64-apple-macosx/debug",
            )
            val userDir = System.getProperty("user.dir") ?: return null
            for (candidate in candidates) {
                val dir = java.io.File(userDir, candidate)
                if (dir.exists() && dir.isDirectory) {
                    val dylib = java.io.File(dir, "libDesktopMPVBridge.dylib")
                    if (dylib.exists()) return dir.absolutePath
                }
            }
            return null
        }
    }

    fun nuvio_player_create(): Pointer
    fun nuvio_player_destroy(player: Pointer)
    fun nuvio_player_show(player: Pointer)

    fun nuvio_player_set_metadata(
        player: Pointer,
        title: String,
        streamTitle: String,
        providerName: String,
        season: Int,
        episode: Int,
        episodeTitle: String?,
    )

    fun nuvio_player_load_file(
        player: Pointer,
        url: String,
        audioUrl: String?,
        headersJson: String?,
    )

    fun nuvio_player_play(player: Pointer)
    fun nuvio_player_pause(player: Pointer)
    fun nuvio_player_seek_to(player: Pointer, positionMs: Long)
    fun nuvio_player_seek_by(player: Pointer, offsetMs: Long)
    fun nuvio_player_set_speed(player: Pointer, speed: Float)
    fun nuvio_player_set_resize_mode(player: Pointer, mode: Int)
    fun nuvio_player_retry(player: Pointer)

    fun nuvio_player_refresh_state(player: Pointer)
    fun nuvio_player_is_loading(player: Pointer): Boolean
    fun nuvio_player_is_playing(player: Pointer): Boolean
    fun nuvio_player_is_ended(player: Pointer): Boolean
    fun nuvio_player_get_position_ms(player: Pointer): Long
    fun nuvio_player_get_duration_ms(player: Pointer): Long
    fun nuvio_player_get_buffered_ms(player: Pointer): Long
    fun nuvio_player_get_speed(player: Pointer): Float
    fun nuvio_player_get_error(player: Pointer): String?

    fun nuvio_player_get_audio_track_count(player: Pointer): Int
    fun nuvio_player_get_audio_track_id(player: Pointer, index: Int): Int
    fun nuvio_player_get_audio_track_label(player: Pointer, index: Int): String?
    fun nuvio_player_get_audio_track_lang(player: Pointer, index: Int): String?
    fun nuvio_player_is_audio_track_selected(player: Pointer, index: Int): Boolean
    fun nuvio_player_select_audio_track(player: Pointer, trackId: Int)

    fun nuvio_player_get_subtitle_track_count(player: Pointer): Int
    fun nuvio_player_get_subtitle_track_id(player: Pointer, index: Int): Int
    fun nuvio_player_get_subtitle_track_label(player: Pointer, index: Int): String?
    fun nuvio_player_get_subtitle_track_lang(player: Pointer, index: Int): String?
    fun nuvio_player_is_subtitle_track_selected(player: Pointer, index: Int): Boolean
    fun nuvio_player_select_subtitle_track(player: Pointer, trackId: Int)

    fun nuvio_player_set_subtitle_url(player: Pointer, url: String)
    fun nuvio_player_clear_external_subtitle(player: Pointer)
    fun nuvio_player_clear_external_subtitle_and_select(player: Pointer, trackId: Int)
    fun nuvio_player_apply_subtitle_style(
        player: Pointer,
        textColor: String,
        outlineSize: Float,
        fontSize: Float,
        subPos: Int,
    )

    fun nuvio_player_show_skip_button(player: Pointer, type: String, endTimeMs: Long)
    fun nuvio_player_hide_skip_button(player: Pointer)

    fun nuvio_player_show_next_episode(
        player: Pointer,
        season: Int,
        episode: Int,
        title: String,
        thumbnail: String?,
        hasAired: Boolean,
    )
    fun nuvio_player_hide_next_episode(player: Pointer)

    fun nuvio_player_is_closed(player: Pointer): Boolean
    fun nuvio_player_pop_next_episode_pressed(player: Pointer): Boolean
}
