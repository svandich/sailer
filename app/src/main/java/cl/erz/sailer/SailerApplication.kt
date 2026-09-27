package cl.erz.sailer

import android.app.Application
import android.webkit.WebView
import cl.erz.sailer.settings.AppSettings

class SailerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Night mode isn't persisted by AppCompat (unlike the app language), so re-apply it every launch.
        AppSettings.applyTheme(AppSettings.theme(this))
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
