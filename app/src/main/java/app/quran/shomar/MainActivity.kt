package app.quran.shomar

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import java.io.IOException

/**
 * Hosts the original web page (assets/index.html, unchanged) inside a WebView.
 * The page is served from https://appassets.androidplatform.net so it runs in a secure
 * context (localStorage, IndexedDB, geolocation and clipboard behave like on a real site).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var shimJs: String = ""

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            sendNotifResult(granted)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureNotificationChannel()
        shimJs = try {
            assets.open("android-shim.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            ""
        }

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0C14"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            webViewClient = AppWebViewClient()
            webChromeClient = AppChromeClient()
        }
        setContentView(webView)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(BACK_JS) { r ->
                    if (r == null || !r.contains("handled")) finish()
                }
            }
        })

        webView.loadUrl("https://$HOST/assets/index.html")
    }

    override fun onDestroy() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- WebView clients

    private inner class AppWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            assetLoader.shouldInterceptRequest(uri)?.let { return it }
            return offlineFontResponse(uri)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            if (uri.host == HOST) return false
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (shimJs.isNotEmpty()) view.evaluateJavascript(shimJs, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (shimJs.isNotEmpty()) view.evaluateJavascript(shimJs, null)
        }
    }

    /** Serve the fonts the page asks for from the APK, so Arabic text renders correctly offline. */
    private fun offlineFontResponse(uri: Uri): WebResourceResponse? {
        val host = uri.host ?: return null
        val path = uri.path ?: ""
        val file = when {
            host == "fonts.googleapis.com" && path.startsWith("/css2") -> "fonts/vazirmatn.css"
            host == "cdn.jsdelivr.net" && path.contains("scheherazade-new") -> "fonts/scheherazade-new.css"
            else -> return null
        }
        return try {
            WebResourceResponse(
                "text/css", "UTF-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                assets.open(file)
            )
        } catch (e: IOException) {
            null
        }
    }

    private inner class AppChromeClient : WebChromeClient() {

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            val fine = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) {
                callback.invoke(origin, true, false)
            } else {
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                locationPermLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------- JS <-> native bridge

    inner class AndroidBridge {
        @JavascriptInterface
        fun notify(title: String, body: String) {
            runOnUiThread { showNotification(title, body) }
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread { requestNotifPermission() }
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) sendNotifResult(true)
            else notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            sendNotifResult(NotificationManagerCompat.from(this).areNotificationsEnabled())
        }
    }

    private fun sendNotifResult(granted: Boolean) {
        val value = if (granted) "granted" else "denied"
        webView.evaluateJavascript(
            "window.__onNotifPermission && window.__onNotifPermission('$value')", null
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.adhan_channel), NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val CHANNEL_ID = "adhan"

        /** Back button: close the date picker, then return to the Quran tab, then exit. */
        private const val BACK_JS =
            "(function(){try{" +
                "if(document.getElementById('jalaliPicker')){closeJalaliPicker();return 'handled';}" +
                "var a=document.querySelector('.nav-tab.active');" +
                "if(a&&a.dataset.tab!=='quran'){switchTab('quran');return 'handled';}" +
                "}catch(e){}return 'exit';})()"
    }
}
