/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.ktfmt.cli

import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.ParseError
import com.google.googlejavaformat.FormattingError
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private const val EXIT_CODE_FAILURE = 1
private const val EXIT_CODE_SUCCESS = 0

private val USAGE =
    """
        Usage: ktfmt [--dropbox-style | --google-style | --kotlinlang-style] [--dry-run] [--set-exit-if-changed] [--stdin-name=<name>] [--do-not-remove-unused-imports] File1.kt File2.kt ...
        Or: ktfmt @file
        """
        .trimIndent()

class Main(
    private val input: InputStream,
    private val out: PrintStream,
    private val err: PrintStream,
    private val inputArgs: Array<String>
) {
  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      exitProcess(Main(System.`in`, System.out, System.err, args).run())
    }

    /**
     * expandArgsToFileNames expands 'args' to a list of .kt files to format.
     *
     * Most commonly, 'args' is either a list of .kt files, or a name of a directory whose contents
     * the user wants to format.
     */
    fun expandArgsToFileNames(args: List<String>): List<File> {
      if (args.size == 1 && File(args[0]).isFile) {
        return listOf(File(args[0]))
      }
      val result = mutableListOf<File>()
      for (arg in args) {
        result.addAll(
            File(arg).walkTopDown().filter {
              it.isFile && (it.extension == "kt" || it.extension == "kts")
            })
      }
      return result
    }
  }

  fun run(): Int {
    val processArgs = ParsedArgs.processArgs(inputArgs)
    val parsedArgs =
        when (processArgs) {
          is ParseResult.Ok -> processArgs.parsedValue
          is ParseResult.Error -> {
            err.println(processArgs.errorMessage)
            return EXIT_CODE_FAILURE
          }
        }
    if (parsedArgs.fileNames.isEmpty()) {
      err.println(USAGE)
      return EXIT_CODE_FAILURE
    }

    if (parsedArgs.fileNames.size == 1 && parsedArgs.fileNames[0] == "-") {
      // Format code read from stdin
      return try {
        val alreadyFormatted = format(null, parsedArgs)
        if (!alreadyFormatted && parsedArgs.setExitIfChanged) EXIT_CODE_FAILURE
        else EXIT_CODE_SUCCESS
      } catch (e: Exception) {
        e.printStackTrace(err)
        EXIT_CODE_FAILURE
      }
    }

    val files: List<File> = expandArgsToFileNames(parsedArgs.fileNames)

    if (files.isEmpty()) {
      err.println("Error: no .kt files found")
      return EXIT_CODE_FAILURE
    }

    val retval = AtomicInteger(EXIT_CODE_SUCCESS)
    files.parallelStream().forEach {
      try {
        if (!format(it, parsedArgs) && parsedArgs.setExitIfChanged) {
          retval.set(EXIT_CODE_FAILURE)
        }
      } catch (e: Exception) {
        e.printStackTrace(err)
        retval.set(EXIT_CODE_FAILURE)
      }
    }
    return retval.get()
  }

  /**
   * Handles the logic for formatting and flags.
   *
   * If dry run mode is active, this simply prints the name of the [source] (file path or `<stdin>`)
   * to [out]. Otherwise, this will run the appropriate formatting as normal.
   *
   * @param file The file to format. If null, the code is read from <stdin>.
   * @return true iff input is valid and already formatted.
   */
  private fun format(file: File?, args: ParsedArgs): Boolean {
    val fileName = file?.toString() ?: args.stdinName ?: "<stdin>"
    try {
      val bytes = if (file == null) input else FileInputStream(file)
      val code = BufferedReader(InputStreamReader(bytes, UTF_8)).readText()
      val formattedCode = Formatter.format(args.formattingOptions, code)
      val alreadyFormatted = code == formattedCode

      // stdin
      if (file == null) {
        if (args.dryRun) {
          if (!alreadyFormatted) {
            out.println(fileName)
          }
        } else {
          BufferedWriter(OutputStreamWriter(out, UTF_8)).use { it.write(formattedCode) }
        }
        return alreadyFormatted
      }

      if (args.dryRun) {
        if (!alreadyFormatted) {
          out.println(fileName)
        }
      } else {
        // TODO(T111284144): Add tests
        if (!alreadyFormatted) {
          file.writeText(formattedCode, UTF_8)
        }
        err.println("Done formatting $fileName")
      }

      return alreadyFormatted
    } catch (e: IOException) {
      err.println("Error formatting $fileName: ${e.message}; skipping.")
      throw e
    } catch (e: ParseError) {
      err.println("$fileName:${e.message}")
      throw e
    } catch (e: FormattingError) {
      for (diagnostic in e.diagnostics()) {
        err.println("$fileName:$diagnostic")
      }
      e.printStackTrace(err)
      throw e
    }
  }
}
