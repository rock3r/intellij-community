package org.jetbrains.jewel.samples.standalone.view.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.HeightAutoSizingTextArea
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

@Suppress("SpellCheckingInspection")
private const val LOREM_IPSUM =
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit. \n" +
        "Sed auctor, neque in accumsan vehicula, enim purus vestibulum odio, non tristique dolor quam vel ipsum. \n" +
        "Proin egestas, orci id hendrerit bibendum, nisl neque imperdiet nisl, a euismod nibh diam nec lectus. \n" +
        "Duis euismod, quam nec aliquam iaculis, dolor lorem bibendum turpis, vel malesuada augue sapien vel mi. \n" +
        "Quisque ut facilisis nibh. Maecenas euismod hendrerit sem, ac scelerisque odio auctor nec. \n" +
        "Sed sit amet consequat eros. Donec nisl tellus, accumsan nec ligula in, eleifend sodales sem. \n" +
        "Sed malesuada, nulla ac eleifend fermentum, nibh mi consequat quam, quis convallis lacus nunc eu dui. \n" +
        "Pellentesque eget enim quis orci porttitor consequat sed sed quam. \n" +
        "Sed aliquam, nisl et lacinia lacinia, diam nunc laoreet nisi, sit amet consectetur dolor lorem et sem. \n" +
        "Duis ultricies, mauris in aliquam interdum, orci nulla finibus massa, a tristique urna sapien vel quam. \n" +
        "Sed nec sapien nec dui rhoncus bibendum. Sed blandit bibendum libero."

@Composable
fun TextAreas() {
    AutoSizingTextArea()
    //    VerticallyScrollableContainer(Modifier.fillMaxSize()) {
    //        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    //            NormalTextAreas()
    //            GroupHeader("Read-only")
    //            ReadOnlyTextAreas()
    //            GroupHeader("Auto-sizing")
    //        }
    //    }
}

@Composable
private fun NormalTextAreas() {
    Row(
        Modifier.padding(horizontal = 16.dp).height(200.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TextArea(state = rememberTextFieldState(LOREM_IPSUM), modifier = Modifier.weight(1f).fillMaxHeight())

        TextArea(
            state = rememberTextFieldState(LOREM_IPSUM),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            enabled = false,
        )

        TextArea(
            state = rememberTextFieldState(""),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            outline = Outline.Error,
            placeholder = { Text("Text area with error") },
        )

        TextArea(
            state = rememberTextFieldState(""),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            outline = Outline.Warning,
            placeholder = { Text("Text area with warning") },
        )
    }
}

@Composable
private fun ReadOnlyTextAreas() {
    Row(
        Modifier.padding(horizontal = 16.dp).height(200.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TextArea(
            state = rememberTextFieldState(LOREM_IPSUM),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            readOnly = true,
        )

        TextArea(
            state = rememberTextFieldState(LOREM_IPSUM),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            enabled = false,
            readOnly = true,
        )

        TextArea(
            state = rememberTextFieldState("Error state"),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            outline = Outline.Error,
            placeholder = { Text("Text area with error") },
            readOnly = true,
        )

        TextArea(
            state = rememberTextFieldState("Warning state"),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            outline = Outline.Warning,
            placeholder = { Text("Text area with warning") },
            readOnly = true,
        )
    }
}

@Composable
private fun AutoSizingTextArea() {
    Row(
        modifier = Modifier.height(300.dp).fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val textFieldState1 = rememberTextFieldState()
        TextArea(
            state = textFieldState1,
            modifier = Modifier.weight(1f).heightIn(50.dp, 150.dp),
            placeholder = { Text("Decorated, with a height between 50 and 150 dp. Type in here!") },
        )

        val textFieldState3 = rememberTextFieldState()
        val scrollState2 = rememberScrollState()
        HeightAutoSizingTextArea(
            state = textFieldState3,
            scrollState = scrollState2,
            modifier = Modifier.weight(1f).heightIn(50.dp, 150.dp),
            undecorated = true,
        )
    }
}
