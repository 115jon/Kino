package com.nuvio.app

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.NetworkCondition

internal enum class AppGateScreen {
    Loading,
    Auth,
    ProfileSelection,
    ProfileEdit,
    Main,
}

internal enum class AppGateTransition {
    KeepCurrent,
    ShowLoading,
    ShowAuth,
    EnterProfileGate,
}

internal fun appGateTransition(
    currentScreen: AppGateScreen,
    authState: AuthState,
    hasCachedProfiles: Boolean,
    networkCondition: NetworkCondition,
): AppGateTransition {
    val allowCachedProfileAccess = hasCachedProfiles &&
        (
            networkCondition != NetworkCondition.Online ||
                currentScreen != AppGateScreen.Auth
        )

    return when (authState) {
        AuthState.Loading -> {
            if (currentScreen == AppGateScreen.Loading || currentScreen == AppGateScreen.Auth) {
                if (hasCachedProfiles) AppGateTransition.EnterProfileGate else AppGateTransition.ShowLoading
            } else {
                AppGateTransition.KeepCurrent
            }
        }

        AuthState.Unauthenticated -> {
            if (!allowCachedProfileAccess) {
                AppGateTransition.ShowAuth
            } else if (currentScreen == AppGateScreen.Loading || currentScreen == AppGateScreen.Auth) {
                AppGateTransition.EnterProfileGate
            } else {
                AppGateTransition.KeepCurrent
            }
        }

        is AuthState.Authenticated -> {
            if (currentScreen == AppGateScreen.Loading || currentScreen == AppGateScreen.Auth) {
                AppGateTransition.EnterProfileGate
            } else {
                AppGateTransition.KeepCurrent
            }
        }
    }
}
