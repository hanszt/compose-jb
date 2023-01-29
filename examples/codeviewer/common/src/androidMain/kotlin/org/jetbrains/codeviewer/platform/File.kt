package org.jetbrains.codeviewer.platform

lateinit var _HomeFolder: java.io.File
actual val HomeFolder: ViewerFile get() = _HomeFolder.toProjectFile()
