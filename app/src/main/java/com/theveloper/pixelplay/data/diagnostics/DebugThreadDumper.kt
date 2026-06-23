package com.theveloper.pixelplay.data.diagnostics

import android.util.Log
import com.theveloper.pixelplay.BuildConfig

object DebugThreadDumper {
    private const val TAG = "DebugThreadDump"
    const val ACTION_DUMP_THREADS = "com.theveloper.pixelplay.DEBUG_DUMP_THREADS"

    fun dumpAsync(reason: String = "manual") {
        if (!BuildConfig.DEBUG) return
        Thread({
            repeat(ASYNC_SAMPLE_COUNT) { index ->
                if (index > 0) Thread.sleep(ASYNC_SAMPLE_DELAY_MS)
                dump(reason = "$reason sample=${index + 1}/$ASYNC_SAMPLE_COUNT")
            }
        }, "PixelDebugThreadDumper").start()
    }

    private fun dump(reason: String = "manual") {
        if (!BuildConfig.DEBUG) return

        val interestingNames = listOf(
            "DefaultDispatcher",
            "main",
            "RenderThread",
            "arch_disk_io",
            "WM.task",
            "PixelPluginRuntime",
            "OkHttp",
            "ExoPlayer"
        )

        val stacks = Thread.getAllStackTraces()
            .filterKeys { thread ->
                interestingNames.any { name -> thread.name.contains(name, ignoreCase = true) }
            }
            .toSortedMap(
                compareBy<Thread>({ it.name }, { it.id })
            )

        Log.w(TAG, "===== thread dump start reason=$reason count=${stacks.size} =====")
        stacks.forEach { (thread, stack) ->
            val header = "\"${thread.name}\" id=${thread.id} state=${thread.state} priority=${thread.priority}"
            Log.w(TAG, header)
            stack.take(MAX_STACK_LINES_PER_THREAD).forEach { frame ->
                Log.w(TAG, "    at $frame")
            }
            if (stack.size > MAX_STACK_LINES_PER_THREAD) {
                Log.w(TAG, "    ... ${stack.size - MAX_STACK_LINES_PER_THREAD} more")
            }
        }
        Log.w(TAG, "===== thread dump end =====")
    }

    private const val MAX_STACK_LINES_PER_THREAD = 48
    private const val ASYNC_SAMPLE_COUNT = 4
    private const val ASYNC_SAMPLE_DELAY_MS = 500L
}
