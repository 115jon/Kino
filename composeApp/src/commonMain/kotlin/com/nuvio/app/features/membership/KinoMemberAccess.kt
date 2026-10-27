package com.nuvio.app.features.membership

internal val KinoMemberAccess = MemberAccess(
    tier = MemberTier.SUPPORTER,
    entitlements = CosmeticEntitlements(
        setOf(
            CosmeticEntitlement.GOLD_THEME,
            CosmeticEntitlement.JADE_THEME,
            CosmeticEntitlement.ROSE_GOLD_THEME,
            CosmeticEntitlement.ARCTIC_BLUE_THEME,
            CosmeticEntitlement.GRAPHITE_THEME,
        ),
    ),
)
