package com.sailer.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sailer.app.R
import com.sailer.app.auth.LoginResult
import com.sailer.app.auth.SecureCredentialStore
import com.sailer.app.auth.UCursosAuthenticator
import com.sailer.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

/**
 * Sailer's own native login form. It never talks to u-cursos.cl directly -
 * it hands the entered username/password to [UCursosAuthenticator], which
 * drives a hidden WebView through the real login page. On success, the
 * credentials are saved with [SecureCredentialStore] so [MainActivity] can
 * retry a login automatically later without asking again.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var credentialStore: SecureCredentialStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        credentialStore = SecureCredentialStore(this)

        val prefillUsername = intent.getStringExtra(EXTRA_PREFILL_USERNAME)
            ?: credentialStore.read()?.username
        if (!prefillUsername.isNullOrEmpty()) {
            binding.usernameField.setText(prefillUsername)
        }
        if (intent.getBooleanExtra(EXTRA_SESSION_EXPIRED, false)) {
            binding.sessionExpiredBanner.visibility = android.view.View.VISIBLE
        }

        binding.loginButton.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val username = binding.usernameField.text?.toString()?.trim().orEmpty()
        val password = binding.passwordField.text?.toString().orEmpty()

        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.error_empty_fields))
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = UCursosAuthenticator(binding.authWebView).login(username, password)
            setLoading(false)
            when (result) {
                is LoginResult.Success -> {
                    credentialStore.save(username, password)
                    startActivity(
                        Intent(this@LoginActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                }

                is LoginResult.InvalidCredentials ->
                    showError(getString(R.string.error_invalid_credentials))

                is LoginResult.NetworkError ->
                    showError(getString(R.string.error_network))

                is LoginResult.Unexpected ->
                    showError(getString(R.string.error_unknown))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loginProgress.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.loginButton.isEnabled = !loading
        binding.usernameField.isEnabled = !loading
        binding.passwordField.isEnabled = !loading
        if (loading) binding.errorText.visibility = android.view.View.GONE
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = android.view.View.VISIBLE
    }

    companion object {
        const val EXTRA_PREFILL_USERNAME = "extra_prefill_username"
        const val EXTRA_SESSION_EXPIRED = "extra_session_expired"
    }
}
