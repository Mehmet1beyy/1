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
     * Harita dışı bağlantıları UYGULAMA İÇİNDE tutar.
     *
     * "Başlat"/navigasyon ve konum derin bağlantıları (intent://, geo:,
     * google.navigation:, comgooglemaps:) başka uygulamaya DEVREDİLMEZ;
     * eşdeğer bir Google Haritalar web adresine çevrilip aynı WebView'da açılır.
     * Böylece uygulamadan dışarı atlama olmaz.
     *
     * Yalnızca apk içinde karşılığı olmayan tel:/sms:/mailto: gibi şemalar
     * ilgili sistem uygulamasına açılır (WebView'da telefon araması yapılamaz).
     * Her durumda true döner; WebView bu adresi kendisi yüklemeye çalışmaz.
     */
    private fun handleExternalUrl(url: String): Boolean {
        try {
            // 1) intent:// — içindeki asıl adresi/koordinatı çıkar, apk içinde aç.
            if (url.startsWith("intent://", ignoreCase = true)) {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                val data = intent?.dataString
                val fallback = intent?.getStringExtra("browser_fallback_url")
                val inApp = when {
                    data != null && isWebUrl(data) -> data
                    data != null -> toInAppMapsUrl(data)
                    !fallback.isNullOrEmpty() -> fallback
                    else -> null
                }
                if (inApp != null) webView.loadUrl(inApp)
                // Çevrilemeyen intent'leri yok say (dışarı atlamamak için).
                return true
            }

            when (Uri.parse(url).scheme?.lowercase()) {
                "geo", "google.navigation", "comgooglemaps", "maps" -> {
                    val inApp = toInAppMapsUrl(url)
                    if (inApp != null) webView.loadUrl(inApp)
                    return true
                }
                "tel", "sms", "smsto", "mailto" -> {
                    // apk içinde karşılığı yok → ilgili uygulamaya aç.
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                else -> {
                    // Bilinmeyen şema: dışarı atlamamak için yok say.
                    return true
                }
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Uygun uygulama bulunamadı", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Sessizce yut; WebView çökmez.
        }
        return true
    }

    /**
     * geo: / google.navigation: / comgooglemaps: gibi bir harita derin
     * bağlantısını, aynı WebView'da açılabilen bir Google Haritalar web
     * adresine çevirir. Çevrilemezse null döner.
     */
    private fun toInAppMapsUrl(raw: String): String? {
        try {
            val uri = Uri.parse(raw)
            when (uri.scheme?.lowercase()) {
                // google.navigation:q=HEDEF&mode=d  (sürüş/yürüyüş navigasyonu)
                "google.navigation" -> {
                    val fake = Uri.parse("http://nav/?${uri.encodedSchemeSpecificPart}")
                    val q = fake.getQueryParameter("q")
                    val mode = when (fake.getQueryParameter("mode")) {
                        "w" -> "walking"
                        "b" -> "bicycling"
                        "l", "r" -> "transit"
                        else -> "driving"
                    }
                    if (!q.isNullOrEmpty()) {
                        return "https://www.google.com/maps/dir/?api=1" +
                            "&destination=${Uri.encode(q)}" +
                            "&travelmode=$mode&dir_action=navigate"
                    }
                }

                // comgooglemaps://?daddr=HEDEF&saddr=BASLANGIC  veya  ?q=ARAMA
                "comgooglemaps" -> {
                    val daddr = uri.getQueryParameter("daddr")
                    val saddr = uri.getQueryParameter("saddr")
                    if (!daddr.isNullOrEmpty()) {
                        val origin =
                            if (!saddr.isNullOrEmpty()) "&origin=${Uri.encode(saddr)}" else ""
                        return "https://www.google.com/maps/dir/?api=1" +
                            "&destination=${Uri.encode(daddr)}$origin" +
                            "&travelmode=driving&dir_action=navigate"
                    }
                    val q = uri.getQueryParameter("q")
                    if (!q.isNullOrEmpty()) {
                        return "https://www.google.com/maps/search/?api=1&query=${Uri.encode(q)}"
                    }
                }

                // geo:LAT,LNG?q=...  veya  geo:0,0?q=ARAMA
                "geo", "maps" -> {
                    val ssp = uri.schemeSpecificPart ?: return null
                    val qIndex = ssp.indexOf('?')
                    val coords = if (qIndex >= 0) ssp.substring(0, qIndex) else ssp
                    val query = if (qIndex >= 0) {
                        Uri.parse("http://geo/?${ssp.substring(qIndex + 1)}")
                            .getQueryParameter("q")
                    } else null
                    val target = when {
                        !query.isNullOrEmpty() -> query
                        coords.isNotBlank() && coords != "0,0" -> coords
                        else -> null
                    }
                    if (!target.isNullOrEmpty()) {
                        return "https://www.google.com/maps/search/?api=1&query=${Uri.encode(target)}"
                    }
                }
            }
        } catch (e: Exception) {
            // yoksay
        }
        return null
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
