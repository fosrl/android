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

    /** An installed app the user can pick; icons are kept by the UI. */
    data class InstalledApp(val packageName: String, val label: String)

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

    /** Case-insensitive match on label or package name; order is preserved. */
    fun filter(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
    }
}
