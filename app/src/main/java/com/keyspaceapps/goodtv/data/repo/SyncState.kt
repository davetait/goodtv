package com.keyspaceapps.goodtv.data.repo

sealed interface SyncState {
    data object Idle : SyncState
    data class Syncing(val sourceIds: Set<Long>) : SyncState
    data class Error(val sourceId: Long, val message: String) : SyncState
}
