package com.gokcank.fakehunter.core.root

import com.gokcank.fakehunter.core.safeRun

/**
 * Minimal process runner with a timeout that works on API 21
 * (Process.waitFor(timeout) only exists from API 26).
 */
object ShellRunner {

    fun run(command: Array<String>, stdin: String? = null, timeoutMs: Long = 10_000L): String? {
        return safeRun("ShellRunner", null) {
            val process = ProcessBuilder(*command).redirectErrorStream(false).start()
            val output = StringBuilder()

            val reader = Thread {
                safeRun("ShellRunnerRead", Unit) {
                    process.inputStream.bufferedReader().use { br ->
                        var line = br.readLine()
                        while (line != null) {
                            synchronized(output) { output.appendLine(line) }
                            line = br.readLine()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            // Drain stderr so the process never blocks on a full pipe
            Thread {
                safeRun("ShellRunnerErr", Unit) { process.errorStream.use { it.readBytes() } }
            }.apply { isDaemon = true; start() }

            if (stdin != null) {
                safeRun("ShellRunnerStdin", Unit) {
                    process.outputStream.bufferedWriter().use { it.write(stdin) }
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            var exitCode: Int? = null
            while (exitCode == null && System.currentTimeMillis() < deadline) {
                try {
                    exitCode = process.exitValue()
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }

            if (exitCode == null) {
                process.destroy()
                return@safeRun null
            }

            reader.join(1_000L)
            if (exitCode != 0) null else synchronized(output) { output.toString() }
        }
    }
}
