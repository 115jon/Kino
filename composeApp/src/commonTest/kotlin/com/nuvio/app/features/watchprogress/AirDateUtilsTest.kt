package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals

class AirDateUtilsTest {
    @Test
    fun `formats full and compact air date labels without plural resolution`() {
        assertEquals(
            "Airs in 3 days",
            formatAirDateDaysLabel(
                daysUntil = 3,
                compact = false,
                fullFormat = "Airs in %1\$d days",
                compactFormat = "In %1\$d days",
            ),
        )
        assertEquals(
            "In 3 days",
            formatAirDateDaysLabel(
                daysUntil = 3,
                compact = true,
                fullFormat = "Airs in %1\$d days",
                compactFormat = "In %1\$d days",
            ),
        )
    }
}
