package com.theveloper.pixelplay.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PrioritizedCandidateSelectorTest {
    @Test
    fun preservesPriorityEvenWhenLowerPriorityFinishesFirst() = runTest {
        val selected = selectFirstAvailableByPriority(
            listOf(
                suspend { delay(100); "preferred" },
                suspend { "fallback" }
            )
        )

        assertThat(selected).isEqualTo("preferred")
    }

    @Test
    fun cancelsUnneededLowerPriorityRequestsAfterSelection() = runTest {
        var lowerPriorityCancelled = false
        val selected = selectFirstAvailableByPriority(
            listOf(
                suspend { delay(100); null },
                suspend { delay(200); "selected" },
                suspend {
                    try {
                        delay(10_000)
                        "unused"
                    } finally {
                        lowerPriorityCancelled = true
                    }
                }
            )
        )

        assertThat(selected).isEqualTo("selected")
        assertThat(lowerPriorityCancelled).isTrue()
    }
}
