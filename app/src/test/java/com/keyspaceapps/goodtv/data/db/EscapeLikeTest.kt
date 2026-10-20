package com.keyspaceapps.goodtv.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class EscapeLikeTest {
    @Test
    fun plainTextUnchanged() = assertEquals("matrix", escapeLike("matrix"))

    @Test
    fun wildcardsAndBackslashEscaped() = assertEquals("100\\% a\\_b c\\\\d", escapeLike("100% a_b c\\d"))
}
