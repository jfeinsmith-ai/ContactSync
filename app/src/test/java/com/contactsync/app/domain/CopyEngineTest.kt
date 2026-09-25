package com.contactsync.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyEngineTest {
    private val from = AccountRef("from@example.test", "com.google")
    private val to = AccountRef("to@example.test", "com.google")

    @Test fun `copy rejects changed account selection`() {
        val store = FakeStore(source = listOf(contact(1)), destination = emptyList())
        val run = CopyEngine(store).scan(from, to)
        val error = runCatching {
            CopyEngine(store).copy(run, to, from, { false })
        }.exceptionOrNull()
        assertTrue(error is StalePlanException)
    }

    @Test fun `copy rejects a destination changed after scan`() {
        val store = FakeStore(source = listOf(contact(1)), destination = emptyList())
        val engine = CopyEngine(store)
        val run = engine.scan(from, to)
        store.destination += contact(99, email = "new@example.test")
        assertTrue(runCatching { engine.copy(run, from, to, { false }) }.exceptionOrNull() is StalePlanException)
    }

    @Test fun `crash after insert is recovered by the next fresh scan`() {
        val source = contact(1)
        val store = FakeStore(source = listOf(source), destination = emptyList(), failAfterInsertOnce = true)
        val engine = CopyEngine(store)
        val first = engine.copy(engine.scan(from, to), from, to, { false })
        assertEquals(1, first.failed)
        assertEquals(1, store.destination.size)

        val repeated = engine.scan(from, to)
        assertEquals(1, repeated.plan.emailSkipped.size)
        assertTrue(repeated.plan.selectedForCopy.isEmpty())
    }

    @Test fun `copy cancellation is checked between contacts`() {
        val store = FakeStore(
            source = listOf(contact(1), contact(2, email = "person2@example.test")),
            destination = emptyList(),
        )
        val engine = CopyEngine(store)
        var cancel = false
        val result = engine.copy(engine.scan(from, to), from, to, { cancel }) { completed, _ ->
            if (completed == 1) cancel = true
        }
        assertTrue(result.cancelled)
        assertEquals(1, result.created)
        assertEquals(1, store.destination.size)
    }

    @Test fun `source-only duplicates are all inserted in one run`() {
        val duplicate = contact(1, email = "duplicate@example.test")
        val store = FakeStore(
            source = listOf(duplicate, duplicate.copy(rawContactId = 2)),
            destination = emptyList(),
        )
        val engine = CopyEngine(store)
        val run = engine.scan(from, to)
        assertEquals(2, run.plan.selectedForCopy.size)
        assertEquals(2, run.plan.sourceDuplicates.contactCount)

        val result = engine.copy(run, from, to, { false })
        assertEquals(2, result.created)
        assertEquals(0, result.alreadyPresent)
        assertEquals(2, store.destination.size)
    }

    @Test fun `account targeting failure stops the run`() {
        val store = FakeStore(source = listOf(contact(1)), destination = emptyList(), targetFailure = true)
        val engine = CopyEngine(store)
        val result = engine.copy(engine.scan(from, to), from, to, { false })
        assertFalse(result.cancelled)
        assertNotNull(result.fatalMessage)
        assertEquals(0, result.created)
    }

    private fun contact(id: Long, email: String = "person$id@example.test") = ContactRecord(
        rawContactId = id,
        givenName = "Person",
        familyName = id.toString(),
        emails = listOf(LabeledValue(email, 1)),
    )

    private inner class FakeStore(
        private val source: List<ContactRecord>,
        destination: List<ContactRecord>,
        private var failAfterInsertOnce: Boolean = false,
        private val targetFailure: Boolean = false,
    ) : ContactStore {
        val destination = destination.toMutableList()
        val mappings = mutableMapOf<Long, Long>()

        override fun read(account: AccountRef): ReadResult = when (account) {
            from -> ReadResult(source)
            to -> ReadResult(destination.toList())
            else -> error("Unexpected account")
        }

        override fun insert(account: AccountRef, contact: ContactRecord): Long {
            if (targetFailure) throw AccountTargetingException("Wrong account")
            val id = 100L + destination.size
            destination += contact.copy(rawContactId = id)
            if (failAfterInsertOnce) {
                failAfterInsertOnce = false
                error("Simulated crash after provider commit")
            }
            return id
        }

        override fun recordMapping(
            from: AccountRef,
            sourceRawId: Long?,
            to: AccountRef,
            destinationRawId: Long,
        ) {
            sourceRawId?.let { mappings[it] = destinationRawId }
        }
    }
}
