package com.keyspaceapps.goodtv.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StackContentKeyTest {
    @Test
    fun theSamePageTwiceGetsTwoContentKeys() {
        val first = MovieDetailKey(1, "m1")
        val person = PersonSearchKey(500, "Tom Cruise")
        val second = MovieDetailKey(1, "m1")
        val stack = listOf(HomeKey, first, person, second)
        // A copy's key doesn't depend on what is above it.
        assertEquals(stackContentKey(listOf(HomeKey, first), first), stackContentKey(stack, first))
        assertNotEquals(stackContentKey(stack, first), stackContentKey(stack, second))
        // Popped, the second copy keeps its key while it animates out.
        assertEquals(stackContentKey(stack, second), stackContentKey(stack.dropLast(1), second))
    }
}
