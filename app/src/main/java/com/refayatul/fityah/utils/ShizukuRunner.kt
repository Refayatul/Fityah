package com.refayatul.fityah.utils
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Utility class for executing shell commands using Shizuku
 */
class ShizukuRunner {

    interface CommandResultListener {
        /**
         * Called when the command produces output.
         * @param output The output from the command execution
         * @param done True if the command execution is complete, false otherwise
         */
        fun onCommandResult(output: String, done: Boolean) {}

        /**
         * Called when an error occurs during command execution.
         * @param error The error message
         */
        fun onCommandError(error: String) {}
    }

    companion object {

        /**
         * Executes a shell command using Shizuku.
         * @param command The shell command to execute
         * @param listener Listener for command results and errors
         * @param lineBundle Number of lines to batch before invoking the listener
         */
        fun executeCommand(command: String, listener: CommandResultListener, lineBundle: Int = 50) {
            Thread {
                try {
                    val binder = Shizuku.getBinder()
                    if (binder == null || !binder.isBinderAlive) {
                        listener.onCommandError("Shizuku is not running or accessible")
                        return@Thread
                    }

                    val process = IShizukuService.Stub.asInterface(binder)
                        .newProcess(arrayOf("sh", "-c", command), null, null)

                    val outputReader = BufferedReader(InputStreamReader(FileInputStream(process.inputStream.fileDescriptor)))
                    val errorReader = BufferedReader(InputStreamReader(FileInputStream(process.errorStream.fileDescriptor)))

                    val outputBuffer = StringBuilder()
                    val errorBuffer = StringBuilder()

                    var line: String?
                    var lineCount = 0

                    while (outputReader.readLine().also { line = it } != null) {
                        lineCount++
                        outputBuffer.append(line).append("\n")

                        // Send partial results if lineBundle is reached
                        if (lineCount == lineBundle) {
                            lineCount = 0
                            listener.onCommandResult(outputBuffer.toString(), false)
                            outputBuffer.clear()
                        }
                    }

                    while (errorReader.readLine().also { line = it } != null) {
                        errorBuffer.append(line).append("\n")
                    }

                    if (errorBuffer.isNotBlank()) {
                        listener.onCommandError(errorBuffer.toString())
                    } else {
                        listener.onCommandResult(outputBuffer.toString(), true)
                    }

                    process.waitFor()

                } catch (e: Exception) {
                    listener.onCommandError(e.message ?: "An unexpected error occurred while executing the command.")
                }
            }.start()
        }
    }
}
