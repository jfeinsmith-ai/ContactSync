package com.contactsync.app.domain

import java.security.MessageDigest

interface ContactStore {
    fun read(account: AccountRef): ReadResult
    fun insert(account: AccountRef, contact: ContactRecord): Long
    fun recordMapping(from: AccountRef, sourceRawId: Long?, to: AccountRef, destinationRawId: Long)
}

data class ReadResult(val contacts: List<ContactRecord>, val errors: Int = 0)

data class PreparedRun(
    val from: AccountRef,
    val to: AccountRef,
    val plan: ScanPlan,
    val destinationFingerprint: String,
)

data class CopyOutcome(
    val requested: Int,
    val created: Int,
    val alreadyPresent: Int,
    val failed: Int,
    val cancelled: Boolean,
    val fatalMessage: String? = null,
)

class StalePlanException(message: String) : IllegalStateException(message)
class AccountTargetingException(message: String) : IllegalStateException(message)

class CopyEngine(
    private val store: ContactStore,
    private val planner: CollisionPlanner = CollisionPlanner(),
) {
    fun scan(from: AccountRef, to: AccountRef): PreparedRun {
        require(from != to) { "FROM and TO accounts must be different." }
        val source = store.read(from)
        val destination = store.read(to)
        return PreparedRun(
            from = from,
            to = to,
            plan = planner.plan(
                source = source.contacts,
                destination = destination.contacts,
                readErrors = source.errors + destination.errors,
            ),
            destinationFingerprint = fingerprint(destination.contacts),
        )
    }

    fun copy(
        run: PreparedRun,
        selectedFrom: AccountRef,
        selectedTo: AccountRef,
        isCancelled: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): CopyOutcome {
        if (run.from != selectedFrom || run.to != selectedTo) {
            throw StalePlanException("The selected accounts changed after Scan. Scan again.")
        }
        val initialDestination = store.read(run.to)
        if (fingerprint(initialDestination.contacts) != run.destinationFingerprint) {
            throw StalePlanException("The TO account changed after Scan. Scan again before copying.")
        }

        val selected = run.plan.selectedForCopy
        val approvedNameClashes = run.plan.nameClashes
            .filter { it.decision == ClashDecision.CREATE_SEPARATE }
            .map { it.source }
            .toSet()
        var created = 0
        var alreadyPresent = 0
        var failed = 0

        selected.forEachIndexed { index, contact ->
            if (isCancelled()) {
                return CopyOutcome(selected.size, created, alreadyPresent, failed, cancelled = true)
            }

            val current = try {
                store.read(run.to).contacts
            } catch (error: Exception) {
                return CopyOutcome(
                    selected.size, created, alreadyPresent, failed, cancelled = false,
                    fatalMessage = "Could not recheck the TO account: ${error.safeMessage()}",
                )
            }
            val collision = planner.collision(contact, current)
            val mayCreateDespiteName = contact in approvedNameClashes
            when (collision.kind) {
                CollisionKind.EMAIL, CollisionKind.LINKEDIN -> {
                    alreadyPresent++
                    onProgress(index + 1, selected.size)
                    return@forEachIndexed
                }
                CollisionKind.AMBIGUOUS -> {
                    failed++
                    onProgress(index + 1, selected.size)
                    return@forEachIndexed
                }
                CollisionKind.NAME_ONLY -> if (!mayCreateDespiteName) {
                    return CopyOutcome(
                        selected.size, created, alreadyPresent, failed, cancelled = false,
                        fatalMessage = "A new name collision appeared. Scan again before continuing.",
                    )
                }
                CollisionKind.UNUSABLE -> {
                    failed++
                    onProgress(index + 1, selected.size)
                    return@forEachIndexed
                }
                CollisionKind.NONE -> Unit
            }

            try {
                val destinationId = store.insert(run.to, contact)
                created++
                store.recordMapping(run.from, contact.rawContactId, run.to, destinationId)
            } catch (error: AccountTargetingException) {
                return CopyOutcome(
                    selected.size, created, alreadyPresent, failed, cancelled = false,
                    fatalMessage = error.safeMessage(),
                )
            } catch (_: Exception) {
                failed++
            }
            onProgress(index + 1, selected.size)
        }
        return CopyOutcome(selected.size, created, alreadyPresent, failed, cancelled = false)
    }

    companion object {
        fun fingerprint(contacts: List<ContactRecord>): String {
            val canonical = contacts.map { contact ->
                listOf(
                    contact.rawContactId?.toString().orEmpty(),
                    contact.normalizedName().orEmpty(),
                    contact.normalizedEmails().sorted().joinToString(","),
                    contact.normalizedLinkedIn().sorted().joinToString(","),
                ).joinToString("|")
            }.sorted().joinToString("\n")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

private fun Throwable.safeMessage(): String = message?.take(200) ?: javaClass.simpleName

