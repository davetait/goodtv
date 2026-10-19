package com.keyspaceapps.goodtv

import org.junit.Assert.assertNotNull
import org.junit.Test

class BuildConfigTest {
    @Test
    fun debugFieldsExistAndAreStrings() {
        assertNotNull(BuildConfig.XTREAM_SERVER)
        assertNotNull(BuildConfig.XTREAM_USERNAME)
        assertNotNull(BuildConfig.XTREAM_PASSWORD)
    }
}
