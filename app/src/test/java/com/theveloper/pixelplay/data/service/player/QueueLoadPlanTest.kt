package com.theveloper.pixelplay.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueueLoadPlanTest {
    @Test
    fun currentItemIsSeparatedFromLargeQueue() {
        val items = (0 until 1_000).toList()
        val plan = requireNotNull(QueueLoadPlan.create(items, preferredIndex = 450))

        assertThat(plan.current).isEqualTo(450)
        assertThat(plan.currentIndex).isEqualTo(450)
        assertThat(plan.beforeCurrent).hasSize(450)
        assertThat(plan.afterCurrent).hasSize(549)
        assertThat(plan.beforeBatches().map(List<Int>::size)).containsExactly(200, 200, 50).inOrder()
        assertThat(plan.afterBatches().map(List<Int>::size)).containsExactly(200, 200, 149).inOrder()
    }

    @Test
    fun queueAtBatchBoundaryDoesNotCreateEmptyBatch() {
        val plan = requireNotNull(QueueLoadPlan.create((0 until 201).toList(), preferredIndex = 0))

        assertThat(plan.afterBatches().map(List<Int>::size)).containsExactly(200).inOrder()
    }

    @Test
    fun twoHundredItemQueueKeepsCurrentSeparateAndUsesOnePartialBatch() {
        val plan = requireNotNull(QueueLoadPlan.create((0 until 200).toList(), preferredIndex = 0))

        assertThat(plan.beforeBatches()).isEmpty()
        assertThat(plan.afterBatches().map(List<Int>::size)).containsExactly(199)
    }

    @Test
    fun oneThousandItemQueueAtEndUsesFiveBeforeBatchesAndNoAfterBatch() {
        val plan = requireNotNull(QueueLoadPlan.create((0 until 1_000).toList(), preferredIndex = 999))

        assertThat(plan.beforeBatches().map(List<Int>::size))
            .containsExactly(200, 200, 200, 200, 199)
            .inOrder()
        assertThat(plan.afterBatches()).isEmpty()
    }
}
