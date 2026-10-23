package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Wipes the database for a test, once the lineup keeper has no build in progress. At launch the keeper may
 * be rebuilding whatever library was already on the device; the wipe resets the library's change count, and
 * the test's own writes count back up to the same number, so that build would be saved under a count that
 * looks current and never rebuilt. The guide would then show the old library's rows, all filtered out.
 */
fun AppGraph.clearDatabaseForTest() {
    runBlocking { withTimeout(120_000) { lineupKeeper.building.first { !it } } }
    db.clearAllTables()
}
