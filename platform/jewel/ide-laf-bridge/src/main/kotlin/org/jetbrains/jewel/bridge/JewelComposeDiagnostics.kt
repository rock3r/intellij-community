package org.jetbrains.jewel.bridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.jewel.foundation.InternalJewelApi

/**
 * Lets diagnostic tooling turn Compose source-information recording on for Jewel content that is already on screen.
 *
 * Setting `Composer.setDiagnosticStackTraceMode(SourceInformation)` by itself is not enough. A composition allocates
 * its source-information map once, when it is constructed, and recorded source info is merged back only if that map
 * exists. A composition created while the mode was `None` therefore never gains source info, no matter how many times
 * it recomposes — and, worse, it then reports that it *has* source information while carrying none, which suppresses
 * the group-key trace it would otherwise have produced.
 *
 * [setCollectSourceInformation] fixes both halves for Jewel-hosted content: it allocates the maps via
 * `collectParameterInformation`, and bumps an epoch so the content is re-inserted rather than merely recomposed.
 * Recording only happens while inserting, so the re-insert is what actually fills the slot table.
 *
 * This is a debugging aid, not a product feature. Turning it on costs roughly what attaching the Layout Inspector
 * costs, and re-inserting discards `remember`ed state — scroll positions, text field contents, and anything else held
 * in the composition are reset.
 */
@Internal
@InternalJewelApi
public object JewelComposeDiagnostics {
    private var epoch by mutableIntStateOf(0)
    private var collectingSourceInformation by mutableStateOf(false)

    /**
     * Turns source-information recording on or off for all Jewel Compose panels, re-inserting their content so the
     * change takes effect immediately.
     *
     * Callers are still responsible for the process-wide runtime mode (`Composer.setDiagnosticStackTraceMode`); this
     * only makes the composition side able to record. Enable the runtime mode first, then call this.
     *
     * Turning it back off is not symmetric. `collectParameterInformation` cannot be undone on a composer that has
     * already been retrofitted, so an existing panel keeps recording — and paying for it — until its composition is
     * thrown away. Passing `false` stops *new* compositions from being retrofitted and re-inserts the current ones; to
     * actually stop the cost on a panel that is already open, close and reopen it.
     */
    public fun setCollectSourceInformation(enabled: Boolean) {
        if (collectingSourceInformation == enabled) return
        collectingSourceInformation = enabled
        epoch++
    }

    /**
     * Wraps the content of a Jewel Compose panel.
     *
     * The `collectParameterInformation` call must happen inside the composition it retrofits — it acts on
     * `currentComposer` — which is why this is a composable and not a plain function.
     */
    @Composable
    internal fun RetrofittingSourceInformation(content: @Composable () -> Unit) {
        if (collectingSourceInformation) {
            currentComposer.collectParameterInformation()
        }
        key(epoch) { content() }
    }
}
