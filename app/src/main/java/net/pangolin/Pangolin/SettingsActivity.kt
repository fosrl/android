package net.pangolin.Pangolin

import android.os.Bundle
import android.view.ViewGroup
import android.text.InputType
import android.util.Patterns
import android.text.method.DigitsKeyListener
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

                // If DNS fields, restrict to numeric + decimal input (to allow dots) and validate
                val isDnsField = preference.key == "primaryDNSServer" || preference.key == "secondaryDNSServer"
                if (isDnsField) {
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.:")
                }
                
                textInputLayout.addView(editText)

                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(preference.title)
                    .setView(textInputLayout)
                    .setPositiveButton("OK", null) // we will override to keep the dialog open on invalid
                    .setNegativeButton("Cancel", null)
                    .create()

                dialog.setOnShowListener {
                    val okButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    okButton.setOnClickListener {
                        val newValue = editText.text?.toString()?.trim().orEmpty()
                        val isSecondary = preference.key == "secondaryDNSServer"
                        val isValid = if (isDnsField) {
                            if (isSecondary && newValue.isEmpty()) {
                                true // allow empty secondary DNS
                            } else {
                                Patterns.IP_ADDRESS.matcher(newValue).matches()
                            }
                        } else {
                            true
                        }

                        if (!isValid) {
                            textInputLayout.error = "Please enter a valid IP address"
                            textInputLayout.helperText = "Examples: 1.1.1.1, 8.8.4.4, or IPv6 like 2001:4860:4860::8888"
                        } else {
                            textInputLayout.error = null
                            textInputLayout.helperText = null
                            if (preference.callChangeListener(newValue)) {
                                preference.text = newValue
                                dialog.dismiss()
                            }
                        }
                    }
                }

                dialog.show()
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }
    }
}