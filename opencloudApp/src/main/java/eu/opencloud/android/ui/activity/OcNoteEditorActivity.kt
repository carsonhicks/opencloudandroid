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

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import eu.opencloud.android.BuildConfig
import eu.opencloud.android.R
import eu.opencloud.android.domain.files.model.OCFile
import eu.opencloud.android.domain.spaces.usecases.GetSpaceByIdForAccountUseCase
import eu.opencloud.android.lib.common.accounts.AccountUtils
import eu.opencloud.android.lib.common.accounts.AccountTypeUtils
import eu.opencloud.android.providers.CoroutinesDispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.ByteArrayInputStream

/**
 * Edits an .ocnote file with the web app's own text editor (real-time collaboration included), hosted in a WebView.
 *
 * The server only allows framing from its own origin, so a small host page is served from the server origin by
 * intercepting requests (nothing is fetched for it). The host page embeds the editor route and hands it the
 * account's access token through the web app's "embed mode" protocol, so the user does not log in a second time.
 */
class OcNoteEditorActivity : AppCompatActivity() {

    private val getSpaceByIdForAccountUseCase: GetSpaceByIdForAccountUseCase by inject()
    private val dispatchers: CoroutinesDispatcherProvider by inject()

    private lateinit var webView: WebView
    private lateinit var messageView: TextView
    private lateinit var account: Account
    private lateinit var baseUrl: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME)
        val remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH)
        val spaceId = intent.getStringExtra(EXTRA_SPACE_ID)
        val resolvedAccount = accountName?.let { eu.opencloud.android.presentation.authentication.AccountUtils.getOpenCloudAccountByName(this, it) }
        if (resolvedAccount == null || remotePath == null) {
            finish()
            return
        }
        account = resolvedAccount
        baseUrl = AccountUtils.getBaseUrlForAccount(this, account).trimEnd('/')

        messageView = TextView(this).apply {
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        webView = WebView(this)
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            filterTouchesWhenObscured = true
            addView(webView, FrameLayout.LayoutParams(-1, -1))
            addView(messageView, FrameLayout.LayoutParams(-1, -1))
        })

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Cookies/HTTP cache are not needed for the delegated login
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Let the web editor close itself (it deals with unsaved changes); the host page then
                // asks us to finish
                webView.evaluateJavascript("window.__ocBack ? window.__ocBack() : (location.href='/$CLOSE_PATH')", null)
            }
        })
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.webViewClient = EditorWebViewClient()

        lifecycleScope.launch {
            val route = withContext(dispatchers.io) { buildEditorRoute(spaceId, remotePath) }
            if (route == null) {
                showMessage(getString(R.string.ocnote_editor_unsupported_location))
                return@launch
            }
            val iframeSrc = "/?embed=true&embed-delegate-authentication=true"
            val host = Uri.parse("$baseUrl/$HOST_PATH").buildUpon()
                .appendQueryParameter("src", iframeSrc)
                .appendQueryParameter("route", route)
                .build()
            webView.loadUrl(host.toString())
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? FrameLayout)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun showMessage(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    /** Web route of the editor for this file, or null when it can't be addressed (e.g. inside a share). */
    private fun buildEditorRoute(spaceId: String?, remotePath: String): String? {
        val alias = getSpaceByIdForAccountUseCase(
            GetSpaceByIdForAccountUseCase.Params(accountName = account.name, spaceId = spaceId)
        )?.driveAlias ?: return null
        val encodedPath = remotePath.split('/').joinToString("/") { Uri.encode(it) }
        return "/text-editor/$alias${if (encodedPath.startsWith("/")) "" else "/"}$encodedPath"
    }

    private inner class EditorWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (!url.toString().startsWith(baseUrl)) return null
            return when (url.path) {
                "/$HOST_PATH" -> response("text/html", readHostPage())
                "/$TOKEN_PATH" -> response("application/json", currentTokenJson())
                else -> null
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.toString().startsWith(baseUrl)) {
                if (url.path == "/$CLOSE_PATH") {
                    finish()
                    return true
                }
                return false
            }
            // Anything else opens outside the app
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            return true
        }
    }

    private fun response(mimeType: String, body: String) = WebResourceResponse(
        mimeType, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(body.toByteArray())
    )

    private fun readHostPage(): String =
        resources.openRawResource(R.raw.ocnote_editor_host).bufferedReader().use { it.readText() }

    /** Runs on a WebView IO thread. Refreshes the access token if it is about to expire. */
    private fun currentTokenJson(): String = try {
        val token = freshAccessToken()
        JSONObject().put("access_token", token).apply {
            jwtClaim(token, "sid")?.let { put("session_id", it) }
        }.toString()
    } catch (e: Exception) {
        Timber.e(e, "Could not get an access token for the editor")
        JSONObject().put("error", getString(R.string.ocnote_editor_login_failed)).toString()
    }

    private fun freshAccessToken(): String {
        var token = AccountUtils.getCredentialsForAccount(this, account).authToken
        val expiresIn = jwtClaim(token, "exp")?.toLongOrNull()?.minus(System.currentTimeMillis() / 1000)
        if (expiresIn != null && expiresIn < TOKEN_MIN_REMAINING_SECONDS) {
            AccountManager.get(this).invalidateAuthToken(
                AccountTypeUtils.getAuthTokenTypeAccessToken(account.type), token
            )
            token = AccountUtils.getCredentialsForAccount(this, account).authToken
        }
        require(!token.isNullOrEmpty()) { "No token" }
        return token
    }

    private fun jwtClaim(jwt: String, claim: String): String? = runCatching {
        val payload = jwt.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(json).opt(claim)?.toString()
    }.getOrNull()

    companion object {
        private const val EXTRA_ACCOUNT_NAME = "ACCOUNT_NAME"
        private const val EXTRA_REMOTE_PATH = "REMOTE_PATH"
        private const val EXTRA_SPACE_ID = "SPACE_ID"
        private const val HOST_PATH = "android-editor-host.html"
        private const val TOKEN_PATH = "android-editor-token"
        private const val CLOSE_PATH = "android-editor-close"
        private const val TOKEN_MIN_REMAINING_SECONDS = 60

        fun canEdit(file: OCFile?): Boolean = file?.remotePath?.endsWith(".ocnote", ignoreCase = true) == true

        fun newIntent(context: Context, accountName: String, file: OCFile): Intent =
            Intent(context, OcNoteEditorActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_NAME, accountName)
                .putExtra(EXTRA_REMOTE_PATH, file.remotePath)
                .putExtra(EXTRA_SPACE_ID, file.spaceId)
    }
}
