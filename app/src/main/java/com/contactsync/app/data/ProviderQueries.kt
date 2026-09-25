package com.contactsync.app.data

internal fun rawContactDataSelection(rawContactCount: Int): String {
    require(rawContactCount in 1..800)
    return "raw_contact_id IN (${List(rawContactCount) { "?" }.joinToString(",")})"
}
