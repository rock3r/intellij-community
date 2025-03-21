// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.parcelize.ide.test

import com.intellij.testFramework.runInEdtAndWait
import org.jetbrains.kotlin.idea.quickfix.AbstractK1QuickFixTest

abstract class AbstractParcelizeK1QuickFixTest : AbstractK1QuickFixTest() {
    override fun setUp() {
        super.setUp()
        runInEdtAndWait {
            addParcelizeLibraries(module)
        }
    }

    override fun tearDown() {
        try {
            runInEdtAndWait {
                removeParcelizeLibraries(module)
            }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }
}