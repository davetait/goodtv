package com.keyspaceapps.goodtv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inputs are names from a real panel. */
class DisplayTitleTest {
    private fun vod(raw: String, label: String) = assertEquals("'$raw'", label, DisplayTitle.parse(raw).label)
    private fun live(raw: String, label: String) = assertEquals("'$raw'", label, DisplayTitle.parse(raw, live = true).label)

    @Test fun moviesKeepTitleYearLanguageAndQuality() {
        vod("EN - The Matrix (1999)", "The Matrix (1999) (EN)")
        vod("4K-AR - The Matrix (1999)", "The Matrix (1999) (AR · 4K)")
        vod("4K-DE - Matrix  (1999)", "Matrix (1999) (DE · 4K)")
        vod("4K-FR-HDR - Spider-Man: Brand New Day (2026)", "Spider-Man: Brand New Day (2026) (FR · 4K)")
        vod("AR-SUBS - Love in 39 Degrees (2024)", "Love in 39 Degrees (2024) (AR-SUB)")
        vod("AR-DUB - LEGO Friends: Heartlake the Musical (2026)", "LEGO Friends: Heartlake the Musical (2026) (AR-DUB)")
        vod("IN-TL - Hi.2026", "Hi (2026) (IN-TL)")
        vod("AF-FR - Shenanigans.2018", "Shenanigans (2018) (AF-FR)")
        vod("AF-EN - When Sparks Fly 2024", "When Sparks Fly (2024) (AF-EN)")
        vod("IR - What We Dreamed Of Then (2026) آنچه آن روزها در رویایش", "What We Dreamed Of Then (2026) (IR)")
        vod("KU-S - Dragon Ball Super: Super Hero (2022) دراگۆن بۆڵ سوپەر", "Dragon Ball Super: Super Hero (2022) (KU-SUB)")
    }

    @Test fun platformAndCategoryTagsAreDroppedNotShown() {
        vod("NF - Ouija: Origin of Evil (2016)", "Ouija: Origin of Evil (2016)")
        vod("4K-NF - The Whisper Man (2026)", "The Whisper Man (2026) (4K)")
        vod("D+ - An Almost Christmas Story (2024)", "An Almost Christmas Story (2024)")
        vod("TOP - Practical Magic 2 (2026)", "Practical Magic 2 (2026)")
        vod("SOC - Fulham - Arsenal 08.12.2024", "Fulham - Arsenal 08.12.2024")
    }

    @Test fun seriesDropTheProductionCountry() {
        vod("EN - Strip the Cosmos (2014) (US)", "Strip the Cosmos (2014) (EN)")
        vod("AR-SUBS - Broadchurch (2013) (GB)", "Broadchurch (2013) (AR-SUB)")
        vod("4K-A+ - Bad Monkey (2024) (US)", "Bad Monkey (2024) (4K)")
        vod("AR-TR-S - Halef: Köklerin Çağrısı (2025) الخليفة", "Halef: Köklerin Çağrısı (2025) (AR-TR-SUB)")
        vod("IL: Ambiguity בהסתורה (2026) (IL)", "Ambiguity בהסתורה (2026) (IL)")
    }

    @Test fun titlesThatLookLikeTagsOrYearsSurvive() {
        vod("FBI: Most Wanted (2020)", "FBI: Most Wanted (2020)")
        vod("Blade Runner 2049", "Blade Runner 2049") // a future "year" is part of the title
        vod("Act 01", "Act 01")
        vod("Up", "Up")
        vod("AR - أحمد زكي :  ناصر 56", "أحمد زكي : ناصر 56 (AR)")
    }

    @Test fun channelsDropBundlesAndDecorations() {
        live("UK: SKY SPORT NEWS ᴿᴬᵂ", "SKY SPORT NEWS (UK)")
        live("US: 13 REASONS WHY ᴿᴬᵂ", "13 REASONS WHY (US)")
        live("VIP: TF1 ᴿᴬᵂ", "TF1")
        live("ES: M.LALIGA ᴴᴰᴿ ³⁸⁴⁰ᴾ", "M.LALIGA (ES · 4K)")
        live("CA EN: GLOBAL TORONTO HD", "GLOBAL TORONTO (CA-EN)")
        live("IN-PREM: 2020-2022 2 HD", "2020-2022 2 (IN)")
        live("PL VIP: TVP 1 ᴿᴬᵂ", "TVP 1 (PL)")
        live("24/7: ADAM DRIVER", "ADAM DRIVER")
        live("AR 4K: MOVIES 1 UHD 3840P", "MOVIES 1 (AR · 4K)")
        live("NEXT | THE RAPTORS SHOW: OCT. 20", "THE RAPTORS SHOW: OCT. 20")
        live("VIX: CINE EXTREMO ᴿᴬᵂ", "CINE EXTREMO")
        live("TELUGU: ETV PLUS", "ETV PLUS (TE)")
        live("ARG: TELEFE", "TELEFE (ARG)")
        live("CA EN: CBC FREDERICTON (HD)", "CBC FREDERICTON (CA-EN)")
    }

    @Test fun irregularAndRepeatedMovieTags() {
        vod("NL - NL - Spotlight (2015)", "Spotlight (2015) (NL)")
        vod("4K-AR - AR- The Green Mile (1999)", "The Green Mile (1999) (AR · 4K)")
        vod("AR-SUBS - AR  - Bosch", "Bosch (AR-SUB)")
        vod("SE- Quick (2019)", "Quick (2019) (SE)")
        vod("AR-SUBS: Secret of the Wings (2012)", "Secret of the Wings (2012) (AR-SUB)")
        vod("4K-SC: Apples Never Fall (US)", "Apples Never Fall (SC · 4K)")
        vod("QC - Le négociateur", "Le négociateur (QC)")
        vod("DE - Protocol: Rain (2023) (Ger Sub)", "Protocol: Rain (2023) (DE)")
        // A second colon "tag" is a real title: "It: Welcome to Derry".
        vod("PT - IT: Bem-Vindos a Derry (2025) (US)", "IT: Bem-Vindos a Derry (2025) (PT)")
        vod("DE - MR-9: Secret Agent (2023)", "MR-9: Secret Agent (2023) (DE)")
        vod("ES - TRON: Ares (2025)", "TRON: Ares (2025) (ES)")
        // "None" stands in for a missing year on some panels, but is also a real word in titles.
        vod("AF-FR - PREDICTION.None", "PREDICTION (AF-FR)")
        vod("AF-FR - 99.éme.VIK.TEAM.None", "99 éme VIK TEAM (AF-FR)")
        vod("AS - None Shall Escape (2026)", "None Shall Escape (2026) (AS)")
    }

    @Test fun channelNamesWithoutACapsTagAreLeftAlone() {
        live("Channel One", "Channel One")
        live(":Tennis  33", ":Tennis 33")
        live("### ARABIC 24/7 4K UHD 3840P ###", "### ARABIC 24/7 4K UHD 3840P ###")
    }

    @Test fun episodes() {
        assertNull(DisplayTitle.episodeTitle("FR - Breaking Bad - S01E01", "Breaking Bad"))
        assertEquals("Pilot", DisplayTitle.episodeTitle("EN - Breaking Bad - S01E01 - Pilot", "Breaking Bad"))
        assertEquals("The One Where It All Began", DisplayTitle.episodeTitle("The One Where It All Began", "Friends"))
        assertEquals("the show's tag and the gap left by the code go", "System", DisplayTitle.episodeTitle("EN - The Bear (US) - S01E01 - System", "The Bear"))
        assertEquals("Part 1 - The Return", DisplayTitle.episodeTitle("EN - Show - S02E03 - Part 1 - The Return", "Show"))
    }
}
