package io.github.urzahil.iptv.data.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * One shared OkHttp client (TLS verification is never relaxed; cleartext is allowed by the manifest
 * because most IPTV providers are plain HTTP). No logging interceptor: URLs carry credentials.
 */
class HttpClientFactory {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Longer read timeout for big XMLTV downloads. */
    val epgClient: OkHttpClient = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
}
