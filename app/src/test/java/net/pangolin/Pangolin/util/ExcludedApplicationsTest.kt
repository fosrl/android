package net.pangolin.Pangolin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcludedApplicationsTest {
    @Test
    fun nothingIsExcludedByDefault() {
        assertTrue(ExcludedApplications.forConfig(Config()).isEmpty())
    }

    @Test
    fun disabledSettingExcludesNothing() {
        assertTrue(ExcludedApplications.forConfig(Config(excludeAndroidAuto = false)).isEmpty())
    }

    @Test
    fun enabledSettingExcludesAndroidAuto() {
        assertEquals(
            listOf(ExcludedApplications.ANDROID_AUTO_PACKAGE),
            ExcludedApplications.forConfig(Config(excludeAndroidAuto = true))
        )
    }
}
