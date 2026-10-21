package com.keyspaceapps.goodtv.data.sports

import com.keyspaceapps.goodtv.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelOrderTest {
    private fun ch(name: String, category: String, language: String? = null) =
        ChannelCandidate(LiveChannel(1, name, "c", name, null, null, language = language), category)

    @Test fun northAmericaFirstThenEnglishFrenchOthersThenLiveThenName() {
        val list = listOf(
            ch("LIVE | NHL ST. LOUIS - WINNIPEG | Wed 21 Oct 02:05 CEST (SE)", "SE| VIAPLAY PPV"),
            ch("NHL: French Language Broadcast San Jose vs Montreal @ Oct 20 7:00 PM :TVA+ 01", "CA| TVA+ PPV"),
            ch("Next | Jets vs. Blues | all | 20-10-2026 | 02:00 (GMT)", "CA| SOCCER PPV"),
            ch("Cincinnati vs. D.C. (Spanish) (2026-10-21 18:55:05)", "US| MLS PPV"),
            ch("LIVE | WINNIPEG @ ST. LOUIS | Tue 20 Oct 21:30 NDT (CA)", "CA| SPORTSNET PPV"),
            ch("NHL: Winnipeg vs St Louis @ Oct 20 8:00 PM :Sportsnet+  18", "CA| SPORTSNET+ PPV"),
        )
        assertEquals(
            listOf(
                "LIVE | WINNIPEG @ ST. LOUIS | Tue 20 Oct 21:30 NDT (CA)", // NA, English, live
                "NHL: Winnipeg vs St Louis @ Oct 20 8:00 PM :Sportsnet+  18", // NA, English, by name
                "Next | Jets vs. Blues | all | 20-10-2026 | 02:00 (GMT)", // NA, English, by name ("Next" after "NHL")
                "NHL: French Language Broadcast San Jose vs Montreal @ Oct 20 7:00 PM :TVA+ 01", // NA, French
                "Cincinnati vs. D.C. (Spanish) (2026-10-21 18:55:05)", // NA, other language
                "LIVE | NHL ST. LOUIS - WINNIPEG | Wed 21 Oct 02:05 CEST (SE)", // outside North America
            ),
            ChannelOrder.sort(list).map { it.channel.name },
        )
    }
}
