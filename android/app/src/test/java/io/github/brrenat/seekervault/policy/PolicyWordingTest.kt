package io.github.brrenat.seekervault.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What this app will not say about a verdict (SAW-029).
 *
 * ALLOWED means the request matched what the owner wrote down. It is not a statement that anything
 * is safe, that it has been checked for them by somebody who would know, or that it will go through
 * on its own — every request still needs their hand, in the app and again in their wallet. The
 * other verdict is the mirror of it: UNDER_RESTRICTIONS stops nothing, so nothing here is blocked.
 *
 * Wording drifts, one comfortable word at a time, and a word like *safe* next to a verdict is worth
 * more to somebody trying to get a payment past its owner than any rule is worth against them. This
 * holds every policy-facing string to the line, so the drift fails a check instead of shipping.
 */
@RunWith(AndroidJUnit4::class)
class PolicyWordingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The words, as whole words: a reassurance, a promise, or a claim to decide anything. */
    private val refused =
        listOf(
            "safe",
            "safely",
            "safer",
            "safest",
            "secure",
            "securely",
            "security",
            "automatic",
            "automatically",
            "blocked",
            "blocks",
            "denied",
            "guarantee",
            "guaranteed",
            "guarantees",
            "protect",
            "protects",
            "protected",
            "protection",
            "prevent",
            "prevents",
            "prevented",
        )

    /** Every string the owner reads about a policy, a verdict, or the step past a warning. */
    private fun policyStrings(): Map<String, String> =
        R.string::class
            .java
            .fields
            .filter { field ->
                field.name.startsWith("policy_") ||
                    field.name.startsWith("activity_policy_") ||
                    field.name == "activity_field_policy" ||
                    field.name.startsWith("approve_") ||
                    field.name == "acknowledge_despite_warnings"
            }
            .associate { it.name to context.getString(it.getInt(null)) }

    @Test
    fun noVerdictIsCalledSafeAutomaticOrBlocked() {
        val strings = policyStrings()
        assertTrue("the policy strings were found", strings.size > 100)

        for ((name, text) in strings) {
            for (word in refused) {
                assertFalse(
                    "$name says \"$word\": $text",
                    Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(text),
                )
            }
        }
    }

    @Test
    fun bothVerdictsSayInWordsThatTheOwnerStillApproves() {
        val allowed = context.getString(R.string.policy_verdict_allowed)
        val restricted = context.getString(R.string.policy_verdict_restricted)
        val underThem = context.getString(R.string.policy_review_manual)

        // The verdicts name the rules, and nothing else: what matched, or what it is under.
        assertTrue(allowed, allowed.contains("rules", ignoreCase = true))
        assertTrue(restricted, restricted.contains("restrictions", ignoreCase = true))
        // Neither reads as a decision. "Under restrictions" stops nothing, and says so by not
        // saying anything else.
        for (verdict in listOf(allowed, restricted)) {
            assertFalse(verdict, verdict.contains("approv", ignoreCase = true))
            assertFalse(verdict, verdict.contains("refus", ignoreCase = true))
        }
        // And the line that never changes is under both of them, saying who approves.
        assertTrue(underThem, underThem.contains("approve", ignoreCase = true))
        assertTrue(underThem, underThem.contains("wallet", ignoreCase = true))
    }
}
