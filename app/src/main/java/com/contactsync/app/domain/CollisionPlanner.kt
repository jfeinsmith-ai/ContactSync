package com.contactsync.app.domain

class CollisionPlanner {
    fun plan(
        source: List<ContactRecord>,
        destination: List<ContactRecord>,
        readErrors: Int = 0,
    ): ScanPlan {
        val index = IdentityIndex()
        destination.forEachIndexed { position, contact ->
            index.add("d:$position:${contact.rawContactId}", contact)
        }

        val ready = mutableListOf<ContactRecord>()
        val emailSkipped = mutableListOf<ContactRecord>()
        val linkedInSkipped = mutableListOf<ContactRecord>()
        val ambiguousSkipped = mutableListOf<ContactRecord>()
        val unusableSkipped = mutableListOf<ContactRecord>()
        val clashes = mutableListOf<NameClash>()

        source.forEach { contact ->
            when (val collision = index.collision(contact)) {
                is CollisionResult -> when (collision.kind) {
                    CollisionKind.UNUSABLE -> unusableSkipped += contact
                    CollisionKind.AMBIGUOUS -> ambiguousSkipped += contact
                    CollisionKind.EMAIL -> emailSkipped += contact
                    CollisionKind.LINKEDIN -> linkedInSkipped += contact
                    CollisionKind.NAME_ONLY -> clashes += NameClash(contact, collision.matches)
                    CollisionKind.NONE -> ready += contact
                }
            }
        }

        return ScanPlan(
            sourceScanned = source.size,
            ready = ready,
            emailSkipped = emailSkipped,
            linkedInSkipped = linkedInSkipped,
            ambiguousSkipped = ambiguousSkipped,
            unusableSkipped = unusableSkipped,
            nameClashes = clashes,
            sourceDuplicates = detectSourceDuplicates(ready),
            readErrors = readErrors,
        )
    }

    fun collision(candidate: ContactRecord, existing: List<ContactRecord>): CollisionResult {
        val index = IdentityIndex()
        existing.forEachIndexed { position, contact -> index.add("e:$position", contact) }
        return index.collision(candidate)
    }

    private class IdentityIndex {
        private data class Entry(val contact: ContactRecord)

        private val entries = mutableMapOf<String, Entry>()
        private val emails = mutableMapOf<String, MutableSet<String>>()
        private val linkedIn = mutableMapOf<String, MutableSet<String>>()
        private val names = mutableMapOf<String, MutableSet<String>>()

        fun add(id: String, contact: ContactRecord) {
            entries[id] = Entry(contact)
            contact.normalizedEmails().forEach { emails.getOrPut(it, ::mutableSetOf).add(id) }
            contact.normalizedLinkedIn().forEach { linkedIn.getOrPut(it, ::mutableSetOf).add(id) }
            contact.normalizedName()?.let { names.getOrPut(it, ::mutableSetOf).add(id) }
        }

        fun collision(contact: ContactRecord): CollisionResult {
            if (!contact.hasUsableIdentity()) return CollisionResult(CollisionKind.UNUSABLE)

            val emailMatches = contact.normalizedEmails().flatMapTo(mutableSetOf()) {
                emails[it].orEmpty()
            }
            val linkedInMatches = contact.normalizedLinkedIn().flatMapTo(mutableSetOf()) {
                linkedIn[it].orEmpty()
            }
            val identifierMatches = emailMatches + linkedInMatches
            if (identifierMatches.size > 1) {
                return CollisionResult(CollisionKind.AMBIGUOUS, contacts(identifierMatches))
            }
            if (emailMatches.isNotEmpty()) {
                return CollisionResult(CollisionKind.EMAIL, contacts(emailMatches))
            }
            if (linkedInMatches.isNotEmpty()) {
                return CollisionResult(CollisionKind.LINKEDIN, contacts(linkedInMatches))
            }

            val nameMatches = contact.normalizedName()?.let { names[it].orEmpty() }.orEmpty()
            if (nameMatches.isNotEmpty()) {
                return CollisionResult(CollisionKind.NAME_ONLY, contacts(nameMatches))
            }
            return CollisionResult(CollisionKind.NONE)
        }

        private fun contacts(ids: Collection<String>): List<ContactRecord> =
            ids.mapNotNull { entries[it]?.contact }.distinct()
    }
}

internal fun detectSourceDuplicates(contacts: List<ContactRecord>): SourceDuplicateSummary {
    if (contacts.size < 2) return SourceDuplicateSummary()
    val parents = IntArray(contacts.size) { it }

    fun root(index: Int): Int {
        var current = index
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }

    fun union(left: Int, right: Int) {
        val leftRoot = root(left)
        val rightRoot = root(right)
        if (leftRoot != rightRoot) parents[rightRoot] = leftRoot
    }

    val firstByKey = mutableMapOf<String, Int>()
    contacts.forEachIndexed { index, contact ->
        val keys = buildSet {
            contact.normalizedEmails().forEach { add("email:$it") }
            contact.normalizedLinkedIn().forEach { add("linkedin:$it") }
            contact.normalizedName()?.let { add("name:$it") }
        }
        keys.forEach { key ->
            firstByKey.putIfAbsent(key, index)?.let { previous -> union(index, previous) }
        }
    }
    val duplicateGroups = contacts.indices.groupBy(::root).values.filter { it.size > 1 }
    return SourceDuplicateSummary(
        contactCount = duplicateGroups.sumOf(List<Int>::size),
        groupCount = duplicateGroups.size,
    )
}

fun ContactRecord.normalizedEmails(): Set<String> =
    emails.mapNotNullTo(linkedSetOf()) { Normalizers.email(it.value) }

fun ContactRecord.normalizedLinkedIn(): Set<String> =
    websites.mapNotNullTo(linkedSetOf()) { Normalizers.linkedInPersonUrl(it.value) }

fun ContactRecord.normalizedName(): String? = Normalizers.structuredName(givenName, familyName)

fun ContactRecord.hasUsableIdentity(): Boolean =
    normalizedName() != null || normalizedEmails().isNotEmpty() || normalizedLinkedIn().isNotEmpty()
