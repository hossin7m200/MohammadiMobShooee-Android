package com.mohammadimobshooei.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var captureButton: Button
    private val captureRequestCode = 4101

    private val textReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenCaptureService.EXTRA_TEXT).orEmpty()
            showRecognizedText(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        captureButton = Button(this).apply {
            text = "خواندن متن صفحه"
            textSize = 13f
            setOnClickListener { requestScreenCapture() }
        }
        val buttonParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            setMargins(16, 16, 20, 24)
        }
        root.addView(captureButton, buttonParams)
        setContentView(root)

        val filter = IntentFilter(ScreenCaptureService.ACTION_TEXT_READY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(textReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(textReceiver, filter)
        }

        webView.loadUrl("https://www.mohammadimobshooei.com/")
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequestCode) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "اجازه خواندن صفحه داده نشد.", Toast.LENGTH_SHORT).show()
            return
        }

        captureButton.isEnabled = false
        captureButton.text = "در حال خواندن…"
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {
            captureButton.isEnabled = true
            captureButton.text = "خواندن متن صفحه"
            Toast.makeText(this, "شروع خواندن صفحه ممکن نشد.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showRecognizedText(text: String) {
        captureButton.isEnabled = true
        captureButton.text = "خواندن متن صفحه"
        if (text.isBlank()) {
            Toast.makeText(
                this,
                "متنی پیدا نشد. نسخه فعلی بیشتر برای نوشته‌های انگلیسی مناسب است.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 8)
        }
        val note = TextView(this).apply {
            this.text = "متن شناسایی‌شده (برای ترجمه، آن را با برنامه دلخواه به اشتراک بگذار):"
            textSize = 14f
        }
        val body = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 16)
        }
        content.addView(note)
        content.addView(body)

        val scroll = ScrollView(this).apply { addView(content) }
        android.app.AlertDialog.Builder(this)
            .setTitle("متن صفحه")
            .setView(scroll)
            .setPositiveButton("اشتراک‌گذاری") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(share, "ارسال متن به برنامه ترجمه"))
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(textReceiver)
        } catch (_: Exception) {
        }
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
