package com.keyspaceapps.goodtv

import android.content.Context
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.xtream.XtreamApi
import com.keyspaceapps.goodtv.data.xtream.XtreamChannelSource
import com.keyspaceapps.goodtv.data.xtream.XtreamJson
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

const val USER_AGENT = "GoodTV/0.2"

/** Manual dependency graph. One instance per process, owned by [GoodTvApp]. */
class AppGraph(val context: Context) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val sourceRegistry: SourceRegistry by lazy {
        SourceRegistry(listOf(XtreamChannelSource(XtreamApi(okHttp, XtreamJson.instance))))
    }
}
