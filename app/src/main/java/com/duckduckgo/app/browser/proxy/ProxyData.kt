/*
 * Proxy data models for parsing proxifly free-proxy-list JSON.
 * Source: https://github.com/proxifly/free-proxy-list
 */

package com.duckduckgo.app.browser.proxy

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProxyGeolocation(
    val country: String = "ZZ",
    val city: String = "Unknown",
)

@JsonClass(generateAdapter = true)
data class ProxyEntry(
    val proxy: String,
    val protocol: String,
    val ip: String,
    val port: Int,
    val https: Boolean = false,
    val anonymity: String = "transparent",
    val score: Int = 0,
    val geolocation: ProxyGeolocation = ProxyGeolocation(),
)
