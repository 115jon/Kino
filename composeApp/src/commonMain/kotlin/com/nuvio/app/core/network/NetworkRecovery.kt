package com.nuvio.app.core.network

internal fun shouldRefreshAfterNetworkRecovery(
    previous: NetworkCondition,
    current: NetworkCondition,
): Boolean =
    current == NetworkCondition.Online && previous.isOfflineLike()

private fun NetworkCondition.isOfflineLike(): Boolean =
    this == NetworkCondition.NoInternet || this == NetworkCondition.ServersUnreachable
