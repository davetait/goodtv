package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** The two epgshare01 files the guide is built from. */
object EpgShareFiles {
    const val LOCALS = "epg_ripper_US_LOCALS1.xml.gz"
    const val CABLE = "epg_ripper_US2.xml.gz"
}

/** Downloads epgshare01's public guide files. */
class EpgShareSource(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://epgshare01.online/epgshare01/".toHttpUrl(),
) {
    /** [file] gunzipped, streamed; closing the stream releases the connection. Throws [IOException] on a failed download. */
    suspend fun open(file: String): InputStream = GZIPInputStream(openCompressed(file), 1 shl 16)

    /** [file] as served (gzip), streamed. Throws [IOException] on a failed download. */
    suspend fun openCompressed(file: String): InputStream = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.resolve(file) ?: throw IOException("Bad guide file name")).header("User-Agent", USER_AGENT).build()
        val response = http.newCall(request).execute()
        try {
            if (!response.isSuccessful) throw IOException("Guide server returned HTTP ${response.code}")
            response.body.byteStream()
        } catch (e: Throwable) {
            response.close()
            throw e
        }
    }
}
