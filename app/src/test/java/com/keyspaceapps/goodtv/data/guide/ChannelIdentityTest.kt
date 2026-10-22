package com.keyspaceapps.goodtv.data.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelIdentityTest {
    private fun id(name: String, category: String = "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ") = ChannelIdentity.of(name, category)

    /** I4(b): a category's words and genre are worked out once per category (93 of them), not once per channel. */
    @Test fun categoryWorkIsDoneOncePerCategory() {
        val news = ChannelIdentity.categoryOf("US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertSame(news, ChannelIdentity.categoryOf("US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertTrue("news" in news.words)
        assertEquals(LiveGenre.SPORTS, ChannelIdentity.categoryOf("US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        assertNull(news.genre)
    }

    @Test fun headingsAndEmptySlotsAreHidden() {
        assertTrue(id("####### NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ #######", "US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").hidden)
        assertTrue(id("- NO EVENT STREAMING - | 8K EXCLUSIVE | US: MLS PPV 1", "US| MLS PPV").hidden)
        assertTrue(id("ENDED | DET - PISTONS VS BOS - CELTICS | Tue 20 Oct 14:00 EDT (US) | 8K EXCLUSIVE | US: NBA PASS PPV 1", "US| NBA PASS PPV ⁸ᴷ").hidden)
        assertTrue(id("LIVE EVENT 03 - NO EVENT", "US| PPV EVENT").hidden)
        assertTrue(id("End | Paolini vs. Kalinina | all | 20-10-2026 | 00:00 (GMT) | 8K EXCLUSIVE | US: SOCCER PPV 2", "US| SOCCER PPV").hidden)
        assertTrue(id("Final | Rangers 3 - 2 Maple Leafs | 8K EXCLUSIVE | US: NHL PPV 4", "US| NHL PPV").hidden)
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

    @Test fun stationCopiesShareOneRow() {
        val wcbs = listOf(
            "US: CBS 2 WCBS (NEW YORK) HD", "US: CBS 2 (WCBS) NEW YORK HD", "US: CBS (WCBS)",
            "TV: A3 NEW YORK 02 WCBS CBS ᴿᴬᵂ", "TV: A3 NEW YORK 02 WCBS CBS NEW YORK ᶜᶦᵗʸ ᴿᴬᵂ",
        ).map { id(it).rowKey }
        assertEquals(1, wcbs.distinct().size)
        assertNotEquals(id("US: NBC 4 NEW YORK (WNBC)").rowKey, id("US: TELEMUNDO NBC (WNJU) ᴴᴰ").rowKey)
        assertNotEquals(id("US: CBS 3 (WWAY-DT2) WILMINGTON HD").rowKey, id("US: ABC 3 (WWAY) WILMINGTON HD").rowKey)
        val east = id("US: CBS (WCBS) (EAST)")
        val west = id("US: CBS (WCBS) WEST HD")
        assertEquals(Region.EAST, east.region)
        assertEquals(Region.WEST, west.region)
        assertNotEquals(east.rowKey, west.rowKey)
        assertNotEquals(east.rowKey, id("US: CBS (WCBS)").rowKey)
        // A city, not a feed.
        assertNull(id("US: NBC (WVIT) WEST HARTFORD, CT (D) ᴿᴬᵂ").region)
        assertEquals(id("US: NBC (WVIT) WEST HARTFORD, CT (D) ᴿᴬᵂ").rowKey, id("US: NBC 30 (WVIT) HARTFORD (H) ᴿᴬᵂ").rowKey)
    }

    @Test fun appCopiesOfStationsAreTheirOwnRow() {
        val prime = id("PRIME: FOX 7 AUSTIN ᴿᴬᵂ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        val local = id("US: FOX 7 HD [AUSTIN]", "US| FOX ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals(Network.FOX, prime.network)
        assertEquals(LiveGenre.NETWORK, prime.genre)
        assertEquals("FOX 7 AUSTIN (Prime)", prime.displayName)
        assertNotEquals(local.rowKey, prime.rowKey)
        assertNotEquals(prime.rowKey, id("RK: FOX 7 AUSTIN ᴿᴬᵂ", "US| ROKU ᴿᴬᵂ ⁶⁰ᶠᵖˢ").rowKey)
        val wabc = id("RK: WABC ABC7 NEW YORK ᴿᴬᵂ", "US| ROKU ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals(Network.ABC, wabc.network)
        assertEquals("New York", wabc.market)
        assertNull(wabc.callSign)
        assertNotEquals(id("TV: A3 NEW YORK 07 WABC ABC ᴿᴬᵂ").rowKey, wabc.rowKey)
        assertEquals(Network.ABC, id("PRIME: WSB ATLANTA (ABC) ᴿᴬᵂ").network)
        // Not a station: the app copy merges with the others.
        assertEquals(id("PRIME: HBO LATINO ᴿᴬᵂ").rowKey, id("TV: HBO LATINO ᴿᴬᵂ").rowKey)
    }

    @Test fun eventSlotsAreNotTheChannelOfTheSameName() {
        assertNotEquals(id("NBA: CHICAGO BULLS ᴴᴰ", "US| NBA TEAM PPV").rowKey, id("AT&T: CHICAGO BULLS ᴿᴬᵂ", "US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ").rowKey)
        assertEquals(id("NBA: CHICAGO BULLS ᴴᴰ", "US| NBA TEAM PPV").rowKey, id("NBA: CHICAGO BULLS SD", "US| NBA TEAM PPV").rowKey)
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
        assertEquals(Network.ABC, id("US: ABC (EAST)", "US| ABC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").network)
    }

    /** M3: the network's own name alone is its national feed only where the provider files it under that network. */
    @Test fun nationalFeedNeedsItsNetworkInTheCategory() {
        val news = id("US: ABC HD", "US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertNull(news.network)
        assertEquals(LiveGenre.NEWS, news.genre)
        assertNull(id("US: ABC (EAST)").network)
        assertNull(id("US: PBS HD", "US| ENTERTAINMENT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").network)
        assertEquals(Network.CBS, id("US: CBS HD", "US| CBS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").network)
        assertEquals(Network.CW, id("US: CW HD", "US| CW ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").network)
        // A station says what it is by its number or call sign, whatever its category.
        assertEquals(Network.NBC, id("US: NBC 4 NEW YORK (WNBC)").network)
        assertEquals(Network.ABC, id("TV: ABC7 NEW YORK ᴿᴬᵂ").network)
    }

    /** I2: what a network row without a call sign needs to be folded into its station. */
    @Test fun networkRowsCarryTheirChannelNumberAndBracketedCity() {
        val ny = id("US: ABC HD [NEW YORK]", "US| ABC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals(Network.ABC, ny.network)
        assertEquals("New York", ny.market)
        assertNull(ny.callSign)
        assertEquals(LiveGenre.NETWORK, ny.genre)
        assertEquals(7, id("TV: ABC7 NEW YORK ᴿᴬᵂ").channelNumber)
        assertEquals(7, id("US: ABC 7 NEW YORK", "US| ABC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").channelNumber)
        assertEquals(5, id("AT&T: FOX 5 LOCAL NEW YORK ᴿᴬᵂ", "US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ").channelNumber)
        assertEquals(26, id("US: CW 26 HD [CHICAGO]", "US| CW ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").channelNumber)
        // A station's own feeds: next to the network word, or before the call sign ("A3 NEW YORK 07 WABC").
        assertEquals(7, id("TV: A3 NEW YORK 07 WABC ABC ᴿᴬᵂ").channelNumber)
        assertEquals(32, id("TV: CHICAGO, IL WFLD FOX 32 A3 HD CHICAGO ᶜᶦᵗʸ ᴿᴬᵂ").channelNumber)
        assertNull(id("US: ABC (WABC)", "US| ABC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").channelNumber)
        assertNull(id("US: A&E HD").channelNumber)
    }

    /** I3: the copies [GuideMatcher] treats as one channel ("X CHANNEL", "X EAST", "THE X") are one row; West stays apart. */
    @Test fun copiesTheMatcherCallsOneChannelShareARow() {
        fun key(name: String, category: String = "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ") = id(name, category).rowKey
        assertEquals(key("US: FOX NEWS HD", "US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), key("TV: FOX NEWS CHANNEL ᴿᴬᵂ"))
        assertEquals(key("US: HALLMARK HD", "US| ENTERTAINMENT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), key("AT&T: HALLMARK CHANNEL ᴿᴬᵂ", "US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals(key("US: TENNIS HD", "US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), key("US: TENNIS CHANNEL", "US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        for (n in listOf("HBO", "STARZ", "CINEMAX", "TNT", "BRAVO", "USA NETWORK", "NICKELODEON")) {
            assertEquals(n, key("TV: $n ᴿᴬᵂ"), key("TV: $n EAST ᴿᴬᵂ"))
            assertNotEquals(n, key("TV: $n ᴿᴬᵂ"), key("TV: $n WEST ᴿᴬᵂ"))
        }
        assertEquals(key("US: THE MOVIE CHANNEL HD", "US| MOVIES ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), key("TV: THE MOVIE CHANNEL EAST ᴿᴬᵂ"))
        assertEquals(key("US: BRAVO WEST HD"), key("US: BRAVO (PACIFIC)"))
        // Loops, event slots and stations keep their own keys.
        assertNotEquals(key("24/7: TNT", "US| 24/7 DRAMA ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), key("TV: TNT ᴿᴬᵂ"))
        assertNotEquals(key("NCAAF 15: TNT", "US| NCAAF PPV"), key("TV: TNT ᴿᴬᵂ"))
        assertNotEquals(key("US: ABC (EAST)", "US| ABC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), key("US: ABC (WEST)", "US| ABC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
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
        assertEquals(LiveGenre.MOVIES, id("US: CINEMANIA ACCION", "US| CINEMANIA HOLLYWOOD ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        assertEquals(LiveGenre.ENTERTAINMENT, id("US: BLACK MIRROR 4K", "US| CINEMA TV SHOWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ").genre)
        // A local station's own news stream is news, not the station.
        val omaha = id("PRIME: CBS OMAHA NEWS (KMTV) ᴿᴬᵂ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals(LiveGenre.NEWS, omaha.genre)
        assertNull(omaha.callSign)
        assertNull(omaha.network)
        assertEquals("WAVY", id("US: NBC 10 (WAVY) NORFOLK/PORTSMOUTH/NEWPORT NEWS (H) ᴿᴬᵂ").callSign)
    }
}
