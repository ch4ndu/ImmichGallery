package com.udnahc.immichgallery.ui.util

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

class TimelineMosaicDispatcherProvider {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val context: CloseableCoroutineDispatcher = newSingleThreadContext("TimelineMosaic")

    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher: CoroutineDispatcher
        get() = context

    @OptIn(ExperimentalCoroutinesApi::class)
    fun close() {
        context.close()
    }
}
