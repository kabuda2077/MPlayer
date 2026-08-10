package com.theveloper.pixelplay.data.service.player

data class QueueLoadPlan<T>(
    val beforeCurrent: List<T>,
    val current: T,
    val afterCurrent: List<T>,
    val currentIndex: Int
) {
    fun beforeBatches(batchSize: Int = DEFAULT_BATCH_SIZE): List<List<T>> =
        beforeCurrent.chunked(requirePositiveBatchSize(batchSize))

    fun afterBatches(batchSize: Int = DEFAULT_BATCH_SIZE): List<List<T>> =
        afterCurrent.chunked(requirePositiveBatchSize(batchSize))

    companion object {
        const val DEFAULT_BATCH_SIZE = 200

        fun <T> create(
            items: List<T>,
            preferredIndex: Int
        ): QueueLoadPlan<T>? {
            if (items.isEmpty()) return null
            val currentIndex = preferredIndex.takeIf { it in items.indices } ?: 0
            return QueueLoadPlan(
                beforeCurrent = items.subList(0, currentIndex),
                current = items[currentIndex],
                afterCurrent = items.subList(currentIndex + 1, items.size),
                currentIndex = currentIndex
            )
        }

        private fun requirePositiveBatchSize(batchSize: Int): Int {
            require(batchSize > 0) { "Queue batch size must be positive" }
            return batchSize
        }
    }
}
