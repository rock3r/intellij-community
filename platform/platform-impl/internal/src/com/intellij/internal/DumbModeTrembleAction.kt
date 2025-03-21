// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbModeTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.TimeoutUtil
import java.util.*
import kotlin.random.Random

internal class DumbModeTrembleAction : DumbAwareAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return

    if (isTrembleDumb(project)) {
      setTrembleDumb(project, false)
    }
    else {
      setTrembleDumb(project, true)
      dumbModeTremble(project)
    }
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    if (project == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }

    val isTrembleDumb = isTrembleDumb(project)
    e.presentation.text = if (isTrembleDumb)
      InternalActionsBundle.message("disable.tremble.dumb.mode")
    else
      InternalActionsBundle.message("enable.tremble.dumb.mode")
  }
}

private const val SETTING_REGISTRY_KEY = "action.dumb.tremble"
private const val DEFAULT_SETTINGS_STRING = "800_2000_200_400"
private val DEFAULT_SETTINGS = parseSettings(DEFAULT_SETTINGS_STRING)

private val LOG = logger<DumbModeTrembleAction>()
private val DUMB_TREMBLE = Key.create<Boolean>("DumbModeTrembleAction")

private fun dumbModeTremble(project: Project) {
  val settings = readSettings()

  ApplicationManager.getApplication().executeOnPooledThread {
    LOG.info("Dumb Mode Tremble enabled")
    while (isTrembleDumb(project)) {
      TimeoutUtil.sleep(Random.nextLong(settings.disableMinMillis, settings.disableMaxMillis))

      DumbService.getInstance(project).queueTask(object : DumbModeTask() {
        override fun performInDumbMode(indicator: ProgressIndicator) {
          TimeoutUtil.sleep(Random.nextLong(settings.enableMinMillis, settings.enableMaxMillis))
          indicator.checkCanceled()
        }
      })
    }
    LOG.info("Dumb Mode Tremble disabled")
  }
}

private data class Settings(
  val disableMinMillis: Long,
  val disableMaxMillis: Long,
  val enableMinMillis: Long,
  val enableMaxMillis: Long
)

private fun parseSettings(settingsString: String): Settings {
  val numberStrings = settingsString.split("_")
  if (numberStrings.size != 4) {
    LOG.error("Bad $SETTING_REGISTRY_KEY value: `$numberStrings`")
    return DEFAULT_SETTINGS
  }

  val numbers = try {
    numberStrings.map { it.toLong() }
  }
  catch (_: NumberFormatException) {
    LOG.error("Can't parse longs in $SETTING_REGISTRY_KEY value: `$numberStrings`")
    return DEFAULT_SETTINGS
  }

  val disableMinMillis = numbers[0]
  val disableMaxMillis = numbers[1]
  val enableMinMillis = numbers[2]
  val enableMaxMillis = numbers[3]

  if ((disableMinMillis in 0 until disableMaxMillis) && (enableMinMillis in 0 until enableMaxMillis)) {
    return Settings(
      disableMinMillis,
      disableMaxMillis,
      enableMinMillis,
      enableMaxMillis
    )
  }

  return DEFAULT_SETTINGS
}

private fun readSettings(): Settings {
  val settingsString = try {
    Registry.stringValue(SETTING_REGISTRY_KEY)
  }
  catch (_: MissingResourceException) {
    return DEFAULT_SETTINGS
  }

  return parseSettings(settingsString)
}

private fun setTrembleDumb(project: Project, value: Boolean) {
  project.putUserData(DUMB_TREMBLE, value)
}

private fun isTrembleDumb(project: Project): Boolean {
  @Suppress("FORBIDDEN_IDENTITY_EQUALS_WARNING", "SSBasedInspection")
  return project.getUserData(DUMB_TREMBLE) === java.lang.Boolean.TRUE
}