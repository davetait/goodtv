package com.keyspaceapps.goodtv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {
    @Test
    fun multiSourceAppendsNickname() = assertEquals("Sports · Home", Labels.categoryLabel("Sports", "Home", multiSource = true))

    @Test
    fun singleSourceIsBareName() = assertEquals("Sports", Labels.categoryLabel("Sports", "Home", multiSource = false))
}
