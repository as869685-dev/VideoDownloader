package com.example.videodownloader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SITE = "site"

        fun cookieFile(ctx: Context) = File(ctx.filesDir, "cookies.txt")

        private val SITES = mapOf(
            "instagram" to Pair("https://www.instagram.com/accounts/login/", "https://www.instagram.com"),
            "facebook" to Pair("https://m.facebook.com/login/", "https://www.facebook.com")
        )
    }

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val site = intent.getStringExtra(EXTRA_SITE) ?: "instagram"
        val (loginUrl, cookieUrl) = SITES[site] ?: SITES.getValue("instagram")
        title = if (site == "facebook") "Facebook login" else "Instagram login"

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val doneBtn = Button(this).apply {
            text = "Login ho gaya, Save karein"
            setOnClickListener { saveCookies(site, cookieUrl) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView)
            addView(doneBtn)
        }
        setContentView(root)
        webView.loadUrl(loginUrl)
    }

    private fun saveCookies(site: String, cookieUrl: String) {
        val cm = CookieManager.getInstance()
        cm.flush()
        val raw = cm.getCookie(cookieUrl)

        if (raw.isNullOrBlank() || (site == "instagram" && !raw.contains("sessionid"))) {
            Toast.makeText(this, "Pehle poora login karein, phir Save dabayein.", Toast.LENGTH_LONG).show()
            return
        }

        val domain = "." + (Uri.parse(cookieUrl).host ?: "").removePrefix("www.")
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600

        val newLines = raw.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                "$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
            }
        }

        val file = cookieFile(this)
        val kept = if (file.exists()) {
            file.readLines().filter {
                it.isNotBlank() && !it.startsWith("#") && !it.startsWith("$domain\t")
            }
        } else emptyList()

        file.writeText("# Netscape HTTP Cookie File\n" + (kept + newLines).joinToString("\n") + "\n")
        Toast.makeText(this, "Login save ho gaya!", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
