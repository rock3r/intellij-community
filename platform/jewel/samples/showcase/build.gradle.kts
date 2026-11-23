// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
plugins {
    jewel
    alias(libs.plugins.composeDesktop)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    compileOnly(project(":foundation"))
    compileOnly(project(":ui"))
    compileOnly(projects.markdown.core)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs) { exclude(group = "org.jetbrains.compose.material") }
    testImplementation(project(":foundation"))
    testImplementation(project(":ui"))
    testImplementation(project(":int-ui:int-ui-standalone"))
    // Platform icon resources, so tests render the same AllIcons the app does — including their stroke
    // variants, which is what makes a selected toggle's icon tint correctly.
    testImplementation(libs.intellijPlatform.icons)
}
