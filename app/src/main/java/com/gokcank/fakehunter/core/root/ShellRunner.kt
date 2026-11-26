package com.gokcank.fakehunter.core.root

import android.os.Build
import com.gokcank.fakehunter.core.safeRun

/**
 * Memory-bounded process runner with monotonic timeout that works on API 21+.
 * Ensures streams are drained/closed and timed-out subprocesses are forcibly terminated.
 */
object ShellRunner {

    private const val DEFAULT_MAX_CHARS = 512 * 1024 // 512 KB cap to prevent OOM on low-RAM head units

    fun run(
        command: Array<String>,
        stdin: String? = null,
        timeoutMs: Long = 10_000L,
        maxChars: Int = DEFAULT_MAX_CHARS
    ): String? {
        return safeRun("ShellRunner", null) {
            val process = ProcessBuilder(*command).redirectErrorStream(false).start()
            val output = StringBuilder()
            var truncated = false

            val stdoutReader = Thread({
                safeRun("ShellRunnerRead", Unit) {
                    process.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(4096)
                        while (true) {
                            val read = reader.read(buf)
                            if (read == -1) break
                            synchronized(output) {
                                val remaining = maxChars - output.length
                                if (remaining > 0) {
                                    val toAppend = read.coerceAtMost(remaining)
                                    output.append(buf, 0, toAppend)
                                    if (toAppend < read) {
                                        truncated = true
                                    }
                                } else {
                                    truncated = true
                                }
                            }
                        }
                    }
                }
            }, "FakeHunter-Stdout").apply { isDaemon = true; start() }

            // Drain stderr with a fixed small buffer so it never blocks or allocates unbounded memory
            val stderrDrain = Thread({
                safeRun("ShellRunnerErr", Unit) {
                    process.errorStream.use { err ->
                        val scratch = ByteArray(4096)
                        while (err.read(scratch) != -1) {
                            // Discard stderr bytes safely
                        }
                    }
                }
            }, "FakeHunter-Stderr").apply { isDaemon = true; start() }

            if (stdin != null) {
                safeRun("ShellRunnerStdin", Unit) {
                    process.outputStream.bufferedWriter().use { writer ->
                        writer.write(stdin)
                        writer.flush()
                    }
                }
            } else {
                safeRun("ShellRunnerStdinClose", Unit) { process.outputStream.close() }
            }

            try {
                val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
                var exitCode: Int? = null
                while (exitCode == null && System.nanoTime() < deadlineNs) {
                    try {
                        exitCode = process.exitValue()
                    } catch (_: IllegalThreadStateException) {
                        Thread.sleep(40)
                    }
                }

                if (exitCode == null) {
                    terminateProcess(process)
                    stdoutReader.join(200L)
                    stderrDrain.join(200L)
                    return@safeRun null
                }

                stdoutReader.join(1_000L)
                stderrDrain.join(300L)

                if (exitCode != 0) {
                    null
                } else {
                    synchronized(output) {
                        if (truncated) {
                            output.append("\n[... output truncated at $maxChars chars ...]\n")
                        }
                        output.toString()
                    }
                }
            } finally {
                terminateProcess(process)
            }
        }
    }

    private fun terminateProcess(process: Process) {
        safeRun("CloseOut", Unit) { process.outputStream.close() }
        safeRun("CloseIn", Unit) { process.inputStream.close() }
        safeRun("CloseErr", Unit) { process.errorStream.close() }
        safeRun("DestroyProc", Unit) {
            process.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}
