// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.eel

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.eel.*
import com.intellij.platform.eel.EelExecApi.ExecuteProcessError
import com.intellij.platform.eel.fs.EelFileSystemApi
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.path.pathOs
import com.intellij.platform.eel.provider.utils.EelPathUtils
import com.intellij.util.awaitCancellationAndInvoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus.Internal
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString

@Internal
// TODO: add EelWindowsApi analog
class EelApiWithPathsMapping(private val ephemeralRoot: Path, private val original: EelPosixApiBase) : EelPosixApiBase by original, EelApi {
  private fun normalizeIfPath(maybePath: String): String {
    return if (maybePath.startsWith(ephemeralRoot.pathString)) {
      val originalPath = mapper.getOriginalPath(Path.of(maybePath))?.toString()

      originalPath ?: maybePath.removePrefix(ephemeralRoot.pathString).let(FileUtil::toSystemIndependentName)
    }
    else {
      maybePath
    }
  }

  override val exec: EelExecApi
    get() = EelExecApiWithNormalization(original.exec) { EelProcessBuilderWithPathsNormalization(it, ::normalizeIfPath) }

  override val mapper: EelPathMapper get() = EelEphemeralRootAwareMapper(ephemeralRoot, original)
}

private class EelEphemeralRootAwareMapper(
  private val ephemeralRoot: Path,
  private val eelApi: EelApiBase,
) : EelPathMapper {
  override fun getOriginalPath(path: Path): EelPath? {
    if (path.startsWith(ephemeralRoot)) {
      return EelPath.build(ephemeralRoot.relativize(path).map(Path::toString), eelApi.platform.pathOs)
    }
    return null
  }

  override suspend fun maybeUploadPath(path: Path, scope: CoroutineScope, options: EelFileSystemApi.CreateTemporaryEntryOptions): EelPath {
    val originalPath = getOriginalPath(path)

    if (originalPath != null) {
      return originalPath
    }

    val tmpDir = eelApi.fs.createTemporaryDirectory(options).getOrThrow()
    val referencedPath = tmpDir.resolve(path.name)

    withContext(Dispatchers.IO) {
      EelPathUtils.walkingTransfer(path, toNioPath(referencedPath), removeSource = false, copyAttributes = true)
    }

    scope.awaitCancellationAndInvoke {
      withContext(Job()) {
        when (val result = eelApi.fs.delete(tmpDir, true)) {
          is EelResult.Ok -> Unit
          is EelResult.Error -> thisLogger().warn("Failed to delete temporary directory $tmpDir: ${result.error}")
        }
      }
    }

    return referencedPath
  }

  override fun toNioPath(path: EelPath): Path {
    return ephemeralRoot.resolve(path.toString())
  }

  override fun pathPrefix(): String {
    return ephemeralRoot.pathString.trimEnd('/', '\\')
  }
}

private class EelProcessBuilderWithPathsNormalization(
  private val original: EelExecApi.ExecuteProcessOptions,
  private val normalizeIfPath: (String) -> String,
) : EelExecApi.ExecuteProcessOptions by original {
  override val workingDirectory: String? = original.workingDirectory?.let { normalizeIfPath(it) }
  override val args: List<String> = original.args.map { normalizeIfPath(it) }
  override val env: Map<String, String> = original.env.mapValues { (_, value) -> normalizeIfPath(value) }
}

private class EelExecApiWithNormalization(
  private val original: EelExecApi,
  private val normalize: (EelExecApi.ExecuteProcessOptions) -> EelExecApi.ExecuteProcessOptions,
) : EelExecApi by original {
  override suspend fun execute(builder: EelExecApi.ExecuteProcessOptions): EelResult<EelProcess, ExecuteProcessError> {
    return original.execute(normalize(builder))
  }
}