/**
 * openCloud Android client application
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.opencloud.android.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import eu.opencloud.android.R
import eu.opencloud.android.domain.files.model.OCFile
import eu.opencloud.android.ocnote.OcNoteHtmlRenderer
import eu.opencloud.android.providers.CoroutinesDispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File

/**
 * Read-only, best-effort view of the local copy of an .ocnote file, for when the web editor can't be used
 * (offline). The note is converted to sanitized HTML and shown in a WebView with JavaScript and network off.
 */
class OcNoteViewerActivity : AppCompatActivity() {

    private val dispatchers: CoroutinesDispatcherProvider by inject()

    private lateinit var webView: WebView
    private lateinit var messageView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_LOCAL_PATH)
        if (path == null) {
            finish()
            return
        }
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = false
                blockNetworkLoads = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Links only open outside the app (the page itself never navigates)
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
        }
        messageView = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val toolbar = Toolbar(ContextThemeWrapper(this, R.style.openCloud_Appbar)).apply {
            setBackgroundColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
            title = intent.getStringExtra(EXTRA_NAME)
            subtitle = getString(R.string.ocnote_viewer_subtitle)
        }
        val content = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(-1, -1))
            addView(messageView, FrameLayout.LayoutParams(-1, -1))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            filterTouchesWhenObscured = true
            setBackgroundColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Edge-to-edge: keep the toolbar below the status bar and the note above the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            content.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        lifecycleScope.launch {
            val html = withContext(dispatchers.io) { loadHtml(File(path), dark) }
            if (html != null) {
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } else {
                messageView.text = getString(R.string.ocnote_viewer_failed)
                messageView.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun loadHtml(file: File, dark: Boolean): String? = try {
        if (file.length() > MAX_BYTES) {
            null
        } else {
            OcNoteHtmlRenderer.render(file.readText(), dark)
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not render note")
        null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? FrameLayout)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LOCAL_PATH = "LOCAL_PATH"
        private const val EXTRA_NAME = "NAME"
        private const val MAX_BYTES = 10L * 1024 * 1024

        fun newIntent(context: Context, file: OCFile): Intent =
            Intent(context, OcNoteViewerActivity::class.java)
                .putExtra(EXTRA_LOCAL_PATH, file.storagePath)
                .putExtra(EXTRA_NAME, file.fileName)
    }
}
