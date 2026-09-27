package cl.erz.sailer.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
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
    private var loadingAnimation: Animator? = null

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
                return
            }
            if (Uri.parse(url).host?.endsWith(UCURSOS_HOST) != true) {
                recoveryAttempts = 0
                showContent()
                return
            }
            // An expired session doesn't always redirect to /login: u-cursos.cl's
            // root just renders the public (logged-out) frontpage instead. So
            // inspect the page itself before accepting it as a logged-in view.
            view.evaluateJavascript(LOGGED_OUT_CHECK_JS) { value ->
                // Ignore stale results from a page we've since navigated away
                // from, or that arrive while a recovery login is in progress.
                if (isRecovering || view.url != url) return@evaluateJavascript
                if (value == "true") {
                    handleRecoverableFailure(networkRelated = false)
                } else {
                    recoveryAttempts = 0
                    showContent()
                }
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

    // The WebView is kept hidden (INVISIBLE, not GONE, so it stays laid out and
    // UCursosAuthenticator can still drive it) until a page is confirmed to be
    // a logged-in view, so the logged-out frontpage and the relogin flow never
    // flash on screen.
    private fun showLoading() {
        binding.webView.visibility = View.INVISIBLE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.offlineContainer.visibility = View.GONE
        startLoadingAnimation()
    }

    private fun showContent() {
        stopLoadingAnimation()
        binding.loadingIndicator.visibility = View.GONE
        binding.offlineContainer.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
    }

    private fun showOffline() {
        stopLoadingAnimation()
        binding.loadingIndicator.visibility = View.GONE
        binding.offlineContainer.visibility = View.VISIBLE
    }

    // A sailboat rocking and bobbing on a strip of waves that scrolls sideways.
    private fun startLoadingAnimation() {
        if (loadingAnimation != null) return
        val boat = binding.loadingBoat
        val waves = binding.loadingWaves
        val density = resources.displayMetrics.density
        boat.post {
            boat.pivotX = boat.width / 2f
            boat.pivotY = boat.height.toFloat()
        }

        fun ObjectAnimator.swing(durationMs: Long) = apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        val rock = ObjectAnimator.ofFloat(boat, View.ROTATION, -BOAT_ROCK_DEGREES, BOAT_ROCK_DEGREES).swing(1_400L)
        val bob = ObjectAnimator.ofFloat(boat, View.TRANSLATION_Y, 0f, BOAT_BOB_DP * density).swing(900L)
        // Scrolling by exactly one wave period (see loading_waves.xml) loops seamlessly.
        val sail = ObjectAnimator.ofFloat(waves, View.TRANSLATION_X, 0f, -WAVE_PERIOD_DP * density).apply {
            duration = 1_200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        loadingAnimation = AnimatorSet().apply {
            playTogether(rock, bob, sail)
            start()
        }
    }

    private fun stopLoadingAnimation() {
        loadingAnimation?.cancel()
        loadingAnimation = null
    }

    override fun onDestroy() {
        stopLoadingAnimation()
        super.onDestroy()
    }

    private fun hideOffline() {
        binding.offlineContainer.visibility = View.GONE
    }

    companion object {
        private const val HOME_URL = "https://www.u-cursos.cl/"
        private const val LOGIN_PATH = "/login"
        private const val UCURSOS_HOST = "u-cursos.cl"

        // Logged-out heuristic: the page offers a way to log in (a link/form
        // pointing at /login or the Cuenta Uchile auth endpoint, or a password
        // field) and no way to log out.
        // Requiring the absence of a logout link keeps logged-in pages that
        // happen to mention /login from being misdetected.
        private const val LOGGED_OUT_CHECK_JS = """
            (function() {
                var els = document.querySelectorAll('a[href], form[action]');
                var hasLogin = false, hasLogout = false;
                for (var i = 0; i < els.length; i++) {
                    var t = (els[i].getAttribute('href') || els[i].getAttribute('action') || '').toLowerCase();
                    if (t.indexOf('logout') >= 0 || t.indexOf('/salir') >= 0) hasLogout = true;
                    // The logged-out frontpage's "Entrar con Cuenta Uchile"
                    // button is a form posting to /b/auth/api.
                    else if (t.indexOf('/login') >= 0 || t.indexOf('/auth/api') >= 0) hasLogin = true;
                }
                if (hasLogout) return false;
                return hasLogin || document.querySelector('input[type=password]') !== null;
            })();
        """
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val MAX_BACKOFF_MS = 10_000L

        private const val BOAT_ROCK_DEGREES = 8f
        private const val BOAT_BOB_DP = 4f
        private const val WAVE_PERIOD_DP = 40f
    }
}
