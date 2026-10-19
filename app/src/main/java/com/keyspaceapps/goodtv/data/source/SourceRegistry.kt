package com.keyspaceapps.goodtv.data.source

class SourceRegistry(sources: List<ChannelSource>) {
    private val byType = sources.associateBy { it.type }

    fun forType(type: SourceType): ChannelSource =
        byType[type] ?: throw IllegalArgumentException("No source for $type")
}
