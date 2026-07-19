package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkRecoveryTest {
    @Test
    fun refreshesOnlyWhenOfflineStateReturnsOnline() {
        assertEquals(
            true,
            shouldRefreshAfterNetworkRecovery(
                previous = NetworkCondition.NoInternet,
                current = NetworkCondition.Online,
            ),
        )
        assertEquals(
            true,
            shouldRefreshAfterNetworkRecovery(
                previous = NetworkCondition.ServersUnreachable,
                current = NetworkCondition.Online,
            ),
        )
        assertEquals(
            false,
            shouldRefreshAfterNetworkRecovery(
                previous = NetworkCondition.Unknown,
                current = NetworkCondition.Online,
            ),
        )
        assertEquals(
            false,
            shouldRefreshAfterNetworkRecovery(
                previous = NetworkCondition.NoInternet,
                current = NetworkCondition.ServersUnreachable,
            ),
        )
    }
}
