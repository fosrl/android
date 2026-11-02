package net.pangolin.Pangolin.util

/** Decides which apps bypass the VPN interface. */
internal object ExcludedApplications {
    /** SharedPreferences key of the in-app exclusion list (a string set of package names). */
    const val PREF_KEY = "excludedApplications"

    /**
     * Packages to pass to VpnService.Builder.addDisallowedApplication(). Our own package is never
     * excluded, so the app's API traffic keeps following the tunnel like it always has.
     */
    fun forConfig(config: Config, ownPackage: String): List<String> =
        config.excludedApplications.filter { it.isNotBlank() && it != ownPackage }.sorted()

    /**
     * An installed app the user can pick; icons are kept by the UI. [isSystem] marks preinstalled
     * apps (including ones updated through the store, such as Android Auto).
     */
    data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean = false)

    /**
     * Excluded apps first so the current choice is visible at a glance, then alphabetical.
     * Excluded packages that aren't installed (or visible) any more are kept as rows labelled
     * by package name, so they can still be removed.
     */
    fun displayOrder(installed: List<InstalledApp>, excluded: Set<String>): List<InstalledApp> {
        val known = installed.mapTo(HashSet()) { it.packageName }
        val missing = excluded.filter { it !in known }.map { InstalledApp(it, it) }
        return (installed + missing).sortedWith(
            compareBy<InstalledApp> { it.packageName !in excluded }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName }
        )
    }

    /**
     * Case-insensitive match on label or package name; order is preserved. Hidden system apps
     * stay visible while they're excluded, so an active exclusion is never out of sight.
     */
    fun filter(
        apps: List<InstalledApp>,
        query: String,
        showSystem: Boolean = true,
        excluded: Set<String> = emptySet(),
    ): List<InstalledApp> {
        val q = query.trim()
        return apps.filter { app ->
            (showSystem || !app.isSystem || app.packageName in excluded) &&
                (q.isEmpty() || app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true))
        }
    }
}
