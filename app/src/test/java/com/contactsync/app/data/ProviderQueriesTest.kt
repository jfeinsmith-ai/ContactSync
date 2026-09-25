package com.contactsync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderQueriesTest {
    @Test fun `data query is scoped only by raw contact ids`() {
        val selection = rawContactDataSelection(3)
        assertEquals("raw_contact_id IN (?,?,?)", selection)
        assertFalse(selection.contains("deleted", ignoreCase = true))
        assertFalse(selection.contains("account_", ignoreCase = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty data batches are rejected`() {
        rawContactDataSelection(0)
    }
}
