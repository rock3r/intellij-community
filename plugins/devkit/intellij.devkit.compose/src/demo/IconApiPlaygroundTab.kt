@file:Suppress("HardCodedStringLiteral")
@file:OptIn(ExperimentalJewelApi::class)

package com.intellij.devkit.compose.demo

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.Nls
import org.jetbrains.icons.DeferredIcon
import org.jetbrains.icons.Icon
import org.jetbrains.icons.IconManager
import org.jetbrains.icons.deferredIcon
import org.jetbrains.icons.design.BlendMode
import org.jetbrains.icons.design.Circle
import org.jetbrains.icons.design.IconAlign
import org.jetbrains.icons.design.RGBA
import org.jetbrains.icons.design.Rectangle
import org.jetbrains.icons.design.badge
import org.jetbrains.icons.design.dp as iconDp
import org.jetbrains.icons.design.percent
import org.jetbrains.icons.icon
import org.jetbrains.icons.modifiers.IconModifier
import org.jetbrains.icons.modifiers.align
import org.jetbrains.icons.modifiers.fillMaxSize
import org.jetbrains.icons.modifiers.size
import org.jetbrains.icons.modifiers.tintColor
import org.jetbrains.icons.swing.swingIcon
import org.jetbrains.icons.swing.toNewIcon
import org.jetbrains.icons.swing.toSwingIcon
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.CodeHighlighter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.typography
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.SwingConstants
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.jewel.ui.component.Icon as JewelIcon

@Composable
internal fun IconApiPlaygroundTab(codeHighlighter: CodeHighlighter) {
  val scope = rememberCoroutineScope()
  var deferredGeneration by remember { mutableIntStateOf(0) }
  var deferredStatus by remember { mutableStateOf("Placeholder is visible. It resolves after about 1.5 seconds.") }

  val layeredResourceIcon = remember { createLayeredResourceIcon() }
  val layoutIcon = remember { createLayoutIcon() }
  val shapeIntrinsicProbeIcon = remember { createShapeIntrinsicProbeIcon() }
  val shapeIntrinsicProbeReportedSize = remember(shapeIntrinsicProbeIcon) { reportedSize(shapeIntrinsicProbeIcon) }
  val rowSpacingProbeIcon = remember { createRowSpacingProbeIcon() }
  val rowSpacingProbeReportedSize = remember(rowSpacingProbeIcon) { reportedSize(rowSpacingProbeIcon) }
  val boxModifierProbeIcon = remember { createBoxModifierProbeIcon() }
  val boxChildModifierReferenceIcon = remember { createBoxChildModifierReferenceIcon() }
  val interopIcon = remember { AllIcons.Actions.NewFolder.toNewIcon(IconModifier.fillMaxSize()) }
  val deferredDemoIcon = remember(deferredGeneration) { createDeferredPlaygroundIcon(deferredGeneration) }

  LaunchedEffect(deferredGeneration) {
    deferredStatus = "Placeholder is visible. It resolves after about 1.5 seconds."
  }

  VerticallyScrollableContainer {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("New Icons API playground", style = JewelTheme.typography.h3TextStyle)
      Text(
        "A throwaway tab for trying the new API from a Jewel tool window. Each preview renders the same icon in Jewel and Swing so " +
        "the runtime behavior is easy to compare.",
        color = JewelTheme.globalColors.text.info,
      )

      PlaygroundSection(
        title = "Entry points",
        description = "These are the pieces worth copy-pasting first when you start experimenting.",
      ) {
        CodeBlock(ENTRY_POINTS_SNIPPET, codeHighlighter)
      }

      PlaygroundSection(
        title = "Layered DSL: image() + badge()",
        description = "Build an icon description from layers, then render it in either Compose/Jewel or Swing.",
      ) {
        CodeBlock(LAYERED_SNIPPET, codeHighlighter)
        IconPreviewRow(
          icon = layeredResourceIcon,
          note = "This one loads an SVG resource from the plugin classloader, then overlays a badge on top.",
        )
      }

      PlaygroundSection(
        title = "Layout + modifiers",
        description = "row(), column(), tintColor(), and the layout units are enough to build composite icons without dropping to custom painters.",
      ) {
        CodeBlock(LAYOUT_SNIPPET, codeHighlighter)
        IconPreviewRow(
          icon = layoutIcon,
          note = "This preview is a single icon description composed from four tinted legacy icons.",
        )
      }

      PlaygroundSection(
        title = "Review repros: measurement / layout mismatches",
        description = "Non-crashing probes for the issues that stood out in review. The screenshots from these are meant to " +
                      "be attached to PR comments.",
      ) {
        ReproCase(
          title = "Shape icon reports zero intrinsic size",
          description = "The icon reports zero intrinsic/Swing size even though the model clearly describes a visible 12dp rectangle.",
          reportedSize = shapeIntrinsicProbeReportedSize,
        ) {
          Text("No live preview here on purpose: the Compose/Jewel path currently crashes on this probe, which is itself part of the problem.", color = JewelTheme.globalColors.text.info)
        }

        ReproCase(
          title = "Row spacing is not reflected in intrinsic width",
          description = "These Swing previews use the same icon under two host sizes. The reported size stays fixed, but the rendered layout redistributes the available width into equal slots.",
          reportedSize = rowSpacingProbeReportedSize,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            PreviewSurface(title = "Swing 32dp host", modifier = Modifier.weight(1f)) {
              SwingIconPreview(rowSpacingProbeIcon.toSwingIcon(), PreviewIconSize)
            }
            PreviewSurface(title = "Swing 48dp host", modifier = Modifier.weight(1f)) {
              SwingIconPreview(rowSpacingProbeIcon.toSwingIcon(), 48.dp)
            }
          }
        }

        ReproCase(
          title = "Box modifiers do not seem to constrain children",
          description = "The two Swing previews below should be much closer. The left applies size/alignment on the box layer, the right applies the same modifier directly to the child as a reference.",
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            PreviewSurface(title = "Modifier on box", modifier = Modifier.weight(1f)) {
              SwingIconPreview(boxModifierProbeIcon.toSwingIcon(), PreviewIconSize)
            }
            PreviewSurface(title = "Reference: modifier on child", modifier = Modifier.weight(1f)) {
              SwingIconPreview(boxChildModifierReferenceIcon.toSwingIcon(), PreviewIconSize)
            }
          }
        }
      }

      PlaygroundSection(
        title = "Swing interop",
        description = "The old API can be wrapped, and the new API can still be pushed back to Swing when needed.",
      ) {
        CodeBlock(INTEROP_SNIPPET, codeHighlighter)
        IconPreviewRow(
          icon = interopIcon,
          note = "Left: Jewel renders the new icon model directly. Right: Swing renders icon.toSwingIcon().",
        )
      }

      PlaygroundSection(
        title = "Deferred evaluation",
        description = "Recreate the icon to replay placeholder -> resolved behavior. The force button calls IconManager.forceEvaluation().",
      ) {
        CodeBlock(DEFERRED_SNIPPET, codeHighlighter)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { deferredGeneration++ }) {
            Text("Create new deferred icon")
          }
          DefaultButton(
            onClick = {
              scope.launch {
                deferredStatus = "Forcing evaluation…"
                IconManager.getInstance().forceEvaluation(deferredDemoIcon)
                deferredStatus = "Force evaluation finished. Both previews should now show the resolved icon."
              }
            }
          ) {
            Text("Force evaluate now")
          }
        }
        Text(deferredStatus, color = JewelTheme.globalColors.text.info)
        IconPreviewRow(
          icon = deferredDemoIcon,
          note = "The identifier controls caching. Reusing the same id may skip the placeholder if the result was already resolved.",
        )
      }
    }
  }
}

@Composable
private fun PlaygroundSection(@Nls title: String, description: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    GroupHeader(title)
    Text(description, color = JewelTheme.globalColors.text.info)
    content()
  }
}

@Composable
private fun ReproCase(
  @Nls title: String,
  description: String,
  reportedSize: String? = null,
  content: @Composable () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = JewelTheme.typography.medium)
    Text(description, color = JewelTheme.globalColors.text.info)
    reportedSize?.let {
      Text(it, color = JewelTheme.globalColors.text.info)
    }
    content()
  }
}

@Composable
private fun ProbeHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(
    modifier = modifier.border(1.dp, JewelTheme.globalColors.borders.normal).padding(4.dp),
    contentAlignment = Alignment.Center,
    content = content,
  )
}

@Composable
private fun CodeBlock(code: String, codeHighlighter: CodeHighlighter) {
  SelectionContainer {
    Box(
      modifier = Modifier.fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal).padding(12.dp)
    ) {
      val highlightedCode by codeHighlighter.highlight(code, "kotlin")
        .collectAsState(AnnotatedString(code))

      Text(highlightedCode, style = JewelTheme.editorTextStyle)
    }
  }
}

@Composable
private fun IconPreviewRow(icon: Icon, note: String) {
  val swingIcon = remember(icon) { icon.toSwingIcon() }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PreviewSurface(title = "Jewel", modifier = Modifier.weight(1f)) {
        JewelIcon(icon = icon, contentDescription = null, modifier = Modifier.size(PreviewIconSize))
      }
      PreviewSurface(title = "Swing", modifier = Modifier.weight(1f)) {
        SwingIconPreview(swingIcon, PreviewIconSize)
      }
    }
    Text(note, color = JewelTheme.globalColors.text.info)
  }
}

@Composable
private fun PreviewSurface(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  Column(
    modifier = modifier.border(1.dp, JewelTheme.globalColors.borders.normal).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(title, style = JewelTheme.typography.medium)
    Box(
      modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
      contentAlignment = Alignment.Center,
      content = content,
    )
  }
}

@Composable
private fun SwingIconPreview(icon: javax.swing.Icon, size: Dp) {
  val sizePx = with(LocalDensity.current) { size.roundToPx() }

  SwingPanel(
    factory = {
      JLabel(icon).apply {
        isOpaque = false
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        preferredSize = Dimension(sizePx, sizePx)
        minimumSize = preferredSize
        maximumSize = preferredSize
        setSize(sizePx, sizePx)
      }
    },
    modifier = Modifier.size(size),
  )
}

private fun createLayeredResourceIcon(): Icon = icon {
  image(
    path = "icons/jewelToolWindow.svg",
    classLoader = IconApiPlaygroundResources::class.java.classLoader,
    modifier = IconModifier.fillMaxSize(),
  )
  badge(DemoGreen, Circle)
}

private fun createLayoutIcon(): Icon {
  val checkoutIcon = AllIcons.Actions.CheckOut.toNewIcon(IconModifier.fillMaxSize())
  val closeIcon = AllIcons.Actions.Close.toNewIcon(IconModifier.fillMaxSize())

  return icon {
    column(spacing = 8.percent, modifier = IconModifier.fillMaxSize()) {
      row(spacing = 8.percent) {
        icon(checkoutIcon, modifier = IconModifier.tintColor(DemoBlue, BlendMode.Color))
        icon(closeIcon, modifier = IconModifier.tintColor(DemoPurple, BlendMode.Color))
      }
      row(spacing = 8.percent) {
        icon(closeIcon, modifier = IconModifier.tintColor(DemoAmber, BlendMode.Color))
        icon(checkoutIcon, modifier = IconModifier.tintColor(DemoGreen, BlendMode.Color))
      }
    }
  }
}

private fun createShapeIntrinsicProbeIcon(): Icon = icon {
  shape(
    color = DemoBlue,
    shape = Rectangle,
    modifier = IconModifier.size(12.iconDp),
  )
}

private fun createRowSpacingProbeIcon(): Icon {
  val checkoutIcon = AllIcons.Actions.CheckOut.toNewIcon()
  val closeIcon = AllIcons.Actions.Close.toNewIcon()

  return icon {
    row(spacing = 25.percent) {
      icon(checkoutIcon)
      icon(closeIcon)
    }
  }
}

private fun createBoxModifierProbeIcon(): Icon {
  val warningIcon = AllIcons.General.Warning.toNewIcon(IconModifier.fillMaxSize())

  return icon {
    box(modifier = IconModifier.size(50.percent).align(IconAlign.BottomRight)) {
      icon(warningIcon)
    }
  }
}

private fun createBoxChildModifierReferenceIcon(): Icon {
  val warningIcon = AllIcons.General.Warning.toNewIcon(IconModifier.fillMaxSize())

  return icon {
    box {
      icon(warningIcon, modifier = IconModifier.size(50.percent).align(IconAlign.BottomRight))
    }
  }
}

private fun createDeferredPlaygroundIcon(sequence: Int): DeferredIcon {
  val placeholder = AllIcons.General.Warning.toNewIcon(IconModifier.fillMaxSize())

  return deferredIcon(
    placeholder = placeholder,
    identifier = "devkit.compose.icons.playground.$sequence",
    classLoader = IconApiPlaygroundResources::class.java.classLoader,
  ) {
    delay(1500.milliseconds)
    icon {
      swingIcon(AllIcons.General.Warning, modifier = IconModifier.fillMaxSize())
      badge(DemoGreen, Circle)
    }
  } as DeferredIcon
}

private fun reportedSize(icon: Icon): String {
  val swingIcon = icon.toSwingIcon()
  return "Reported Swing size: ${swingIcon.iconWidth} × ${swingIcon.iconHeight}"
}

private val PreviewIconSize = 32.dp

private object IconApiPlaygroundResources

private val DemoGreen = RGBA(0.22f, 0.71f, 0.39f, 1f)
private val DemoBlue = RGBA(0.24f, 0.55f, 0.96f, 1f)
private val DemoPurple = RGBA(0.67f, 0.39f, 0.91f, 1f)
private val DemoAmber = RGBA(0.98f, 0.72f, 0.18f, 1f)

@Language("Kotlin")
private val ENTRY_POINTS_SNIPPET = """
val icon: Icon = icon { /* describe layers */ }
val deferred: Icon = deferredIcon(placeholder, identifier = "sample") { /* suspend */ }

JewelIcon(icon = icon, contentDescription = null)
val swingIcon: javax.swing.Icon = icon.toSwingIcon()
""".trimIndent()

@Language("Kotlin")
private val LAYERED_SNIPPET = """
val icon = icon {
  image(
    "icons/jewelToolWindow.svg",
    IconApiPlaygroundResources::class.java.classLoader,
    modifier = IconModifier.fillMaxSize(),
  )
  badge(RGBA(0.22f, 0.71f, 0.39f, 1f), Circle)
}
""".trimIndent()

@Language("Kotlin")
private val LAYOUT_SNIPPET = """
val checkout = AllIcons.Actions.CheckOut.toNewIcon(IconModifier.fillMaxSize())
val close = AllIcons.Actions.Close.toNewIcon(IconModifier.fillMaxSize())

val icon = icon {
  column(spacing = 8.percent, modifier = IconModifier.fillMaxSize()) {
    row(spacing = 8.percent) {
      icon(checkout, modifier = IconModifier.tintColor(RGBA(0.24f, 0.55f, 0.96f, 1f), BlendMode.Color))
      icon(close, modifier = IconModifier.tintColor(RGBA(0.67f, 0.39f, 0.91f, 1f), BlendMode.Color))
    }
    row(spacing = 8.percent) {
      icon(close, modifier = IconModifier.tintColor(RGBA(0.98f, 0.72f, 0.18f, 1f), BlendMode.Color))
      icon(checkout, modifier = IconModifier.tintColor(RGBA(0.22f, 0.71f, 0.39f, 1f), BlendMode.Color))
    }
  }
}
""".trimIndent()

@Language("Kotlin")
private val INTEROP_SNIPPET = """
val newIcon = AllIcons.Actions.NewFolder.toNewIcon(IconModifier.fillMaxSize())

JewelIcon(icon = newIcon, contentDescription = null)
val swingIcon: javax.swing.Icon = newIcon.toSwingIcon()
""".trimIndent()

@Language("Kotlin")
private val DEFERRED_SNIPPET = """
val placeholder = AllIcons.General.Warning.toNewIcon(IconModifier.fillMaxSize())

val icon = deferredIcon(
  placeholder = placeholder,
  identifier = "devkit.compose.icons.playground",
  classLoader = IconApiPlaygroundResources::class.java.classLoader,
) {
  delay(1500)
  icon {
    swingIcon(AllIcons.General.Warning, modifier = IconModifier.fillMaxSize())
    badge(RGBA(0.22f, 0.71f, 0.39f, 1f), Circle)
  }
}
""".trimIndent()
