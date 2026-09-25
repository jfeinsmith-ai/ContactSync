package com.contactsync.app.domain

data class AccountRef(
    val name: String,
    val type: String,
) {
    val stableKey: String = "$type\u0000$name"
    override fun toString(): String = name
}

data class LabeledValue(
    val value: String,
    val type: Int,
    val label: String? = null,
)

data class ContactRecord(
    val rawContactId: Long?,
    val givenName: String?,
    val familyName: String?,
    val emails: List<LabeledValue> = emptyList(),
    val phones: List<LabeledValue> = emptyList(),
    val websites: List<LabeledValue> = emptyList(),
) {
    val displayName: String
        get() = listOfNotNull(givenName?.trim(), familyName?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Unnamed contact" }
}

enum class ClashDecision { SKIP, CREATE_SEPARATE }

data class NameClash(
    val source: ContactRecord,
    val matches: List<ContactRecord>,
    var decision: ClashDecision = ClashDecision.SKIP,
)

data class SourceDuplicateSummary(
    val contactCount: Int = 0,
    val groupCount: Int = 0,
)

data class ScanPlan(
    val sourceScanned: Int,
    val ready: List<ContactRecord>,
    val emailSkipped: List<ContactRecord>,
    val linkedInSkipped: List<ContactRecord>,
    val ambiguousSkipped: List<ContactRecord>,
    val unusableSkipped: List<ContactRecord>,
    val nameClashes: List<NameClash>,
    val sourceDuplicates: SourceDuplicateSummary = SourceDuplicateSummary(),
    val readErrors: Int = 0,
) {
    val selectedForCopy: List<ContactRecord>
        get() = ready + nameClashes
            .filter { it.decision == ClashDecision.CREATE_SEPARATE }
            .map { it.source }
}

enum class CollisionKind { NONE, EMAIL, LINKEDIN, NAME_ONLY, AMBIGUOUS, UNUSABLE }

data class CollisionResult(
    val kind: CollisionKind,
    val matches: List<ContactRecord> = emptyList(),
)
