package com.keyspaceapps.goodtv.data.xtream

import kotlinx.serialization.json.Json

object XtreamJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
}
