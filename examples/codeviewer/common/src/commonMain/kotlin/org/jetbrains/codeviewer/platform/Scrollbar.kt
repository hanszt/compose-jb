package org.jetbrains.codeviewer.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun verticalScrollbar(
    modifier: Modifier,
    scrollState: ScrollState
)

@Composable
expect fun verticalScrollbar(
    modifier: Modifier,
    scrollState: LazyListState
)
