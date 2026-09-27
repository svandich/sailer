package cl.erz.sailer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cl.erz.sailer.auth.LoginResult
import cl.erz.sailer.auth.SecureCredentialStore
import cl.erz.sailer.auth.UCursosAuthenticator
import cl.erz.sailer.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow

/**
 * Hosts the real u-cursos.cl site in a WebView, same as the original app.
 * The addition here: if the page fails to load (network error) or we get
 * bounced back to /login (expired session), it automatically retries the
 * login using the credentials [SecureCredentialStore] saved on a previous
 * successful login, with a few backed-off attempts, before giving up and
 * either showing an offline screen (network problem) or sending the user to
 * [LoginActivity] (the saved credentials themselves no longer work).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var credentialStore: SecureCredentialStore

    private var recoveryAttempts = 0
    private var isRecovering = false
    private var lastFailureWasNetwork = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        credentialStore = SecureCredentialStore(this)
        if (!credentialStore.hasSavedCredentials()) {
            goToLogin(sessionExpired = false)
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureWebView()
        binding.retryButton.setOnClickListener {
            recoveryAttempts = 0
            hideOffline()
            loadHome()
        }

        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }

        loadHome()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = mainWebViewClient()
    }

    private fun mainWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            if (LOGIN_PATH in url) {
                handleRecoverableFailure(networkRelated = false)
            } else {
                recoveryAttempts = 0
                showContent()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) handleRecoverableFailure(networkRelated = true)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                handleRecoverableFailure(networkRelated = true)
            }
        }
    }

    private fun loadHome() {
        showLoading()
        binding.webView.loadUrl(HOME_URL)
    }

    private fun handleRecoverableFailure(networkRelated: Boolean) {
        if (isRecovering) return
        lastFailureWasNetwork = networkRelated

        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            if (lastFailureWasNetwork) showOffline() else goToLogin(sessionExpired = true)
            return
        }

        val credentials = credentialStore.read()
        if (credentials == null) {
            goToLogin(sessionExpired = true)
            return
        }

        isRecovering = true
        recoveryAttempts++
        showLoading()

        lifecycleScope.launch {
            kotlinx.coroutines.delay(backoffDelayMs(recoveryAttempts))
            val result = UCursosAuthenticator(binding.webView).login(credentials.username, credentials.password)
            isRecovering = false
            // UCursosAuthenticator takes over webView's client while it drives the
            // login; hand control back to MainActivity's own client before doing
            // anything else with the WebView.
            binding.webView.webViewClient = mainWebViewClient()
            when (result) {
                is LoginResult.Success -> loadHome()
                is LoginResult.NetworkError -> handleRecoverableFailure(networkRelated = true)
                is LoginResult.InvalidCredentials -> goToLogin(sessionExpired = true)
                is LoginResult.Unexpected -> goToLogin(sessionExpired = true)
            }
        }
    }

    private fun backoffDelayMs(attempt: Int): Long =
        (INITIAL_BACKOFF_MS * 2.0.pow(attempt - 1)).toLong().let { min(it, MAX_BACKOFF_MS) }

    private fun goToLogin(sessionExpired: Boolean) {
        val username = credentialStore.read()?.username
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_SESSION_EXPIRED, sessionExpired)
                .putExtra(LoginActivity.EXTRA_PREFILL_USERNAME, username)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun showLoading() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.offlineContainer.visibility = View.GONE
    }

    private fun showContent() {
        binding.loadingIndicator.visibility = View.GONE
        binding.offlineContainer.visibility = View.GONE
    }

    private fun showOffline() {
        binding.loadingIndicator.visibility = View.GONE
        binding.offlineContainer.visibility = View.VISIBLE
    }

    private fun hideOffline() {
        binding.offlineContainer.visibility = View.GONE
    }

    companion object {
        private const val HOME_URL = "https://www.u-cursos.cl/"
        private const val LOGIN_PATH = "/login"
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val MAX_BACKOFF_MS = 10_000L
    }
}
