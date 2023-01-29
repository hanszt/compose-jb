@file:Suppress("NewApi")

package org.jetbrains.codeviewer.platform

import java.io.File

actual val HomeFolder: ViewerFile
    get() = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .onEach(::println)
        .first { "kotlin" in it.name.lowercase() }
        .toProjectFile()
