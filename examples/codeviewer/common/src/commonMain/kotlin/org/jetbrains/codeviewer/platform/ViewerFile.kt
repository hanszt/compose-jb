package org.jetbrains.codeviewer.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.codeviewer.util.TextLines

expect val HomeFolder: ViewerFile

interface ViewerFile {
    val name: String
    val isDirectory: Boolean
    val children: List<ViewerFile>
    val hasChildren: Boolean

    fun readLines(scope: CoroutineScope, coroutineDispatcher: CoroutineDispatcher): TextLines
}
