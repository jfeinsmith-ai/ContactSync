package com.contactsync.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizersTest {
    @Test fun `name trims normalizes unicode collapses whitespace and folds case`() {
        assertEquals("alice marie", Normalizers.name("  ＡLICE\t  Marie "))
    }

    @Test fun `email only trims and folds case`() {
        assertEquals("first.last+tag@example.test", Normalizers.email(" FIRST.Last+Tag@Example.Test "))
        assertNull(Normalizers.email("not-an-email"))
    }

    @Test fun `linkedin normalizes superficial personal profile variations`() {
        assertEquals(
            "linkedin.com/in/alice-example",
            Normalizers.linkedInPersonUrl("https://www.LinkedIn.com/in/Alice-Example/?trk=x#about"),
        )
        assertEquals(
            "linkedin.com/in/alice-example",
            Normalizers.linkedInPersonUrl("linkedin.com/us/in/alice-example/"),
        )
    }

    @Test fun `linkedin rejects non-profile pages`() {
        assertNull(Normalizers.linkedInPersonUrl("https://linkedin.com/company/example"))
        assertNull(Normalizers.linkedInPersonUrl("https://linkedin.com/posts/alice_123"))
        assertNull(Normalizers.linkedInPersonUrl("https://example.test/in/alice"))
    }
}

