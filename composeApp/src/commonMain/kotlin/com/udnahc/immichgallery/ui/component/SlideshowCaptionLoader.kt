package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SlideshowCaptionContent(val assetId: String, val dateTime: String?, val location: String?, val description: String?)

internal class SlideshowCaptionLoader(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val getDetail: suspend (String) -> Result<AssetDetail>
) {
    private val mutableState = MutableStateFlow<SlideshowCaptionContent?>(null)
    val state = mutableState.asStateFlow()
    private val cache = mutableMapOf<String, SlideshowCaptionContent>()
    private var selected: SlideshowMediaRequest? = null
    private var job: Job? = null
    private var generation = 0L
    private var closed = false

    fun select(request: SlideshowMediaRequest?, enabled: Boolean) {
        if (closed) return
        val next = request.takeIf { enabled && it?.assetId != null }
        if (next == selected) return
        selected = next
        generation += 1
        val captured = generation
        job?.cancel()
        mutableState.value = null
        val id = next?.assetId ?: return
        cache[id]?.let { mutableState.value = it; return }
        job = scope.launch {
            val detail = withContext(dispatcher) { getDetail(id) }.getOrNull() ?: return@launch
            ensureActive()
            if (captured != generation || closed) return@launch
            val caption = SlideshowCaptionContent(id, detail.dateTime,
                listOfNotNull(detail.city, detail.state, detail.country).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
                detail.description?.trim()?.takeIf { it.isNotEmpty() })
            cache[id] = caption
            mutableState.value = caption
        }
    }

    fun close() { closed = true; generation += 1; job?.cancel(); cache.clear(); mutableState.value = null }
}
