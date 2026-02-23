/*
 * ProxyManager — downloads free proxy lists from proxifly, validates them,
 * and applies the first working proxy to the Android WebView via ProxyController.
 */

package com.duckduckgo.app.browser.proxy

import android.os.Build
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import logcat.logcat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Proxy connection state.
 */
sealed class ProxyState {
    object Idle : ProxyState()
    object Loading : ProxyState()
    data class Checking(val current: Int, val total: Int) : ProxyState()
    data class Connected(val entry: ProxyEntry) : ProxyState()
    object Failed : ProxyState()
}

class ProxyManager(
    private val moshi: Moshi,
) {
    companion object {
        private const val TAG = "ProxyManager"

        private val PROXY_URLS = listOf(
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.json",
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.json",
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks4/data.json",
        )

        private const val TEST_URL = "http://httpbin.org/ip"
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 5L
    }

    private val _state = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val state: StateFlow<ProxyState> = _state

    /** Simple OkHttp client for downloading proxy lists (no proxy applied). */
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Main entry point. Downloads proxy lists, validates each proxy, applies the first working one.
     * Returns the connected [ProxyEntry] or null if none worked.
     */
    suspend fun findAndApplyProxy(): ProxyEntry? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            logcat { "$TAG: ProxyController requires API 29+, skipping" }
            return@withContext null
        }

        _state.value = ProxyState.Loading
        logcat { "$TAG: Starting proxy search..." }

        val allProxies = downloadAllProxies()
        if (allProxies.isEmpty()) {
            logcat { "$TAG: No proxies downloaded" }
            _state.value = ProxyState.Failed
            return@withContext null
        }

        logcat { "$TAG: Downloaded ${allProxies.size} proxies total, validating..." }

        // Prefer elite/anonymous proxies first, then by score
        val sorted = allProxies.sortedWith(
            compareByDescending<ProxyEntry> {
                when (it.anonymity) {
                    "elite" -> 2
                    "anonymous" -> 1
                    else -> 0
                }
            }.thenByDescending { it.score },
        )

        for ((index, proxy) in sorted.withIndex()) {
            _state.value = ProxyState.Checking(index + 1, sorted.size)
            logcat { "$TAG: Checking proxy ${index + 1}/${sorted.size}: ${proxy.ip}:${proxy.port} (${proxy.protocol})" }

            if (isProxyWorking(proxy)) {
                logcat { "$TAG: ✅ Working proxy found: ${proxy.ip}:${proxy.port} (${proxy.protocol})" }
                applyProxyToWebView(proxy)
                _state.value = ProxyState.Connected(proxy)
                return@withContext proxy
            }
        }

        logcat { "$TAG: ❌ No working proxy found out of ${sorted.size}" }
        _state.value = ProxyState.Failed
        return@withContext null
    }

    /**
     * Downloads and parses proxy lists from all sources.
     */
    private fun downloadAllProxies(): List<ProxyEntry> {
        val listType = Types.newParameterizedType(List::class.java, ProxyEntry::class.java)
        val adapter = moshi.adapter<List<ProxyEntry>>(listType)
        val allProxies = mutableListOf<ProxyEntry>()

        for (url in PROXY_URLS) {
            try {
                val request = Request.Builder().url(url).build()
                downloadClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val proxies = adapter.fromJson(body)
                            if (proxies != null) {
                                allProxies.addAll(proxies)
                                logcat { "$TAG: Loaded ${proxies.size} proxies from $url" }
                            }
                        }
                    } else {
                        logcat { "$TAG: Failed to download from $url: ${response.code}" }
                    }
                }
            } catch (e: Exception) {
                logcat { "$TAG: Error downloading from $url: ${e.message}" }
            }
        }

        return allProxies
    }

    /**
     * Tests if a proxy is working by making a test request through it.
     */
    private fun isProxyWorking(entry: ProxyEntry): Boolean {
        return try {
            val proxyType = when (entry.protocol) {
                "http", "https" -> Proxy.Type.HTTP
                "socks4", "socks5" -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }

            val proxy = Proxy(proxyType, InetSocketAddress(entry.ip, entry.port))

            val testClient = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(TEST_URL).build()
            testClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    logcat { "$TAG: Proxy ${entry.ip}:${entry.port} responded OK" }
                }
                success
            }
        } catch (e: Exception) {
            logcat { "$TAG: Proxy ${entry.ip}:${entry.port} failed: ${e.message}" }
            false
        }
    }

    /**
     * Applies the proxy to the Android WebView using AndroidX ProxyController.
     */
    private fun applyProxyToWebView(entry: ProxyEntry) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                logcat { "$TAG: WebView PROXY_OVERRIDE feature not supported" }
                return
            }

            val proxyRule = when (entry.protocol) {
                "socks5" -> "socks5://${entry.ip}:${entry.port}"
                "socks4" -> "socks://${entry.ip}:${entry.port}"
                else -> "${entry.ip}:${entry.port}"
            }

            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule(proxyRule)
                .addDirect() // fallback to direct if proxy fails for a request
                .build()

            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                Executor { it.run() },
                Runnable {
                    logcat { "$TAG: WebView proxy applied: $proxyRule" }
                },
            )

            logcat { "$TAG: ProxyController.setProxyOverride called with: $proxyRule" }
        } catch (e: Exception) {
            logcat { "$TAG: Failed to apply proxy to WebView: ${e.message}" }
        }
    }
}
