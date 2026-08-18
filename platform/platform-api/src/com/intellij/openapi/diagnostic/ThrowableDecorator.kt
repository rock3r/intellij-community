// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.diagnostic

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus

/**
 * Replaces a logged throwable before it reaches the error pool.
 *
 * The result is what the error dialog renders, what the IDE log receives, and what an
 * [ErrorReportSubmitter] later submits, so a decorator only has to run once to affect all three.
 *
 * Register the implementation in `plugin.xml`:
 * ```xml
 * <extensions defaultExtensionNs="com.intellij">
 *   <throwableDecorator implementation="my.plugin.MyThrowableDecorator"/>
 * </extensions>
 * ```
 *
 * The motivating case is a stack trace that is complete but unreadable: frames carrying a synthetic
 * or obfuscated name that only the reporting side can resolve. A decorator can rewrite those frames
 * into real names using a mapping shipped with the product. Compose group-key stack traces are the
 * first such case — a Compose crash carries a suppressed exception whose frames read
 * `at $$compose.m$-696222513(SourceFile:1)`, and only a bundled mapping turns that into
 * `at com.example.MyScreenKt.MyScreen(MyScreen.kt:42)`.
 *
 * **Contract:** [decorate] runs on a background thread for every logged error, before anything is
 * shown to the user. Implementations must be fast, must not block, and must not log errors
 * themselves — logging an error from here re-enters the same path.
 *
 * @see ErrorReportSubmitter to change what is submitted rather than what is shown.
 */
@ApiStatus.Experimental
interface ThrowableDecorator {
  /**
   * Returns a replacement for [throwable], or `null` to leave it unchanged.
   *
   * Returning [throwable] itself is equivalent to returning `null`; implementations that mutate a
   * suppressed exception in place may do either.
   *
   * Must not throw. A decorator that throws is skipped and the previous value is kept.
   */
  fun decorate(throwable: Throwable): Throwable?

  companion object {
    val EP_NAME: ExtensionPointName<ThrowableDecorator> = ExtensionPointName.create("com.intellij.throwableDecorator")

    /**
     * Runs every registered decorator in turn, feeding each one the previous result.
     *
     * Never throws, and never returns `null`: if the extension point cannot be queried yet, or a
     * decorator fails, the best value obtained so far is returned. A misbehaving decorator must not
     * cost us the error report.
     */
    @ApiStatus.Internal
    fun decorateSafely(throwable: Throwable): Throwable {
      var result = throwable
      try {
        for (decorator in EP_NAME.extensionList) {
          try {
            result = decorator.decorate(result) ?: result
          }
          catch (e: Throwable) {
            // `warn`, not `error`: an error here would be logged through the appender that called us.
            Logger.getInstance(ThrowableDecorator::class.java).warn("${decorator.javaClass.name} failed to decorate", e)
          }
        }
      }
      catch (e: Throwable) {
        Logger.getInstance(ThrowableDecorator::class.java).warn("Cannot query throwable decorators", e)
      }
      return result
    }
  }
}
