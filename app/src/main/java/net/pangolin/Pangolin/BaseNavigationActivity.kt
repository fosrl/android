package net.pangolin.Pangolin

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView

/**
 * Base activity that provides consistent drawer navigation across all activities.
 * Subclasses must call setupNavigation() after setting their content view.
 */
abstract class BaseNavigationActivity : AppCompatActivity() {

    protected lateinit var drawerLayout: DrawerLayout
    protected lateinit var navView: NavigationView
    protected lateinit var toolbar: MaterialToolbar
    private lateinit var toggle: ActionBarDrawerToggle

    /**
     * Returns the menu item ID that should be selected in this activity
     */
    protected abstract fun getSelectedNavItemId(): Int

    /**
     * Sets up the navigation drawer with consistent behavior.
     * Must be called after setContentView() in the subclass.
     */
    protected fun setupNavigation(
        drawerLayout: DrawerLayout,
        navView: NavigationView,
        toolbar: MaterialToolbar
    ) {
        this.drawerLayout = drawerLayout
        this.navView = navView
        this.toolbar = toolbar

        setSupportActionBar(toolbar)

        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { menuItem ->
            handleNavigationItemSelected(menuItem.itemId)
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        navView.setCheckedItem(getSelectedNavItemId())

        setupBackPressHandler()
    }

    /**
     * Handles navigation item selection with consistent behavior
     */
    private fun handleNavigationItemSelected(itemId: Int) {
        when (itemId) {
            R.id.nav_main -> {
                if (this !is MainActivity) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
            R.id.nav_status -> {
                if (this !is StatusActivity) {
                    startActivity(Intent(this, StatusActivity::class.java))
                    finish()
                }
            }
            R.id.nav_settings -> {
                if (this !is SettingsActivity) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
            }
            R.id.nav_about -> {
                if (this !is AboutActivity) {
                    startActivity(Intent(this, AboutActivity::class.java))
                    finish()
                }
            }
        }
    }

    /**
     * Sets up consistent back press handling for the drawer
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Utility method to open the drawer programmatically
     */
    protected fun openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    /**
     * Utility method to close the drawer programmatically
     */
    protected fun closeDrawer() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }
}
