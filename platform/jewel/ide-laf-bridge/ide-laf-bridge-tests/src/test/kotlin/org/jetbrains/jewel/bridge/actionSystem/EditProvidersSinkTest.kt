// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import com.intellij.ide.CopyProvider
import com.intellij.ide.CutProvider
import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * The root data provider must sink all three semantic edit providers, so the platform `$Copy`, `$Cut`, and `$Paste`
 * actions enable and perform against focused Compose semantics without any Jewel-specific action registration.
 */
internal class EditProvidersSinkTest {
    @JvmField @Rule internal val rule: ComposeContentTestRule = createComposeRule()

    @Test
    fun `root snapshot sinks copy, cut, and paste providers`() {
        runBlocking {
            val rootDataProviderModifier = RootDataProviderModifier()
            rule.setContent { Box(modifier = rootDataProviderModifier.focusable()) }
            rule.awaitIdle()

            val sink = TestDataSink()
            rootDataProviderModifier.uiDataSnapshot(sink)

            assertNotNull(sink.get<CopyProvider>(PlatformDataKeys.COPY_PROVIDER.name))
            assertNotNull(sink.get<CutProvider>(PlatformDataKeys.CUT_PROVIDER.name))
            assertNotNull(sink.get<PasteProvider>(PlatformDataKeys.PASTE_PROVIDER.name))
        }
    }
}
