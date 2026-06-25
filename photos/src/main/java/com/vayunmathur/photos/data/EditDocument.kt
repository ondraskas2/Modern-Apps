package com.vayunmathur.photos.data

import androidx.compose.ui.geometry.Rect

/**
 * The full editable document: a stack of [layers] (index 0 = bottom, last = top)
 * plus document-level geometric transforms applied to the final composite. This
 * replaces the flat per-effect pipeline; every former effect becomes either an
 * [AdjustmentLayer] or a rendering primitive inside a [Layer].
 *
 * The model is immutable — all helpers return a new [EditDocument], which makes it
 * trivial to snapshot for undo/redo.
 */
data class EditDocument(
    val layers: List<Layer> = emptyList(),
    val activeLayerIndex: Int = 0,
    val rotation: Float = 0f,
    val cropRect: Rect = FULL_CROP,
    val perspectiveCorners: PerspectiveCorners = PerspectiveCorners(),
    val canvasWidth: Int = 0,
    val canvasHeight: Int = 0,
) {
    val activeLayer: Layer?
        get() = layers.getOrNull(activeLayerIndex)

    val hasTransform: Boolean
        get() = rotation != 0f || cropRect != FULL_CROP || !perspectiveCorners.isIdentity()

    private fun clampIndex(index: Int): Int =
        index.coerceIn(0, (layers.size - 1).coerceAtLeast(0))

    /** Adds [layer] above the active layer (or on top when [onTop]) and selects it. */
    fun addLayer(layer: Layer, onTop: Boolean = true): EditDocument {
        if (layers.isEmpty()) {
            return copy(layers = listOf(layer), activeLayerIndex = 0)
        }
        val insertAt = if (onTop) layers.size else (activeLayerIndex + 1).coerceIn(0, layers.size)
        val newLayers = layers.toMutableList().apply { add(insertAt, layer) }
        return copy(layers = newLayers, activeLayerIndex = insertAt)
    }

    fun removeLayer(index: Int): EditDocument {
        if (index !in layers.indices || layers.size <= 1) return this
        val newLayers = layers.toMutableList().apply { removeAt(index) }
        return copy(layers = newLayers, activeLayerIndex = clampIndexFor(newLayers, activeLayerIndex))
    }

    fun moveLayer(from: Int, to: Int): EditDocument {
        if (from !in layers.indices) return this
        val target = to.coerceIn(0, layers.size - 1)
        if (from == target) return this
        val newLayers = layers.toMutableList()
        val item = newLayers.removeAt(from)
        newLayers.add(target, item)
        val newActive = when (activeLayerIndex) {
            from -> target
            else -> activeLayerIndex
        }
        return copy(layers = newLayers, activeLayerIndex = clampIndexFor(newLayers, newActive))
    }

    fun updateLayer(index: Int, transform: (Layer) -> Layer): EditDocument {
        if (index !in layers.indices) return this
        val newLayers = layers.toMutableList()
        newLayers[index] = transform(newLayers[index])
        return copy(layers = newLayers)
    }

    fun updateActiveLayer(transform: (Layer) -> Layer): EditDocument =
        updateLayer(activeLayerIndex, transform)

    fun setActiveLayer(index: Int): EditDocument =
        copy(activeLayerIndex = clampIndex(index))

    /** Duplicates the layer at [index] directly above it and selects the copy. */
    fun duplicateLayer(index: Int): EditDocument {
        if (index !in layers.indices) return this
        val source = layers[index]
        val copy = when (source) {
            is PixelLayer -> source.copy(id = newId(), name = "${source.name} copy")
            is AdjustmentLayer -> source.copy(id = newId(), name = "${source.name} copy")
            is TextLayer -> source.copy(id = newId(), name = "${source.name} copy")
            is DrawingLayer -> source.copy(id = newId(), name = "${source.name} copy")
        }
        val newLayers = layers.toMutableList().apply { add(index + 1, copy) }
        return this.copy(layers = newLayers, activeLayerIndex = index + 1)
    }

    companion object {
        val FULL_CROP: Rect = Rect(0f, 0f, 1f, 1f)

        private fun newId(): String = java.util.UUID.randomUUID().toString()

        private fun clampIndexFor(layers: List<Layer>, desired: Int): Int =
            desired.coerceIn(0, (layers.size - 1).coerceAtLeast(0))

        /** Builds an initial document with a single background [PixelLayer]. */
        fun fromBackground(bitmapRef: BitmapReference, width: Int, height: Int): EditDocument =
            EditDocument(
                layers = listOf(PixelLayer(bitmapRef = bitmapRef, name = "Background", locked = true)),
                activeLayerIndex = 0,
                canvasWidth = width,
                canvasHeight = height,
            )
    }
}
