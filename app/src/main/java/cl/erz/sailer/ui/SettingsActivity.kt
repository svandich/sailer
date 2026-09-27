package cl.erz.sailer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import cl.erz.sailer.R
import cl.erz.sailer.databinding.ActivitySettingsBinding
import cl.erz.sailer.settings.AppSettings

/**
 * Language and theme preferences, opened from MainActivity's drawer - the
 * native counterpart of the original app's PreferencesActivity. Changes are
 * applied natively right away (which recreates the open activities);
 * MainActivity pushes them to u-cursos.cl itself on its next page load.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<ListPreference>(AppSettings.KEY_THEME)?.setOnPreferenceChangeListener { _, value ->
                AppSettings.applyTheme(value as String)
                true
            }

            findPreference<ListPreference>(AppSettings.KEY_LANGUAGE)?.apply {
                // The language itself is stored by AppCompat (see AppSettings.language());
                // this preference only mirrors it.
                isPersistent = false
                this.value = AppSettings.language()
                setOnPreferenceChangeListener { _, value ->
                    AppSettings.applyLanguage(value as String)
                    true
                }
            }
        }
    }
}
