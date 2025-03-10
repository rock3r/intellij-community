// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.testFramework

abstract class GradleExecutionTestCase : GradleExecutionBaseTestCase() {

  fun isPerTaskOutputSupported(): Boolean = isGradleAtLeast("4.7")

  fun isBuildCompilationReportSupported(): Boolean = isGradleAtLeast("8.11")
}