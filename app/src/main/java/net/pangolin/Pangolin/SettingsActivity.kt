package net.pangolin.Pangolin

import android.os.Bundle
import android.view.ViewGroup
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import net.pangolin.Pangolin.databinding.SettingsActivityBinding

class SettingsActivity : BaseNavigationActivity() {

    private lateinit var binding: SettingsActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup navigation using base class
        setupNavigation(binding.drawerLayout, binding.navView, binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_settings
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is EditTextPreference) {
                // Create Material3 text input
                val textInputLayout = TextInputLayout(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                    hint = preference.title
                    setPadding(
                        resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal),
                        resources.getDimensionPixelSize(R.dimen.dialog_padding_vertical),
                        resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal),
                        0
                    )
                }
                
                val editText = TextInputEditText(textInputLayout.context).apply {
                    setText(preference.text)
                }
                
                textInputLayout.addView(editText)

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(preference.title)
                    .setView(textInputLayout)
                    .setPositiveButton("OK") { _, _ ->
                        val newValue = editText.text.toString()
                        if (preference.callChangeListener(newValue)) {
                            preference.text = newValue
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }
    }
}