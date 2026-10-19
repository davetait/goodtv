package com.keyspaceapps.goodtv.ui.browse

object Labels {
    /** "Sports · Home" when several sources are enabled, else just "Sports". */
    fun categoryLabel(name: String, nickname: String, multiSource: Boolean): String =
        if (multiSource) "$name · $nickname" else name
}
