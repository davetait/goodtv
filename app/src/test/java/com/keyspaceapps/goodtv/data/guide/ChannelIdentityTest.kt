package com.keyspaceapps.goodtv.data.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelIdentityTest {
    private fun id(name: String, category: String = "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ") = ChannelIdentity.of(name, category)

    @Test fun headingsAndEmptySlotsAreHidden() {
        assertTrue(id("####### NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ #######", "US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").hidden)
        assertTrue(id("- NO EVENT STREAMING - | 8K EXCLUSIVE | US: MLS PPV 1", "US| MLS PPV").hidden)
        assertTrue(id("ENDED | DET - PISTONS VS BOS - CELTICS | Tue 20 Oct 14:00 EDT (US) | 8K EXCLUSIVE | US: NBA PASS PPV 1", "US| NBA PASS PPV ⁸ᴷ").hidden)
        assertTrue(id("LIVE EVENT 03 - NO EVENT", "US| PPV EVENT").hidden)
        assertFalse(id("US: NBC 4 NEW YORK (WNBC)", "US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").hidden)
        assertFalse(id("LIVE | SAS - SPURS VS OKC - THUNDER | Tue 20 Oct 20:30 EDT (US) | 8K EXCLUSIVE | US: NBA PASS PPV 3", "US| NBA PASS PPV ⁸ᴷ").hidden)
    }

    @Test fun qualityCopiesShareARowKey() {
        assertEquals(id("US: A&E HD", "US| ENTERTAINMENT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").rowKey, id("TV: A&E ᴿᴬᵂ").rowKey)
        assertEquals(id("AT&T: A&E ᴿᴬᵂ").rowKey, id("US: A&E HD").rowKey)
        assertEquals(id("US: NBC 5 (WMAQ) CHICAGO (S) ᴿᴬᵂ").rowKey, id("US: NBC 5 (WMAQ) CHICAGO (D) HD").rowKey)
        assertNotEquals(id("US: NBC BRAVO (EAST) (D) ᴿᴬᵂ").rowKey, id("US: NBC BRAVO (WEST) (D) ᴿᴬᵂ").rowKey)
        assertEquals(Region.EAST, id("US: NBC BRAVO (EAST) (D) ᴿᴬᵂ").region)
        assertEquals(Region.WEST, id("US: BRAVO WEST HD").region)
        // A loop of a show is not the cinema channel of the same name.
        assertNotEquals(id("24/7: 30 ROCK", "US| 24/7 COMEDY ᴿᴬᵂ ⁶⁰ᶠᵖˢ").rowKey, id("US: 30 ROCK 4K").rowKey)
    }

    @Test fun regionsAndCallSignsStayApart() {
        val raleigh = id("US: NBC 5 (WRAL) RALEIGH/DURHAM (A) ᴿᴬᵂ")
        val chicago = id("US: NBC 5 (WMAQ) CHICAGO (S) ᴿᴬᵂ")
        assertNotEquals(raleigh.rowKey, chicago.rowKey)
        assertEquals("WRAL", raleigh.callSign)
        assertEquals("WMAQ", chicago.callSign)
        assertNull(raleigh.region)
    }

    @Test fun networkMarketAndCallSign() {
        val wnbc = id("US: NBC 4 NEW YORK (WNBC)", "US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals(Network.NBC, wnbc.network)
        assertEquals("New York", wnbc.market)
        assertEquals("WNBC", wnbc.callSign)
        assertEquals(LiveGenre.NETWORK, wnbc.genre)

        val wcbs = id("TV: A3 NEW YORK 02 WCBS CBS ᴿᴬᵂ")
        assertEquals(Network.CBS, wcbs.network)
        assertEquals("New York", wcbs.market)
        assertEquals("WCBS", wcbs.callSign)

        val wral = id("US: NBC 5 (WRAL) RALEIGH/DURHAM (A) ᴿᴬᵂ")
        assertEquals(Network.NBC, wral.network)
        assertNull(wral.market)

        assertEquals("Dallas", id("US: NBC 5 (KXAS) DALLAS/FORT WORTH (A) ᴿᴬᵂ").market)
        assertEquals("Dallas", id("US: FOX 4 (KDFW) FORT WORTH HD").market)
        // "CITY, ST …" names end with an unrelated city; the station's city is the one before the comma.
        assertNull(id("TV: DAVENPORT, IA KWQC NBC 6 A3 HD LOS ANGELES ᶜᶦᵗʸ ᴿᴬᵂ").market)
        assertEquals("Chicago", id("TV: CHICAGO, IL WMAQ NBC 5 A3 HD CHICAGO ᶜᶦᵗʸ ᴿᴬᵂ").market)
        // A subchannel keeps its designation: it is not the main station.
        assertEquals("WWAY-DT2", id("US: CBS 3 (WWAY-DT2) WILMINGTON HD").callSign)

        // Network words that aren't a station.
        assertNull(id("US: NBC BRAVO (EAST) (D) ᴿᴬᵂ").network)
        assertNull(id("US: NBC BRAVO (WEST) (D) ᴿᴬᵂ").callSign)
        assertNull(id("AT&T: FOX NEWS ᴿᴬᵂ").network)
        assertEquals(Network.ABC, id("US: ABC (EAST)").network)
    }

    @Test fun genresFromChannelsAndCategories() {
        assertEquals(LiveGenre.NEWS, id("US: FOX NEWS HD", "US| FOX ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        val rock = id("24/7: 30 ROCK", "US| 24/7 COMEDY ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals(LiveGenre.COMEDY, rock.genre)
        assertEquals("30 Rock", rock.loopShow)
        assertEquals("Scarecrow and Mrs. King", id("24/7: SCARECROW AND MRS. KING", "US| 24/7 ACTION/ADVENTURE ᴿᴬᵂ ⁶⁰ᶠᵖˢ").loopShow)
        assertEquals("Met Gala 2023", id("US: 24/7 MET GALA 2023", "US| 24/7 PPV ᴿᴬᵂ ⁶⁰ᶠᵖˢ").loopShow)
        assertEquals(LiveGenre.SPORTS, id("US: ESPN HD", "US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        assertEquals(LiveGenre.SPORTS, id("AT&T: ESPNEWS ᴿᴬᵂ").genre)
        assertEquals(LiveGenre.EVENTS, id("US (ESPN+ 046) | Toronto Maple Leafs vs. Columbus Blue Jackets Oct 20 6:30PM ET (2026-10-20 18:30:20)", "US| ESPN+ PPV").genre)
        assertEquals(LiveGenre.COMEDY, id("AT&T: COMEDY CENTRAL ᴿᴬᵂ").genre)
        assertEquals(LiveGenre.MOVIES, id("US: HBO 2 EAST HD").genre)
        assertEquals(LiveGenre.MOVIES, id("24/7: DIE HARD", "US| 24/7 MOVIES/ACTORS ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        assertEquals(LiveGenre.KIDS, id("US: DISNEY CHANNEL HD").genre)
        assertEquals(LiveGenre.KIDS, id("US: METV TOONS HD", "US| KIDS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        assertEquals(LiveGenre.DOCUMENTARY, id("TV: FOOD NETWORK ᴿᴬᵂ").genre)
        assertEquals(LiveGenre.SPORTS, id("US: DAZN COMBAT", "US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        assertEquals(LiveGenre.ENTERTAINMENT, id("US: A&E HD", "US| ENTERTAINMENT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        assertEquals(LiveGenre.ENTERTAINMENT, id("24/7: CSI", "US| 24/7 CRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        // A local station's own news stream is news, not the station.
        val omaha = id("PRIME: CBS OMAHA NEWS (KMTV) ᴿᴬᵂ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals(LiveGenre.NEWS, omaha.genre)
        assertNull(omaha.callSign)
        assertNull(omaha.network)
        assertEquals("WAVY", id("US: NBC 10 (WAVY) NORFOLK/PORTSMOUTH/NEWPORT NEWS (H) ᴿᴬᵂ").callSign)
    }
}
