package net.pangolin.Pangolin.util

/** Decides which apps bypass the VPN interface for a given configuration. */
internal object ExcludedApplications {
    // Android Auto's projection app; must also be listed in the manifest's <queries>.
    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

    fun forConfig(config: Config): List<String> =
        if (config.excludeAndroidAuto) listOf(ANDROID_AUTO_PACKAGE) else emptyList()
}
