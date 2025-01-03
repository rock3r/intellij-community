// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.testFramework.junit5.eel.impl

import com.intellij.platform.eel.EelArchiveApi
import com.intellij.platform.eel.EelExecApi
import com.intellij.platform.eel.EelPathMapper
import com.intellij.platform.eel.EelPlatform
import com.intellij.platform.eel.EelPosixApi
import com.intellij.platform.eel.EelResult
import com.intellij.platform.eel.EelTunnelsPosixApi
import com.intellij.platform.eel.EelUserPosixInfo
import com.intellij.platform.eel.fs.EelFileSystemApi
import com.intellij.platform.eel.impl.fs.PosixNioBasedEelFileSystemApi
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.testFramework.junit5.eel.impl.ownUri.EelTestFileSystem
import com.intellij.util.system.CpuArch
import java.nio.file.Files

internal class EelTestPosixApi(fileSystem: EelTestFileSystem, localPrefix: String) : EelPosixApi {
  override val userInfo: EelUserPosixInfo = EelTestPosixUserInfo()

  override val platform: EelPlatform.Posix
    get() = if (CpuArch.CURRENT == CpuArch.ARM64) {
      EelPlatform.Aarch64Linux
    }
    else {
      EelPlatform.X8664Linux
    }

  override val mapper: EelPathMapper = EelTestPathMapper(EelPath.OS.UNIX, fileSystem, localPrefix)

  override val fs: PosixNioBasedEelFileSystemApi = EelTestFileSystemPosixApi(mapper, fileSystem)

  override val archive: EelArchiveApi
    get() = TODO()
  override val tunnels: EelTunnelsPosixApi
    get() = TODO()
  override val exec: EelExecApi
    get() = TODO()

}

private class EelTestFileSystemPosixApi(val mapper: EelPathMapper, fileSystem: EelTestFileSystem) : PosixNioBasedEelFileSystemApi(fileSystem, EelTestPosixUserInfo()) {

  override suspend fun readFully(path: EelPath, limit: ULong, overflowPolicy: EelFileSystemApi.OverflowPolicy): EelResult<EelFileSystemApi.FullReadResult, EelFileSystemApi.FullReadError> {
    TODO("Not yet implemented")
  }

  override suspend fun createTemporaryDirectory(options: EelFileSystemApi.CreateTemporaryEntryOptions): EelResult<EelPath, EelFileSystemApi.CreateTemporaryEntryError> {
    return wrapIntoEelResult {
      val nioTempDir = Files.createTempDirectory(fs.rootDirectories.single(), options.prefix)
      mapper.getOriginalPath(nioTempDir)!!
    }
  }

  override suspend fun createTemporaryFile(options: EelFileSystemApi.CreateTemporaryEntryOptions): EelResult<EelPath, EelFileSystemApi.CreateTemporaryEntryError> {
    TODO("Not yet implemented")
  }
}

private class EelTestPosixUserInfo : EelUserPosixInfo {
  override val uid: Int
    get() = 1001
  override val gid: Int
    get() = 1
  override val home: EelPath
    get() = EelPath.parse("/home", EelPath.OS.UNIX)
}