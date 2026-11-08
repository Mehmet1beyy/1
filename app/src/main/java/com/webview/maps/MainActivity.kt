package com.webview.maps

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Google Haritalar mobil adresi
    private val startUrl = "https://www.google.com/maps"

    // Henüz izin verilmemiş bir konum isteği beklerken köprü tutulur.
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val locationPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result.values.any { it }
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }
        setContentView(webView)

        configureWebView()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // http/https haritada kalır; diğer şemalar/derin bağlantılar dışarı.
                return if (isWebUrl(url)) {
                    false
                } else {
                    handleExternalUrl(url)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    requestLocationPermission()
                }
            }

            // "Yol tarifi" gibi butonlar yeni pencere (window.open / target=_blank)
            // açar. Hedef adresi yakalayıp ana WebView'da açarız; böylece tık boşa
            // gitmez. Harita dışı derin bağlantılar ise ilgili uygulamaya gider.
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val transport = resultMsg.obj as WebView.WebViewTransport
                val temp = WebView(this@MainActivity)
                temp.settings.javaScriptEnabled = true
                temp.settings.userAgentString = webView.settings.userAgentString
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        if (isWebUrl(url)) {
                            webView.loadUrl(url)
                        } else {
                            handleExternalUrl(url)
                        }
                        v.post { v.destroy() }
                        return true
                    }
                }
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }

        // Geri tuşu: WebView geçmişinde gezinir, geçmiş yoksa uygulamadan çıkar.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Açılışta konum izni iste ki "Konumum" özelliği çalışsın.
        if (!hasLocationPermission()) {
            requestLocationPermission()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Google'ın tam donanımlı mobil harita arayüzünü sunması için
            // güncel bir mobil Chrome kimliği kullanılır.
            userAgentString =
                "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
    }

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /**
     * Harita dışı bağlantıları yönetir:
     * - "Başlat" navigasyonu: intent:// veya google.navigation: / geo: ile
     *   kurulu Google Haritalar uygulamasına devredilir.
     * - Kurulu uygulama yoksa intent'in browser_fallback_url'ine düşülür.
     * - tel:, mailto:, sms: gibi şemalar ilgili uygulamaya açılır.
     * Her durumda true döner (WebView bu adresi kendisi yüklemeye çalışmasın).
     */
    private fun handleExternalUrl(url: String): Boolean {
        try {
            if (url.startsWith("intent://")) {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        return true
                    }
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrEmpty()) {
                        webView.loadUrl(fallback)
                        return true
                    }
                    // Uygulama yoksa Google Haritalar web navigasyonuna çevir.
                    openMapsFallback(intent)
                }
                return true
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(
                    this,
                    "Bu işlemi açacak bir uygulama bulunamadı",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Uygun uygulama bulunamadı", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Sessizce yut; WebView çökmez.
        }
        return true
    }

    /** google.navigation:q=.. gibi bir intent'i web haritası yol tarifine çevirir. */
    private fun openMapsFallback(intent: Intent) {
        val data = intent.dataString ?: return
        val q = Uri.parse(data).getQueryParameter("q")
            ?: Uri.parse(data).query
        if (!q.isNullOrEmpty()) {
            webView.loadUrl(
                "https://www.google.com/maps/dir/?api=1&destination=" +
                    Uri.encode(q)
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
