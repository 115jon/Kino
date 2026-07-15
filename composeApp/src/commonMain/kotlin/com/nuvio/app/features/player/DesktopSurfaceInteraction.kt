package com.nuvio.app.features.player

internal fun shouldNotifyPlayerSurfaceExit(wasInside: Boolean, isInside: Boolean): Boolean =
    wasInside && !isInside
