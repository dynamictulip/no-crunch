package com.nocrunch.rider.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class FileChangeBatcherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun batches_paths_added_close_together() {
        val sent = LinkedBlockingQueue<List<String>>()
        val batcher = FileChangeBatcher(scope, 50) { sent.add(it) }

        batcher.add(listOf("a", "b"))
        batcher.add(listOf("b", "c"))

        assertEquals(listOf("a", "b", "c"), sent.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun paths_added_while_a_batch_is_being_sent_are_flushed_too() {
        val sent = LinkedBlockingQueue<List<String>>()
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val batcher = FileChangeBatcher(scope, 10) { batch ->
            sent.add(batch)
            if (batch == listOf("a")) { sending.countDown(); release.await(5, TimeUnit.SECONDS) }
        }

        batcher.add(listOf("a"))
        assertEquals(true, sending.await(5, TimeUnit.SECONDS))
        batcher.add(listOf("b")) // arrives after the first batch was taken, before its send returns
        release.countDown()

        assertEquals(listOf("a"), sent.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf("b"), sent.poll(5, TimeUnit.SECONDS))
    }
}
