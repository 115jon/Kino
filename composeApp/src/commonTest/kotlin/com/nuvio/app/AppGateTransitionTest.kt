package com.nuvio.app

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals

class AppGateTransitionTest {
    @Test
    fun `loading auth state keeps main gate with cached profiles`() {
        assertEquals(
            AppGateTransition.KeepCurrent,
            appGateTransition(
                currentScreen = AppGateScreen.Main,
                authState = AuthState.Loading,
                hasCachedProfiles = true,
                networkCondition = NetworkCondition.Online,
            ),
        )
    }

    @Test
    fun `unauthenticated auth state keeps main gate with cached profiles`() {
        assertEquals(
            AppGateTransition.KeepCurrent,
            appGateTransition(
                currentScreen = AppGateScreen.Main,
                authState = AuthState.Unauthenticated,
                hasCachedProfiles = true,
                networkCondition = NetworkCondition.Online,
            ),
        )
    }

    @Test
    fun `transient auth state keeps intentional profile selection`() {
        assertEquals(
            AppGateTransition.KeepCurrent,
            appGateTransition(
                currentScreen = AppGateScreen.ProfileSelection,
                authState = AuthState.Loading,
                hasCachedProfiles = true,
                networkCondition = NetworkCondition.Online,
            ),
        )
    }

    @Test
    fun `cleared profile state after sign out shows auth`() {
        assertEquals(
            AppGateTransition.ShowAuth,
            appGateTransition(
                currentScreen = AppGateScreen.Main,
                authState = AuthState.Unauthenticated,
                hasCachedProfiles = false,
                networkCondition = NetworkCondition.Online,
            ),
        )
    }

    @Test
    fun `online auth gate does not use cached profiles`() {
        assertEquals(
            AppGateTransition.ShowAuth,
            appGateTransition(
                currentScreen = AppGateScreen.Auth,
                authState = AuthState.Unauthenticated,
                hasCachedProfiles = true,
                networkCondition = NetworkCondition.Online,
            ),
        )
    }

    @Test
    fun `offline startup can enter cached profile gate`() {
        assertEquals(
            AppGateTransition.EnterProfileGate,
            appGateTransition(
                currentScreen = AppGateScreen.Loading,
                authState = AuthState.Unauthenticated,
                hasCachedProfiles = true,
                networkCondition = NetworkCondition.NoInternet,
            ),
        )
    }

    @Test
    fun `startup without profiles preserves loading and auth gates`() {
        assertEquals(
            AppGateTransition.ShowLoading,
            appGateTransition(
                currentScreen = AppGateScreen.Loading,
                authState = AuthState.Loading,
                hasCachedProfiles = false,
                networkCondition = NetworkCondition.Unknown,
            ),
        )
        assertEquals(
            AppGateTransition.ShowAuth,
            appGateTransition(
                currentScreen = AppGateScreen.Loading,
                authState = AuthState.Unauthenticated,
                hasCachedProfiles = false,
                networkCondition = NetworkCondition.Online,
            ),
        )
    }

    @Test
    fun `startup authentication enters profile gate`() {
        assertEquals(
            AppGateTransition.EnterProfileGate,
            appGateTransition(
                currentScreen = AppGateScreen.Loading,
                authState = AuthState.Authenticated(
                    userId = "user-id",
                    email = null,
                    isAnonymous = false,
                ),
                hasCachedProfiles = false,
                networkCondition = NetworkCondition.Online,
            ),
        )
    }
}
