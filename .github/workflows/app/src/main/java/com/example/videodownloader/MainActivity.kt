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
        updateBtn.setOnClickListener { updateEngine() }

        askStoragePermissionIfNeeded()
        handleSharedLink(intent)
        initEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedLink(intent)
    }

    // Upar right ⋮ menu
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
        downloadBtn.isEnabled = false
        updateBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = "Video ki jaankari li ja rahi hai..."

        val cookies = LoginActivity.cookieFile(this)

        val request = YoutubeDLRequest(url).apply {
            addOption("-o", "${outputDir.absolutePath}/%(title).80s [%(id)s].%(ext)s")
            addOption("--no-mtime")
            addOption("--no-playlist")
            if (cookies.exists()) addOption("--cookies", cookies.absolutePath)
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
                            statusText.text = "Download ho raha hai: $p%  (baaki ~${eta}s)"
                        }
                    }
                }
                scanNewFiles()
                progressBar.progress = 100
                statusText.text = "Ho gaya! File yahan hai: Download/VideoDownloader"
                urlInput.text.clear()
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val needsLogin = msg.contains("cookies", true) || msg.contains("login", true)
                val tip = if (needsLogin)
                    "Ye site login maang rahi hai. Upar ⋮ menu se Instagram/Facebook login karke dobara try karein."
                else
                    "'Downloader engine update karein' dabakar dobara try karein. Private video download nahi hogi."
                statusText.text = "Download fail hua.\n${msg.take(300)}\n\nTip: $tip"
            } finally {
                busy = false
                downloadBtn.isEnabled = true
                updateBtn.isEnabled = true
            }
        }
    }

    private fun scanNewFiles() {
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000
        val files = outputDir.listFiles()
            ?.filter { it.isFile && it.lastModified() > cutoff }
            ?.map { it.absolutePath }
            ?.toTypedArray() ?: return
        if (files.isNotEmpty()) MediaScannerConnection.scanFile(this, files, null, null)
    }

    private fun updateEngine() {
        if (!ready || busy) return
        busy = true
        updateBtn.isEnabled = false
        downloadBtn.isEnabled = false
        statusText.text = "Engine update ho raha hai..."
        lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance()
                        .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                }
                statusText.text = if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE)
                    "Engine pehle se latest hai." else "Engine update ho gaya."
            } catch (e: Exception) {
                statusText.text = "Update fail hua: ${e.message}"
            } finally {
                busy = false
                updateBtn.isEnabled = true
                downloadBtn.isEnabled = true
            }
        }
    }
}
