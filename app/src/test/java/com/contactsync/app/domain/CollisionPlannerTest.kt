package com.contactsync.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollisionPlannerTest {
    private val planner = CollisionPlanner()

    @Test fun `email match is skipped automatically`() {
        val destination = contact(10, "Ada", "Lovelace", email = "ada@example.test")
        val source = contact(1, "Different", "Name", email = "ADA@example.test")
        assertEquals(listOf(source), planner.plan(listOf(source), listOf(destination)).emailSkipped)
    }

    @Test fun `gmail dots and plus tags are not removed`() {
        val destination = contact(10, "Ada", "Lovelace", email = "ada.name@example.test")
        val source = contact(1, "Ada", "Else", email = "adaname+tag@example.test")
        assertEquals(listOf(source), planner.plan(listOf(source), listOf(destination)).ready)
    }

    @Test fun `linkedin profile match is skipped automatically`() {
        val destination = contact(10, "Ada", "Lovelace", linkedIn = "https://linkedin.com/in/ada")
        val source = contact(1, "Different", "Name", linkedIn = "www.linkedin.com/in/ADA/?x=1")
        assertEquals(listOf(source), planner.plan(listOf(source), listOf(destination)).linkedInSkipped)
    }

    @Test fun `structured first and last match creates default-skip review`() {
        val destination = contact(10, "  ADA", "LoveLace")
        val source = contact(1, "ada", "lovelace")
        val plan = planner.plan(listOf(source), listOf(destination))
        assertEquals(1, plan.nameClashes.size)
        assertEquals(ClashDecision.SKIP, plan.nameClashes.single().decision)
        assertTrue(plan.selectedForCopy.isEmpty())
        plan.nameClashes.single().decision = ClashDecision.CREATE_SEPARATE
        assertEquals(listOf(source), plan.selectedForCopy)
    }

    @Test fun `display-name-only contact is unusable`() {
        val source = ContactRecord(rawContactId = 1, givenName = null, familyName = null)
        assertEquals(listOf(source), planner.plan(listOf(source), emptyList()).unusableSkipped)
    }

    @Test fun `email and linkedin resolving to different contacts is ambiguous`() {
        val byEmail = contact(10, "One", "Person", email = "same@example.test")
        val byLinkedIn = contact(11, "Other", "Person", linkedIn = "linkedin.com/in/same")
        val source = contact(
            1, "Source", "Person",
            email = "same@example.test",
            linkedIn = "linkedin.com/in/same",
        )
        assertEquals(
            listOf(source),
            planner.plan(listOf(source), listOf(byEmail, byLinkedIn)).ambiguousSkipped,
        )
    }

    @Test fun `within-run email and name collisions use earlier candidates`() {
        val first = contact(1, "Ada", "Lovelace", email = "ada@example.test")
        val emailDuplicate = contact(2, "Other", "Person", email = "ADA@example.test")
        val nameDuplicate = contact(3, "ada", "lovelace", email = "other@example.test")
        val plan = planner.plan(listOf(first, emailDuplicate, nameDuplicate), emptyList())
        assertEquals(listOf(first), plan.ready)
        assertEquals(listOf(emailDuplicate), plan.emailSkipped)
        assertEquals(listOf(nameDuplicate), plan.nameClashes.map { it.source })
    }

    @Test fun `repeated plan skips a previously inserted contact`() {
        val source = contact(1, "Ada", "Lovelace", email = "ada@example.test")
        val first = planner.plan(listOf(source), emptyList())
        assertEquals(listOf(source), first.ready)
        val inserted = source.copy(rawContactId = 99)
        val repeated = planner.plan(listOf(source), listOf(inserted))
        assertEquals(listOf(source), repeated.emailSkipped)
    }

    @Test fun `account reversal changes which side is copied`() {
        val left = contact(1, "Left", "Only", email = "left@example.test")
        val right = contact(2, "Right", "Only", email = "right@example.test")
        assertEquals(listOf(left), planner.plan(listOf(left), listOf(right)).ready)
        assertEquals(listOf(right), planner.plan(listOf(right), listOf(left)).ready)
    }

    private fun contact(
        id: Long,
        first: String,
        last: String,
        email: String? = null,
        linkedIn: String? = null,
    ) = ContactRecord(
        rawContactId = id,
        givenName = first,
        familyName = last,
        emails = email?.let { listOf(LabeledValue(it, 1)) }.orEmpty(),
        websites = linkedIn?.let { listOf(LabeledValue(it, 1)) }.orEmpty(),
    )
}

