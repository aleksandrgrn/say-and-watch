package com.voicesearch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerActionTest {

    @Test
    fun `russian trailer opens the video in SmartTube`() {
        assertEquals(TrailerAction.Open("ru1"), trailerAction("ru1", "Люпен", hasSmartTube = true))
    }

    @Test
    fun `no russian trailer searches by title`() {
        assertEquals(TrailerAction.Search, trailerAction(null, "Люпен", hasSmartTube = true))
    }

    @Test
    fun `without SmartTube the button stays off even with a trailer`() {
        assertEquals(TrailerAction.Off, trailerAction("ru1", "Люпен", hasSmartTube = false))
        assertEquals(TrailerAction.Off, trailerAction(null, "Люпен", hasSmartTube = false))
    }

    @Test
    fun `no trailer and no title gives nothing to search`() {
        assertEquals(TrailerAction.Off, trailerAction(null, " ", hasSmartTube = true))
    }
}
