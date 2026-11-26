package com.daroo100.nabat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREVIEW_URL = "http://185.173.106.117/plesk-site-preview/nabat.daroo100.com/factor.html"
        private const val OFFICIAL_URL = "https://nabat.daroo100.com/factor.html"
        private const val PREFS_NAME = "nabat_app_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ASKED_STARTUP_PERMS = "asked_startup_perms_v4"
        private const val EITAA_PKG = "ir.eitaa.messenger"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraFile: File? = null
    private var pendingCameraUri: Uri? = null
    private var pendingCaptureOnly: Boolean = false
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var backPressedTime: Long = 0

    // ۱. درخواست مجوزهای دوربین و میکروفون در اولین اجرای برنامه (تا کاربر هرگز نیاز به ورود دستی به تنظیمات نداشته باشد)
    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // نتیجه توسط سیستم ذخیره می‌شود و در صورت نیاز مجدداً هنگام لمس دوربین بررسی خواهد شد
    }

    // ۲. دریافت نتیجه عکاسی یا انتخاب از گالری
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode != Activity.RESULT_OK) {
            cleanupPendingCameraFile()
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val results = mutableListOf<Uri>()
        if (data?.clipData != null && data.clipData!!.itemCount > 0) {
            for (i in 0 until data.clipData!!.itemCount) {
                results.add(data.clipData!!.getItemAt(i).uri)
            }
        } else if (data?.data != null) {
            results.add(data.data!!)
        } else {
            val camFile = pendingCameraFile
            val camUri = pendingCameraUri
            if (camFile != null && camUri != null && camFile.exists() && camFile.length() > 0) {
                results.add(camUri)
            }
        }

        callback.onReceiveValue(if (results.isEmpty()) null else results.toTypedArray())
    }

    // ۳. درخواست مجوز زمان اجرای دوربین هنگام لمس دکمه عکاسی/گالری
    private val cameraPermissionForChooserLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchImageChooser(captureOnly = pendingCaptureOnly, allowCamera = true)
        } else {
            val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
            if (pendingCaptureOnly && !canAskAgain) {
                // کاربر قبلاً مجوز را دائماً رد کرده است؛ دکمه مستقیم باز کردن تنظیمات را نشان بده
                AlertDialog.Builder(this)
                    .setTitle("نیاز به مجوز دوربین")
                    .setMessage("برای عکاسی مستقیم از سونوگرافی و مدارک بیمار، مجوز دوربین را روی «هنگام استفاده از برنامه» قرار دهید یا عکس را از گالری انتخاب کنید.")
                    .setPositiveButton("باز کردن تنظیمات مجوز") { _, _ ->
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNeutralButton("انتخاب از گالری") { _, _ ->
                        launchImageChooser(captureOnly = false, allowCamera = false)
                    }
                    .setNegativeButton("انصراف") { _, _ ->
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                    }
                    .setCancelable(false)
                    .show()
            } else {
                Toast.makeText(
                    this,
                    "مجوز دوربین داده نشد؛ می‌توانید عکس را از گالری انتخاب کنید.",
                    Toast.LENGTH_LONG
                ).show()
                launchImageChooser(captureOnly = false, allowCamera = false)
            }
        }
    }

    // ۴. درخواست مجوزهای میکروفون و دوربین از طریق WebChromeClient
    private val webRuntimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val req = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (req != null) {
            val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val camOk = grants[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
                if (url.startsWith("https://web.eitaa.com") ||
                    url.startsWith("https://eitaa.com") ||
                    url.startsWith("eitaa:") ||
                    url.startsWith("tel:")
                ) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    return true
                }
                return false
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                showSslNoticeDialog()
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
                    pendingWebPermissionRequest = request
                    webRuntimePermissionLauncher.launch(needed.toTypedArray())
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val isCapture = fileChooserParams?.isCaptureEnabled == true
                pendingCaptureOnly = isCapture

                val hasCameraPerm = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasCameraPerm) {
                    cameraPermissionForChooserLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    launchImageChooser(captureOnly = isCapture, allowCamera = true)
                }
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
                        Toast.makeText(
                            this@MainActivity,
                            "برای خروج یک بار دیگر دکمه بازگشت را بزنید",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })

        // درخواست یک‌باره مجوز دوربین و میکروفون در اولین اجرا
        requestInitialPermissionsIfNeeded()

        webView.loadUrl(getSavedServerUrl())
    }

    private fun requestInitialPermissionsIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_STARTUP_PERMS, false)) return

        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        prefs.edit().putBoolean(KEY_ASKED_STARTUP_PERMS, true).apply()
        if (missing.isNotEmpty()) {
            startupPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun launchImageChooser(captureOnly: Boolean, allowCamera: Boolean) {
        try {
            var camIntent: Intent? = null
            if (allowCamera) {
                try {
                    val photoFile = File(cacheDir, "nabat_cam_${System.currentTimeMillis()}.jpg")
                    val photoUri = FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    pendingCameraFile = photoFile
                    pendingCameraUri = photoUri

                    camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                        clipData = ClipData.newRawUri("NabatPhoto", photoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } catch (_: Exception) {
                    pendingCameraFile = null
                    pendingCameraUri = null
                    camIntent = null
                }
            } else {
                pendingCameraFile = null
                pendingCameraUri = null
            }

            if (captureOnly && camIntent != null) {
                try {
                    fileChooserLauncher.launch(camIntent)
                    return
                } catch (_: Exception) {}
            }

            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, galleryIntent)
                putExtra(Intent.EXTRA_TITLE, "انتخاب عکس سونوگرافی یا عکاسی")
                if (camIntent != null) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camIntent))
                }
            }
            fileChooserLauncher.launch(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در باز کردن دوربین/گالری: ${e.message}", Toast.LENGTH_SHORT).show()
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private fun cleanupPendingCameraFile() {
        try {
            pendingCameraFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
        } catch (_: Exception) {}
        pendingCameraFile = null
        pendingCameraUri = null
    }

    private fun cleanupOldTempPdfs() {
        try {
            val pdfDir = File(cacheDir, "shared_pdfs")
            if (pdfDir.exists()) {
                val cutoff = System.currentTimeMillis() - (60 * 60 * 1000L) // فایل‌های موقت بالای ۱ ساعت
                pdfDir.listFiles()?.forEach { f ->
                    if (f.lastModified() < cutoff) f.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun getSavedServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, PREVIEW_URL) ?: PREVIEW_URL
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
            hint = OFFICIAL_URL
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("تنظیم آدرس سرور نَبَط")
            .setMessage("آدرس فعلی را وارد کنید:\n• رسمی: $OFFICIAL_URL\n• پیش‌نمایش: $PREVIEW_URL")
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
            .setNeutralButton("دامنه رسمی (HTTPS)") { _, _ ->
                saveServerUrl(OFFICIAL_URL)
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                webView.loadUrl(OFFICIAL_URL)
                Toast.makeText(this, "روی دامنه رسمی تنظیم شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showSslNoticeDialog() {
        AlertDialog.Builder(this)
            .setTitle("هشدار گواهی امنیتی (SSL)")
            .setMessage("آدرس فعلی گواهی SSL معتبر ندارد. برای پیش‌نمایش پلسک از http:// و برای دامنه رسمی از https:// استفاده کنید.")
            .setPositiveButton("تنظیم آدرس سرور") { _, _ -> showServerSettingsDialog() }
            .setNegativeButton("بستن", null)
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

        // اشتراک‌گذاری مستقیم فایل PDF در ایتا بدون ذخیره کردن در پوشه دانلودهای گوشی
        @JavascriptInterface
        fun sharePdfBase64(base64Data: String?, fileName: String?, preferEitaa: Boolean) {
            if (base64Data.isNullOrBlank()) return
            runOnUiThread {
                try {
                    cleanupOldTempPdfs()
                    val cleanName = (if (fileName.isNullOrBlank()) "Nabat_Invoice" else fileName)
                        .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
                        .trim()
                    val pdfBytes = Base64.decode(base64Data, Base64.DEFAULT)

                    val pdfDir = File(cacheDir, "shared_pdfs")
                    if (!pdfDir.exists()) pdfDir.mkdirs()
                    val outFile = File(pdfDir, "$cleanName.pdf")
                    FileOutputStream(outFile).use { it.write(pdfBytes) }

                    val pdfUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        outFile
                    )

                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, pdfUri)
                        clipData = ClipData.newRawUri(cleanName, pdfUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    if (preferEitaa) {
                        val eitaaIntent = Intent(sendIntent).setPackage(EITAA_PKG)
                        val eitaaResolves = packageManager.queryIntentActivities(eitaaIntent, 0)
                        if (eitaaResolves.isNotEmpty()) {
                            startActivity(eitaaIntent)
                            return@runOnUiThread
                        }
                    }
                    startActivity(Intent.createChooser(sendIntent, "ارسال مستقیم فایل PDF در ایتا"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "خطا در ارسال مستقیم PDF به ایتا: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun shareTextToEitaa(text: String?, preferEitaa: Boolean) {
            if (text.isNullOrBlank()) return
            runOnUiThread {
                try {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    if (preferEitaa) {
                        val eitaaIntent = Intent(sendIntent).setPackage(EITAA_PKG)
                        if (packageManager.queryIntentActivities(eitaaIntent, 0).isNotEmpty()) {
                            startActivity(eitaaIntent)
                            return@runOnUiThread
                        }
                    }
                    startActivity(Intent.createChooser(sendIntent, "ارسال در ایتا یا سایر پیام‌رسان‌ها"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "خطا در اشتراک‌گذاری: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun shareText(text: String?) {
            shareTextToEitaa(text, false)
        }

        @JavascriptInterface
        fun copyToClipboard(text: String?) {
            if (text == null) return
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Nabat", text))
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
