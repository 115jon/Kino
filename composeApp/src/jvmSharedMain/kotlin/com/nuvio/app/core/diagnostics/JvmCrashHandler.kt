package com.nuvio.app.core.diagnostics

import co.touchlab.kermit.Logger

internal fun installJvmCrashHandler() {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            Logger.withTag("UncaughtException").a(throwable) {
                "uncaught exception on thread=${thread.name}"
            }
        }
        CrashReporter.capture(thread.name, throwable)
        previousHandler?.uncaughtException(thread, throwable)
    }
}
