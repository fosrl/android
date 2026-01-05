package net.pangolin.Pangolin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.pangolin.Pangolin.databinding.ActivitySignInCodeBinding

class SignInCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInCodeBinding
    private val signInCode = "VC2W-GWCG" // This would normally come from your authentication flow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySignInCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "Sign In"

        // Set the code text
        displayCode(signInCode)

        // Setup back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Setup copy code button
        binding.copyCodeButton.setOnClickListener {
            copyCodeToClipboard()
        }

        // Setup open login page button
        binding.openLoginButton.setOnClickListener {
            openLoginPage()
        }
    }

    private fun displayCode(code: String) {
        // Split code into individual characters
        val codeChars = code.replace("-", "").toCharArray()
        
        val codeViews = listOf(
            binding.codeChar1,
            binding.codeChar2,
            binding.codeChar3,
            binding.codeChar4,
            binding.codeChar5,
            binding.codeChar6,
            binding.codeChar7,
            binding.codeChar8,
            binding.codeChar9
        )

        codeChars.forEachIndexed { index, char ->
            if (index < codeViews.size) {
                codeViews[index].text = char.toString()
            }
        }
    }

    private fun copyCodeToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Sign In Code", signInCode)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun openLoginPage() {
        val url = "https://app.pangolin.net/auth/login/device"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}