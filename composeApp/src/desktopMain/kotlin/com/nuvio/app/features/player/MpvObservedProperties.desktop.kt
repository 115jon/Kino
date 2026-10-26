package com.nuvio.app.features.player

import java.util.concurrent.ConcurrentHashMap

internal data class MpvObservedProperty(
    val name: String,
    val format: Int,
    val notifiesState: Boolean,
)

internal val mpvObservedProperties: List<MpvObservedProperty> = listOf(
    MpvObservedProperty("pause", MPV_FORMAT_FLAG, notifiesState = true),
    MpvObservedProperty("paused-for-cache", MPV_FORMAT_FLAG, notifiesState = true),
    MpvObservedProperty("core-idle", MPV_FORMAT_FLAG, notifiesState = true),
    MpvObservedProperty("eof-reached", MPV_FORMAT_FLAG, notifiesState = true),
    MpvObservedProperty("seeking", MPV_FORMAT_FLAG, notifiesState = true),
    MpvObservedProperty("track-list/count", MPV_FORMAT_INT64, notifiesState = true),
    MpvObservedProperty("aid", MPV_FORMAT_INT64, notifiesState = true),
    MpvObservedProperty("volume", MPV_FORMAT_DOUBLE, notifiesState = true),
    MpvObservedProperty("duration", MPV_FORMAT_DOUBLE, notifiesState = true),
    MpvObservedProperty("speed", MPV_FORMAT_DOUBLE, notifiesState = true),
    MpvObservedProperty("time-pos", MPV_FORMAT_DOUBLE, notifiesState = false),
    MpvObservedProperty("demuxer-cache-time", MPV_FORMAT_DOUBLE, notifiesState = false),
    MpvObservedProperty("path", MPV_FORMAT_STRING, notifiesState = false),
    MpvObservedProperty("media-title", MPV_FORMAT_STRING, notifiesState = false),
    MpvObservedProperty("file-format", MPV_FORMAT_STRING, notifiesState = false),
    MpvObservedProperty("vo-configured", MPV_FORMAT_FLAG, notifiesState = false),
    MpvObservedProperty("video-params/w", MPV_FORMAT_INT64, notifiesState = false),
    MpvObservedProperty("video-params/h", MPV_FORMAT_INT64, notifiesState = false),
)

private val mpvSilentProperties = mpvObservedProperties
    .filterNot { it.notifiesState }
    .mapTo(HashSet()) { it.name }

internal fun mpvPropertyChangeNotifiesState(name: String): Boolean = name !in mpvSilentProperties

internal class MpvPropertyCache {
    private val values = ConcurrentHashMap<String, Any>()

    fun update(name: String, value: Any?) {
        if (name.isEmpty()) return
        if (value == null) values.remove(name) else values[name] = value
    }

    fun clear() {
        values.clear()
    }

    fun double(name: String): Double? = when (val value = values[name]) {
        is Double -> value
        is Long -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    fun long(name: String): Long? = when (val value = values[name]) {
        is Long -> value
        is Double -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    fun flag(name: String): Boolean = values[name] == true

    fun flagString(name: String): String? = when (val value = values[name]) {
        is Boolean -> if (value) "yes" else "no"
        null -> null
        else -> value.toString()
    }

    fun string(name: String): String? = values[name]?.toString()
}
