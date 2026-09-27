package cl.erz.sailer.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives a real [WebView] through u-cursos.cl's own `/login` page so that
 * whatever the site actually requires to authenticate (CSRF tokens, hidden
 * fields, redirects) runs completely unmodified. This class never talks to
 * the login endpoint directly - it loads the real page and fills in the
 * fields the way a user would, then submits the real form.
 *
 * The field selectors below are intentionally generic (first password field
 * on the page, plus the most plausible username/email field in the same
 * form) rather than hard-coded to specific `name`/`id` attributes, because
 * this project was built from a decompiled Android client with no live
 * access to u-cursos.cl to confirm the exact markup of its login form.
 * Verify this against the real login page on a device and adjust
 * [FILL_AND_SUBMIT_JS] if the site's form doesn't match these assumptions.
 *
 * All public methods must be called from the main thread (WebView
 * requirement); calling from `lifecycleScope.launch { }`, which defaults to
 * `Dispatchers.Main`, satisfies this. A single instance is meant to be used
 * for one [login] call.
 */
class UCursosAuthenticator(private val webView: WebView) {

    // Set by whichever WebViewClient is currently attached; read by the
    // polling loop below. Everything here runs on the main thread, so there
    // is no real concurrency between the callback writes and the reads.
    private var mainFrameErrorSeen = false

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun login(username: String, password: String): LoginResult {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)

        // Start from a clean slate so a half-expired session cookie can't
        // interfere with (or fake-succeed) a fresh login attempt.
        CookieManager.getInstance().removeAllCookies(null)
        webView.clearCache(true)
        webView.clearHistory()

        mainFrameErrorSeen = false
        val loaded = loadAndAwait(LOGIN_URL)
        if (!loaded || mainFrameErrorSeen) return LoginResult.NetworkError

        // Attach the error watcher that will stay active through the submit
        // + redirect before starting the submission, so nothing during that
        // window can slip past unobserved.
        webView.webViewClient = errorWatchingClient()

        // u-cursos.cl/login no longer hosts a credentials form itself - it's
        // a gateway page (policy-acceptance checkbox(es) + a continue button)
        // that redirects to a separate identity provider, where the actual
        // username/password fields live. If there's no password field yet,
        // click through that gateway once before looking for one.
        if (!hasPasswordField()) {
            val previousUrl = webView.url
            if (!acceptAndContinue()) {
                return LoginResult.Unexpected("No se encontró el formulario de inicio de sesión en la página")
            }
            waitForNextPage(previousUrl)
            if (mainFrameErrorSeen) return LoginResult.NetworkError
        }

        val submitted = fillAndSubmit(username, password)
        if (!submitted) {
            return LoginResult.Unexpected("No se encontró el formulario de inicio de sesión en la página")
        }

        return awaitOutcome()
    }

    private suspend fun loadAndAwait(url: String): Boolean {
        val deferred = CompletableDeferred<Unit>()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                if (!deferred.isCompleted) deferred.complete(Unit)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameErrorSeen = true
                    if (!deferred.isCompleted) deferred.complete(Unit)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    mainFrameErrorSeen = true
                    if (!deferred.isCompleted) deferred.complete(Unit)
                }
            }
        }
        webView.loadUrl(url)
        return withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } != null
    }

    // Poll for the outcome instead of relying only on onPageFinished, since
    // the login form may either do a full-page POST (which navigates,
    // triggering onPageFinished) or submit via JS/fetch (which may not).
    // The password field disappearing is the success signal - not a URL
    // pattern, since the real credentials form may live on a separate
    // identity-provider domain reached via [acceptAndContinue].
    private suspend fun awaitOutcome(): LoginResult {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (mainFrameErrorSeen) return LoginResult.NetworkError

            if (!hasPasswordField()) {
                // Let any last redirect settle, then confirm we really left the login form.
                delay(SETTLE_DELAY_MS)
                if (mainFrameErrorSeen) return LoginResult.NetworkError
                CookieManager.getInstance().flush()
                return LoginResult.Success
            }

            delay(POLL_INTERVAL_MS)
        }

        return if (mainFrameErrorSeen) LoginResult.NetworkError else LoginResult.InvalidCredentials
    }

    // Waits for navigation away from [previousUrl] (triggered by
    // [acceptAndContinue]'s click), then waits for a password field to show
    // up on the destination page - which may itself be gated behind an
    // async bot-check (e.g. Cloudflare) before rendering the real form.
    private suspend fun waitForNextPage(previousUrl: String?) {
        withTimeoutOrNull(TIMEOUT_MS) {
            while (webView.url == previousUrl) delay(POLL_INTERVAL_MS)
        }
        withTimeoutOrNull(TIMEOUT_MS) {
            while (!hasPasswordField() && !mainFrameErrorSeen) delay(POLL_INTERVAL_MS)
        }
        // Extra settle time for an async bot-check to finish issuing its
        // token even after the form fields themselves are already visible.
        delay(SETTLE_DELAY_MS)
    }

    private fun errorWatchingClient(): WebViewClient = object : WebViewClient() {
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) mainFrameErrorSeen = true
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) mainFrameErrorSeen = true
        }

        // Deliberately not overriding onReceivedSslError: the default
        // behavior (cancel the connection) is the secure one, and we never
        // want to bypass a certificate error to push saved credentials
        // through.
    }

    private suspend fun fillAndSubmit(username: String, password: String): Boolean {
        val script = FILL_AND_SUBMIT_JS.format(jsEscape(username), jsEscape(password))
        val deferred = CompletableDeferred<Boolean>()
        webView.evaluateJavascript(script) { value -> deferred.complete(value == "true") }
        return withTimeoutOrNull(EVAL_JS_TIMEOUT_MS) { deferred.await() } ?: false
    }

    // Checks any consent checkbox(es) present and clicks the page's submit
    // control, for gateway pages that require accepting terms before
    // continuing to the real identity provider (see [login]).
    private suspend fun acceptAndContinue(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        webView.evaluateJavascript(ACCEPT_AND_CONTINUE_JS) { value -> deferred.complete(value == "true") }
        return withTimeoutOrNull(EVAL_JS_TIMEOUT_MS) { deferred.await() } ?: false
    }

    private suspend fun hasPasswordField(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        webView.evaluateJavascript(HAS_PASSWORD_FIELD_JS) { value -> deferred.complete(value == "true") }
        return withTimeoutOrNull(EVAL_JS_TIMEOUT_MS) { deferred.await() } ?: true
    }

    private fun jsEscape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val LOGIN_URL = "https://www.u-cursos.cl/login"
        private const val TIMEOUT_MS = 20_000L
        private const val EVAL_JS_TIMEOUT_MS = 5_000L
        private const val SETTLE_DELAY_MS = 800L
        private const val POLL_INTERVAL_MS = 500L

        private const val HAS_PASSWORD_FIELD_JS =
            "(function(){return document.querySelector('input[type=password]') !== null;})();"

        private const val ACCEPT_AND_CONTINUE_JS = """
            (function() {
                var checkboxes = document.querySelectorAll('input[type=checkbox]');
                for (var i = 0; i < checkboxes.length; i++) {
                    if (!checkboxes[i].checked) checkboxes[i].click();
                }
                var btn = document.querySelector('button[type=submit], input[type=submit]');
                if (!btn) return false;
                btn.click();
                return true;
            })();
        """

        private val FILL_AND_SUBMIT_JS = """
            (function() {
                function setValue(el, value) {
                    var proto = Object.getPrototypeOf(el);
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (setter && setter.set) { setter.set.call(el, value); } else { el.value = value; }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                var passwordField = document.querySelector('input[type=password]');
                if (!passwordField) return false;
                var form = passwordField.closest('form');
                if (!form) return false;
                var userField = form.querySelector(
                    'input[type=email], input[name*=user i], input[name*=mail i], ' +
                    'input[name*=login i], input[type=text]'
                );
                if (!userField) return false;
                setValue(userField, '%s');
                setValue(passwordField, '%s');
                var submitBtn = form.querySelector('button[type=submit], input[type=submit]');
                if (submitBtn) { submitBtn.click(); }
                else if (form.requestSubmit) { form.requestSubmit(); }
                else { form.submit(); }
                return true;
            })();
        """.trimIndent()
    }
}
