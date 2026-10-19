package com.keyspaceapps.goodtv.model

/**
 * One live channel. Shaped so an Xtream Codes `get_live_streams` entry maps onto it directly
 * (stream_id -> id, name -> name, category -> group, stream_icon -> logoUrl).
 */
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val group: String? = null,
    val logoUrl: String? = null,
)

/** Public test streams so the skeleton can prove playback before a real playlist source exists. */
object SampleChannels {
    val all: List<Channel> = listOf(
        Channel(
            id = "mux-bbb",
            name = "Big Buck Bunny (Mux test stream)",
            streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            group = "Test",
        ),
        Channel(
            id = "apple-bipbop",
            name = "Apple BipBop 4x3",
            streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
            group = "Test",
        ),
        Channel(
            id = "unified-tos",
            name = "Tears of Steel (Unified Streaming)",
            streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
            group = "Test",
        ),
    )
}
