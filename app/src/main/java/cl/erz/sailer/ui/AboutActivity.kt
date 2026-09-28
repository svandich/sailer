package cl.erz.sailer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cl.erz.sailer.BuildConfig
import cl.erz.sailer.R
import cl.erz.sailer.databinding.ActivityAboutBinding

/**
 * App version plus the links from u-cursos.cl's page footer (Políticas de
 * Uso, Privacidad, Accesibilidad), which MainActivity hides. The links open
 * in MainActivity's WebView, like any other site page.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.version.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        for ((title, icon, url) in FOOTER_LINKS) {
            val row = layoutInflater.inflate(R.layout.item_about_link, binding.links, false) as TextView
            row.setText(title)
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            row.setOnClickListener {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_URL, url)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
            binding.links.addView(row)
        }
    }

    private companion object {
        // Same targets as the footer's links.
        val FOOTER_LINKS = listOf(
            Triple(R.string.about_terms, R.drawable.ic_terms, "https://www.u-cursos.cl/m/paginas/terms"),
            Triple(R.string.about_privacy, R.drawable.ic_privacy, "https://www.u-cursos.cl/m/paginas/privacy"),
            Triple(R.string.about_accessibility, R.drawable.ic_accessibility, "https://www.u-cursos.cl/m/paginas/a11y"),
        )
    }
}
