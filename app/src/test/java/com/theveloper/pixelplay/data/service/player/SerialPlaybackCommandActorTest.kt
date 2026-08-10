package com.theveloper.pixelplay.data.service.player

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class SerialPlaybackCommandActorTest {
    @Test
    fun concurrentCommandsNeverOverlap() {
        val actor = SerialPlaybackCommandActor()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)

        try {
            val futures = (0 until 40).map {
                pool.submit {
                    start.await()
                    actor.execute {
                        val nowActive = active.incrementAndGet()
                        maximumActive.accumulateAndGet(nowActive, ::maxOf)
                        Thread.sleep(2)
                        active.decrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(maximumActive.get()).isEqualTo(1)
        assertThat(active.get()).isEqualTo(0)
    }

    @Test
    fun reentrantCommandRunsInsideCurrentActorTurn() {
        val actor = SerialPlaybackCommandActor()
        val events = mutableListOf<String>()

        actor.execute {
            events += "outer-start"
            actor.execute { events += "inner" }
            events += "outer-end"
        }

        assertThat(events).containsExactly("outer-start", "inner", "outer-end").inOrder()
    }
}
