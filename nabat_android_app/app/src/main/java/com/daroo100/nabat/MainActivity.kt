package com.daroo100.nabat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        // آدرس آزمایشی پیش‌نمایش پلسک (بعداً از داخل خود برنامه یا اینجا به https://nabat.daroo100.com/factor.html تغییر می‌دهید)
        private const val DEFAULT_URL = "http://185.173.106.117/plesk-site-preview/nabat.daroo100.com/factor.html"
        private const val PREFS_NAME = "nabat_app_prefs"
        private const val KEY_SERVER_URL = "server_url"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var backPressedTime: Long = 0

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val results = mutableListOf<Uri>()
        if (data?.clipData != null) {
            for (i in 0 until data.clipData!!.itemCount) {
                results.add(data.clipData!!.getItemAt(i).uri)
            }
        } else if (data?.data != null) {
            results.add(data.data!!)
        } else if (pendingCameraUri != null) {
            results.add(pendingCameraUri!!)
        }

        callback.onReceiveValue(if (results.isEmpty()) null else results.toTypedArray())
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val req = pendingPermissionRequest
        pendingPermissionRequest = null
        if (req != null) {
            val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
            val camOk = grants[Manifest.permission.CAMERA] == true
            if (audioOk || camOk) {
                req.grant(req.resources)
            } else {
                req.deny()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.nabatWebView)
        progressBar = findViewById(R.id.topProgress)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK

        webView.addJavascriptInterface(NabatAndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // لینک‌های ایتا یا خارج از سرور را در مرورگر/اپلیکیشن مربوطه باز کن
                if (url.startsWith("https://web.eitaa.com") || url.startsWith("eitaa:") || url.startsWith("tel:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    if (!isOnline()) {
                        view?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        Toast.makeText(this@MainActivity, "حالت آفلاین فعال است", Toast.LENGTH_SHORT).show()
                    } else {
                        showConnectionErrorDialog(error?.description?.toString() ?: "خطای شبکه")
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val needed = mutableListOf<String>()
                for (res in request.resources) {
                    if (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    ) {
                        needed.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    ) {
                        needed.add(Manifest.permission.CAMERA)
                    }
                }
                if (needed.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingPermissionRequest = request
                    runtimePermissionLauncher.launch(needed.toTypedArray())
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val extraIntents = mutableListOf<Intent>()
                try {
                    val photoFile = File(cacheDir, "nabat_cam_${System.currentTimeMillis()}.jpg")
                    pendingCameraUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    extraIntents.add(camIntent)
                } catch (_: Exception) {
                    pendingCameraUri = null
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, galleryIntent)
                    putExtra(Intent.EXTRA_TITLE, "انتخاب عکس سونوگرافی یا عکاسی")
                    if (extraIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
                    }
                }
                fileChooserLauncher.launch(chooser)
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = now
                        Toast.makeText(this@MainActivity, "برای خروج یک بار دیگر دکمه بازگشت را بزنید", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        webView.loadUrl(getSavedServerUrl())
    }

    private fun getSavedServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    private fun saveServerUrl(newUrl: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, newUrl.trim()).apply()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showServerSettingsDialog() {
        val input = EditText(this).apply {
            setText(getSavedServerUrl())
            hint = "https://nabat.daroo100.com/factor.html"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("تنظیم آدرس سرور سامانه نَبَط")
            .setMessage("آدرس فعلی (آزمایشی یا دامنه رسمی) را وارد کنید:")
            .setView(input)
            .setPositiveButton("ذخیره و بارگذاری") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    saveServerUrl(url)
                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webView.loadUrl(url)
                    Toast.makeText(this, "آدرس جدید ذخیره شد", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "آدرس باید با http:// یا https:// شروع شود", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("بازگشت به پیش‌فرض") { _, _ ->
                saveServerUrl(DEFAULT_URL)
                webView.loadUrl(DEFAULT_URL)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showConnectionErrorDialog(errText: String) {
        AlertDialog.Builder(this)
            .setTitle("خطا در اتصال به سرور نَبَط")
            .setMessage("جزئیات: $errText\nآیا می‌خواهید دوباره تلاش کنید یا آدرس سرور را بررسی کنید؟")
            .setPositiveButton("تلاش مجدد") { _, _ -> webView.reload() }
            .setNeutralButton("تغییر آدرس سرور") { _, _ -> showServerSettingsDialog() }
            .setNegativeButton("بستن", null)
            .show()
    }

    inner class NabatAndroidBridge {
        @JavascriptInterface
        fun printInvoicePdf(jobTitle: String?) {
            runOnUiThread {
                try {
                    val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                    val safeName = if (jobTitle.isNullOrBlank()) "Nabat-Invoice" else jobTitle
                    val adapter = webView.createPrintDocumentAdapter(safeName)
                    val attrs = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A5)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                    printManager.print(safeName, adapter, attrs)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "خطا در چاپ PDF: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun shareText(text: String?) {
            if (text.isNullOrBlank()) return
            runOnUiThread {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(sendIntent, "ارسال در ایتا یا سایر پیام‌رسان‌ها"))
            }
        }

        @JavascriptInterface
        fun openServerSettings() {
            runOnUiThread {
                showServerSettingsDialog()
            }
        }
    }
}
