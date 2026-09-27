package com.example.videodownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var audioOnly: CheckBox
    private lateinit var downloadBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var ready = false
    private var busy = false

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val outputDir: File by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "VideoDownloader"
        ).apply { mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        audioOnly = findViewById(R.id.audioOnly)
        downloadBtn = findViewById(R.id.downloadBtn)
        updateBtn = findViewById(R.id.updateBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.pasteBtn).setOnClickListener { pasteFromClipboard() }
        downloadBtn.setOnClickListener { startDownload() }
        updateBtn.setOnClickListener { updateEngine(auto = false) }

        askStoragePermissionIfNeeded()
        handleSharedLink(intent)
        initEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedLink(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Instagram login")
        menu.add(0, 2, 0, "Facebook login")
        menu.add(0, 3, 0, "Login hatayein")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> openLogin("instagram")
            2 -> openLogin("facebook")
            3 -> {
                LoginActivity.cookieFile(this).delete()
                CookieManager.getInstance().removeAllCookies(null)
                statusText.text = "Login hata diya gaya."
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun openLogin(site: String) {
        startActivity(
            Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SITE, site)
        )
    }

    private fun handleSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = Regex("https?://\\S+").find(text)?.value ?: return
        urlInput.setText(link)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val link = Regex("https?://\\S+").find(text)?.value ?: text
        urlInput.setText(link.trim())
    }

    private fun askStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    private fun initEngine() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                }
                ready = true
                downloadBtn.isEnabled = true
                statusText.text = "Taiyar hai. Link daalkar Download dabayein."

                // Din mein ek baar engine apne aap update
                val last = prefs.getLong("lastUpdate", 0L)
                if (System.currentTimeMillis() - last > 24L * 3600 * 1000) {
                    updateEngine(auto = true)
                }
            } catch (e: Exception) {
                statusText.text = "Engine start nahi hua: ${e.message}"
            }
        }
    }

    private fun startDownload() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("http")) {
            statusText.text = "Sahi link daalein (https:// se shuru hona chahiye)."
            return
        }
        if (!ready || busy) return

        busy = true
        setButtons(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = "Video ki jaankari li ja rahi hai..."

        val cookies = LoginActivity.cookieFile(this)
        val isYouTube = url.contains("youtube.com") || url.contains("youtu.be")

        val request = YoutubeDLRequest(url).apply {
            addOption("-o", "${outputDir.absolutePath}/%(title).70s [%(id)s].%(ext)s")
            addOption("--no-mtime")
            addOption("--windows-filenames")
            addOption("--retries", "5")
            addOption("--fragment-retries", "10")
            if (isYouTube) addOption("--no-playlist")
            if (cookies.exists() && !isYouTube) addOption("--cookies", cookies.absolutePath)
            if (audioOnly.isChecked) {
                addOption("-x")
                addOption("--audio-format", "mp3")
            } else {
                addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                addOption("--merge-output-format", "mp4")
            }
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, "download") { progress, eta, _ ->
                        runOnUiThread {
                            val p = progress.toInt().coerceIn(0, 100)
                            progressBar.progress = p
                            statusText.text = "Download ho raha hai: $p%  (baaki ~${eta}s)\nApp band na karein."
                        }
                    }
                }
                scanNewFiles()
                progressBar.progress = 100
                statusText.text = "Ho gaya! File yahan hai: Download/VideoDownloader"
                urlInput.text.clear()
            } catch (e: Exception) {
                statusText.text = "Download fail hua.\n${friendlyError(e.message ?: "", url)}"
            } finally {
                busy = false
                setButtons(true)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun friendlyError(msg: String, url: String): String {
        val m = msg.lowercase()
        val site = when {
            url.contains("instagram") -> "Instagram"
            url.contains("facebook") || url.contains("fb.watch") -> "Facebook"
            else -> ""
        }
        val tip = when {
            m.contains("unable to resolve host") || m.contains("network is unreachable") ||
                    m.contains("timed out") ->
                "Internet connection check karein aur dobara try karein."
            site.isNotEmpty() && (m.contains("cookies") || m.contains("login") ||
                    m.contains("empty media") || m.contains("rate") || m.contains("private")) ->
                if (LoginActivity.cookieFile(this).exists())
                    "$site ne mana kar diya. Login purana ho gaya hoga: upar ⋮ se $site login dobara karein. Private account ki video tabhi milegi jab aap us account ko follow karte hon."
                else
                    "$site login maang raha hai. Upar ⋮ menu se $site login karke dobara try karein."
            m.contains("sign in to confirm") || m.contains("bot") ->
                "YouTube ne rok diya. 'Downloader engine update karein' dabayein, thodi der baad dobara try karein."
            m.contains("unsupported url") ->
                "Ye link support nahi hai. Video ka poora link (Share → Copy link) daalein."
            m.contains("no space") ->
                "Phone mein jagah khatam hai. Kuch jagah khaali karein."
            else ->
                "'Downloader engine update karein' dabakar dobara try karein."
        }
        return "$tip\n\n(Detail: ${msg.take(200)})"
    }

    private fun setButtons(enabled: Boolean) {
        downloadBtn.isEnabled = enabled
        updateBtn.isEnabled = enabled
    }

    private fun scanNewFiles() {
        val cutoff = System.currentTimeMillis() - 30 * 60 * 1000
        val files = outputDir.listFiles()
            ?.filter { it.isFile && it.lastModified() > cutoff }
            ?.map { it.absolutePath }
            ?.toTypedArray() ?: return
        if (files.isNotEmpty()) MediaScannerConnection.scanFile(this, files, null, null)
    }

    private fun updateEngine(auto: Boolean) {
        if (!ready || busy) return
        busy = true
        setButtons(false)
        statusText.text = "Engine update ho raha hai, thoda rukiye..."
        lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance()
                        .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                }
                prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
                statusText.text = when {
                    auto -> "Taiyar hai. Link daalkar Download dabayein."
                    status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Engine pehle se latest hai."
                    else -> "Engine update ho gaya."
                }
            } catch (e: Exception) {
                statusText.text = if (auto) "Taiyar hai. Link daalkar Download dabayein."
                else "Update fail hua. Internet check karein.\n(${e.message?.take(150)})"
            } finally {
                busy = false
                setButtons(true)
            }
        }
    }
}
