package com.vayunmathur.photos.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke as InkStroke
import com.vayunmathur.library.ui.CanvasTextElement
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconBrush
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconEraser
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.InkCanvasView
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.translate
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BlackAndWhiteAdj
import com.vayunmathur.photos.data.BlurAdj
import com.vayunmathur.photos.data.BlurParams
import com.vayunmathur.photos.data.ChannelMixerAdj
import com.vayunmathur.photos.data.ColorBalanceAdj
import com.vayunmathur.photos.data.CurveChannel
import com.vayunmathur.photos.data.CurvesAdj
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.DodgeBurnMode
import com.vayunmathur.photos.data.DodgeBurnStroke
import com.vayunmathur.photos.data.DodgeBurnStrokes
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GradientMapAdj
import com.vayunmathur.photos.data.HealMode
import com.vayunmathur.photos.data.HealingStroke
import com.vayunmathur.photos.data.HealingStrokes
import com.vayunmathur.photos.data.HslAdj
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.HslColorRange
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.LayerAdjustment
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.data.PhotoFilter
import com.vayunmathur.photos.data.PhotoFilters
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.RedEyeSpot
import com.vayunmathur.photos.data.RedEyeSpots
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SelectiveAdj
import com.vayunmathur.photos.data.SelectiveEdits
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.SmudgeStroke
import com.vayunmathur.photos.data.SmudgeStrokes
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.applyToBitmap
import com.vayunmathur.photos.data.applyHealingToBitmap
import com.vayunmathur.photos.data.toColorMatrix
import com.vayunmathur.photos.util.PhotoEditViewModel
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.serialize
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class ToolCategory(val label: String) {
    Adjust("Adjust"), Filters("Filters"), Retouch("Retouch"),
    Select("Select"), Transform("Crop"), Draw("Draw"), Layers("Layers"),
}

private enum class EditorMode {
    None,
    Adjust, Filters, Curves, HSL, Levels, ColorBalance, ChannelMixer, BlackWhite, GradientMap,
    LensBlur, Selective, FilterFx, Liquify,
    Healing, RedEye, DodgeBurn, Smudge,
    Selection,
    Crop,
    Layers,
}

private data class ToolEntry(val mode: EditorMode, val label: String)

/** A committed marquee selection described as a shape (for drawing the pattern overlay). */
private data class SelShape(
    val isEllipse: Boolean,
    val rect: Rect,
    val inverted: Boolean,
    val featherFracX: Float,
    val featherFracY: Float,
)

private val categoryTools: Map<ToolCategory, List<ToolEntry>> = mapOf(
    ToolCategory.Adjust to listOf(
        ToolEntry(EditorMode.Adjust, "Light"),
        ToolEntry(EditorMode.Filters, "Presets"),
        ToolEntry(EditorMode.Curves, "Curves"),
        ToolEntry(EditorMode.HSL, "HSL"),
        ToolEntry(EditorMode.Levels, "Levels"),
        ToolEntry(EditorMode.ColorBalance, "Balance"),
        ToolEntry(EditorMode.ChannelMixer, "Mixer"),
        ToolEntry(EditorMode.BlackWhite, "B&W"),
        ToolEntry(EditorMode.GradientMap, "Gradient"),
    ),
    ToolCategory.Filters to listOf(
        ToolEntry(EditorMode.LensBlur, "Lens Blur"),
        ToolEntry(EditorMode.Selective, "Selective"),
        ToolEntry(EditorMode.FilterFx, "Filters"),
        ToolEntry(EditorMode.Liquify, "Liquify"),
    ),
    ToolCategory.Retouch to listOf(
        ToolEntry(EditorMode.Healing, "Heal"),
        ToolEntry(EditorMode.RedEye, "Red-Eye"),
        ToolEntry(EditorMode.DodgeBurn, "Dodge/Burn"),
        ToolEntry(EditorMode.Smudge, "Smudge"),
    ),
    ToolCategory.Select to listOf(
        ToolEntry(EditorMode.Selection, "Marquee"),
    ),
    ToolCategory.Transform to listOf(
        ToolEntry(EditorMode.Crop, "Crop & Rotate"),
    ),
    ToolCategory.Layers to listOf(
        ToolEntry(EditorMode.Layers, "Layers"),
    ),
)

private fun ToolCategory.description(): String = when (this) {
    ToolCategory.Adjust -> "Tune light and color."
    ToolCategory.Filters -> "Effects, blur, and presets."
    ToolCategory.Retouch -> "Fix and clean up areas."
    ToolCategory.Select -> "Pick an area to limit edits."
    ToolCategory.Transform -> "Crop, straighten, and rotate."
    ToolCategory.Draw -> "Draw, highlight, and add text."
    ToolCategory.Layers -> "Manage layers and masks."
}

private fun EditorMode.description(): String = when (this) {
    EditorMode.Adjust -> "Fine-tune light and color with sliders."
    EditorMode.Filters -> "Apply a one-tap preset look."
    EditorMode.Curves -> "Reshape brightness and contrast with a curve."
    EditorMode.HSL -> "Adjust hue, saturation, and lightness per color."
    EditorMode.Levels -> "Set the black point, white point, and midtones."
    EditorMode.ColorBalance -> "Shift colors in shadows, midtones, and highlights."
    EditorMode.ChannelMixer -> "Blend the red, green, and blue channels."
    EditorMode.BlackWhite -> "Convert to black & white and control how colors map."
    EditorMode.GradientMap -> "Map dark-to-light tones onto a color gradient."
    EditorMode.LensBlur -> "Blur the background for a depth-of-field look."
    EditorMode.Selective -> "Paint an adjustment onto specific spots."
    EditorMode.FilterFx -> "Apply a baked-in photo filter to the pixels."
    EditorMode.Liquify -> "Push and warp pixels around."
    EditorMode.Healing -> "Remove blemishes by copying nearby pixels."
    EditorMode.RedEye -> "Tap each eye to remove red-eye."
    EditorMode.DodgeBurn -> "Brush to lighten (dodge) or darken (burn)."
    EditorMode.Smudge -> "Drag to smear pixels like wet paint."
    EditorMode.Selection -> "Draw an area; edits then apply only inside it."
    EditorMode.Crop -> "Crop, straighten, and rotate the photo."
    EditorMode.Layers -> "Stack, blend, and mask layers."
    EditorMode.None -> ""
}

private enum class AdjustmentType(
    val label: String,
    val min: Float,
    val max: Float,
    val get: (ImageAdjustments) -> Float,
    val set: (ImageAdjustments, Float) -> ImageAdjustments,
) {
    Brightness("Brightness", -100f, 100f, { it.brightness }, { a, v -> a.copy(brightness = v) }),
    Contrast("Contrast", -100f, 100f, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    Saturation("Saturation", -100f, 100f, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    Warmth("Warmth", -100f, 100f, { it.warmth }, { a, v -> a.copy(warmth = v) }),
    Exposure("Exposure", -100f, 100f, { it.exposure }, { a, v -> a.copy(exposure = v) }),
    Highlights("Highlights", -100f, 100f, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    Shadows("Shadows", -100f, 100f, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    Sharpness("Sharpness", 0f, 100f, { it.sharpness }, { a, v -> a.copy(sharpness = v) }),
    Vignette("Vignette", 0f, 100f, { it.vignette }, { a, v -> a.copy(vignette = v) }),
    Grain("Grain", 0f, 100f, { it.grain }, { a, v -> a.copy(grain = v) }),
    Fade("Fade", 0f, 100f, { it.fade }, { a, v -> a.copy(fade = v) }),
    Tint("Tint", -100f, 100f, { it.tint }, { a, v -> a.copy(tint = v) }),
}

private inline fun <reified T : LayerAdjustment> EditDocument.activeAdjustment(): T? =
    (activeLayer as? AdjustmentLayer)?.adjustment as? T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPhotoPage(
    backStack: NavBackStack<EditRoute>,
    photoEditViewModel: PhotoEditViewModel,
    id: Long,
    initialUri: String? = null,
) {
    val vm = photoEditViewModel
    val context = LocalActivity.current!!
    LaunchedEffect(id, initialUri) { vm.loadPhoto(id, initialUri) }
    val photo by vm.photo.collectAsState()

    val document by vm.document.collectAsState()
    val preview by vm.compositedPreview.collectAsState()
    val baseBitmap by vm.baseBitmap.collectAsState()
    val selection by vm.selection.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()

    var activeCategory by remember { mutableStateOf<ToolCategory?>(null) }
    var editorMode by remember { mutableStateOf(EditorMode.None) }
    var selectedAdjustment by remember { mutableStateOf(AdjustmentType.Brightness) }
    var selectedCurveChannel by remember { mutableStateOf(CurveChannel.Combined) }
    var selectedHslRange by remember { mutableStateOf(HslColorRange.Red) }

    var isCropping by remember { mutableStateOf(false) }
    var cropCx by remember { mutableFloatStateOf(0.5f) }
    var cropCy by remember { mutableFloatStateOf(0.5f) }
    var cropHx by remember { mutableFloatStateOf(0.5f) }
    var cropHy by remember { mutableFloatStateOf(0.5f) }
    var cropAngle by remember { mutableFloatStateOf(0f) }
    var cropAspect by remember { mutableStateOf<Float?>(null) }
    var showSaveMenu by remember { mutableStateOf(false) }

    // Selective
    var currentSelectiveMask by remember { mutableStateOf(SelectiveMask()) }
    var showSelectiveMask by remember { mutableStateOf(false) }

    // Healing
    var healingBrushSize by remember { mutableFloatStateOf(0.02f) }
    var isSettingHealingSource by remember { mutableStateOf(true) }
    var healingSourceX by remember { mutableStateOf<Float?>(null) }
    var healingSourceY by remember { mutableStateOf<Float?>(null) }
    var currentHealingPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    // Retouch brush (dodge/burn/smudge)
    var retouchPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var dodgeBurnMode by remember { mutableStateOf(DodgeBurnMode.Dodge) }
    var brushSize by remember { mutableFloatStateOf(0.05f) }

    // Liquify
    var liquifyTool by remember { mutableStateOf(com.vayunmathur.photos.data.LiquifyTool.Push) }
    var liquifyStrength by remember { mutableFloatStateOf(0.5f) }
    var liquifyRadius by remember { mutableFloatStateOf(0.15f) }

    // Selection tool
    var selectionIsEllipse by remember { mutableStateOf(false) }
    var selectionFeather by remember { mutableFloatStateOf(0f) }
    var committedSel by remember { mutableStateOf<SelShape?>(null) }
    var selDragStart by remember { mutableStateOf<Offset?>(null) }
    var selDragCurrent by remember { mutableStateOf<Offset?>(null) }

    // Drawing
    val inkStrokes = remember { mutableStateListOf<InkStroke>() }
    var activeTool by remember { mutableStateOf(DrawingTool.Pointer) }
    var penColor by remember { mutableStateOf(Color.Red) }
    var penSize by remember { mutableFloatStateOf(10f) }
    var highlighterColor by remember { mutableStateOf(Color.Yellow) }
    var highlighterSize by remember { mutableFloatStateOf(40f) }
    var highlighterOpacity by remember { mutableFloatStateOf(0.5f) }
    var textFontSize by remember { mutableFloatStateOf(40f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        if (activeTool == DrawingTool.Pointer) { scale *= zoomChange; offset += offsetChange }
    }

    val texts = remember { mutableStateListOf<TextElement>() }
    var selectedTextId by remember { mutableStateOf<String?>(null) }
    var selectedStrokeIndex by remember { mutableStateOf<Int?>(null) }
    var selectedTextIndex by remember { mutableStateOf<Int?>(null) }
    var textToEdit by remember { mutableStateOf<TextElement?>(null) }
    var currentViewportWidth by remember { mutableFloatStateOf(1f) }
    var currentViewportHeight by remember { mutableFloatStateOf(1f) }

    val isDrawing = activeCategory == ToolCategory.Draw

    fun exitCropPreview() {
        vm.setCroppingPreview(false)
        isCropping = false
    }

    fun selectTool(mode: EditorMode) {
        when (mode) {
            EditorMode.Crop -> {
                cropCx = 0.5f; cropCy = 0.5f; cropHx = 0.5f; cropHy = 0.5f
                cropAngle = 0f; cropAspect = null
                isCropping = true
                vm.setCroppingPreview(true)
                editorMode = EditorMode.Crop
            }
            else -> {
                if (isCropping) exitCropPreview()
                editorMode = if (editorMode == mode) EditorMode.None else mode
            }
        }
    }

    fun goHome() {
        if (isCropping) exitCropPreview()
        activeCategory = null
        editorMode = EditorMode.None
        activeTool = DrawingTool.Pointer
        selectedTextId = null
        selectedStrokeIndex = null
        selectedTextIndex = null
    }

    fun openCategory(cat: ToolCategory) {
        activeCategory = cat
        if (cat == ToolCategory.Draw) {
            activeTool = DrawingTool.Pointer
        } else {
            categoryTools[cat]?.firstOrNull()?.let { selectTool(it.mode) }
        }
    }

    fun commitSelection(rect: Rect, isEllipse: Boolean, inverted: Boolean, featherPx: Float) {
        val cw = document.canvasWidth.coerceAtLeast(1)
        val ch = document.canvasHeight.coerceAtLeast(1)
        // Build the mask at a capped resolution so it stays cheap on the main thread.
        val maxDim = 768f
        val scale = minOf(1f, maxDim / maxOf(cw, ch))
        val mw = (cw * scale).roundToInt().coerceAtLeast(1)
        val mh = (ch * scale).roundToInt().coerceAtLeast(1)
        var sel = if (isEllipse) {
            Selection.ellipse(mw, mh, (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f, (rect.right - rect.left) / 2f, (rect.bottom - rect.top) / 2f)
        } else {
            Selection.rectangle(mw, mh, rect.left, rect.top, rect.right, rect.bottom)
        }
        val featherScaled = featherPx * scale
        if (featherScaled > 0f) sel = sel.applyFeather(featherScaled)
        if (inverted) sel = sel.invert()
        vm.setSelection(sel)
        committedSel = SelShape(isEllipse, rect, inverted, featherPx / cw, featherPx / ch)
    }

    fun applyCrop() {
        val w = document.canvasWidth.coerceAtLeast(1).toFloat()
        val h = document.canvasHeight.coerceAtLeast(1).toFloat()
        val aRad = Math.toRadians(cropAngle.toDouble())
        val cosA = cos(aRad)
        val sinA = sin(aRad)
        val cpx = cropCx * w
        val cpy = cropCy * h
        val hwpx = cropHx * w
        val hhpx = cropHy * h
        val wp = w * abs(cosA) + h * abs(sinA)
        val hp = w * abs(sinA) + h * abs(cosA)
        val dx = cpx - w / 2.0
        val dy = cpy - h / 2.0
        // Rotate the crop center by -angle (the image rotation we will apply), into result space.
        val rx = cosA * dx + sinA * dy
        val ry = -sinA * dx + cosA * dy
        val crx = rx + wp / 2.0
        val cry = ry + hp / 2.0
        val l = ((crx - hwpx) / wp).toFloat().coerceIn(0f, 1f)
        val t = ((cry - hhpx) / hp).toFloat().coerceIn(0f, 1f)
        val r = ((crx + hwpx) / wp).toFloat().coerceIn(0f, 1f)
        val b = ((cry + hhpx) / hp).toFloat().coerceIn(0f, 1f)
        vm.setRotation(-cropAngle)
        vm.setCropRect(Rect(l, t, r, b))
        goHome()
    }

    val currentBrush: Brush = remember(activeTool, penColor, penSize, highlighterColor, highlighterSize, highlighterOpacity) {
        when (activeTool) {
            DrawingTool.Highlighter -> {
                val argb = highlighterColor.toArgb()
                val alpha = (highlighterOpacity * 255).roundToInt()
                val colorWithAlpha = (alpha shl 24) or (argb and 0x00FFFFFF)
                Brush.createWithColorIntArgb(StockBrushes.highlighter(), colorWithAlpha, highlighterSize, 0.1f)
            }
            else -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), penColor.toArgb(), penSize, 0.1f)
        }
    }

    ResultEffect<DrawingSettingsResult>("drawing_settings") { result ->
        var changed = false
        selectedTextId?.let { tid ->
            val index = texts.indexOfFirst { it.id == tid }
            if (index != -1) {
                texts[index] = texts[index].copy(color = result.color, fontSize = result.thickness)
                changed = true
            }
        }
        if (!changed) {
            activeTool = result.tool
            when (result.tool) {
                DrawingTool.Pen -> { penColor = Color(result.color); penSize = result.thickness }
                DrawingTool.Highlighter -> {
                    highlighterColor = Color(result.color); highlighterSize = result.thickness; highlighterOpacity = result.opacity
                }
                DrawingTool.Text -> { penColor = Color(result.color); textFontSize = result.thickness }
                else -> {}
            }
        }
    }

    LaunchedEffect(photo?.uri) {
        val uri = photo?.uri?.toUri() ?: return@LaunchedEffect
        vm.decode(uri)
    }

    // Ensure the right layer is active for the selected tool.
    LaunchedEffect(editorMode) {
        when (editorMode) {
            EditorMode.Adjust, EditorMode.Filters ->
                vm.ensureAdjustment({ it is BasicAdjustment }, { BasicAdjustment() })
            EditorMode.Curves -> vm.ensureAdjustment({ it is CurvesAdj }, { CurvesAdj() })
            EditorMode.HSL -> vm.ensureAdjustment({ it is HslAdj }, { HslAdj() })
            EditorMode.Levels -> vm.ensureAdjustment({ it is LevelsAdj }, { LevelsAdj() })
            EditorMode.ColorBalance -> vm.ensureAdjustment({ it is ColorBalanceAdj }, { ColorBalanceAdj() })
            EditorMode.ChannelMixer -> vm.ensureAdjustment({ it is ChannelMixerAdj }, { ChannelMixerAdj() })
            EditorMode.BlackWhite -> vm.ensureAdjustment({ it is BlackAndWhiteAdj }, { BlackAndWhiteAdj() })
            EditorMode.LensBlur -> vm.ensureAdjustment({ it is BlurAdj }, { BlurAdj() })
            EditorMode.Selective -> vm.ensureAdjustment({ it is SelectiveAdj }, { SelectiveAdj() })
            EditorMode.Healing, EditorMode.RedEye, EditorMode.DodgeBurn, EditorMode.Smudge, EditorMode.FilterFx, EditorMode.Liquify -> {
                val idx = document.layers.indexOfLast { it is PixelLayer }
                if (idx >= 0 && idx != document.activeLayerIndex) vm.setActiveLayer(idx)
            }
            else -> {}
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onWritePermissionGranted()
        else vm.onWritePermissionDenied()
    }
    val writePermissionRequest by vm.writePermissionRequest.collectAsState()
    LaunchedEffect(writePermissionRequest) {
        writePermissionRequest?.let { writePermissionLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    fun doSave(asCopy: Boolean) {
        photo?.let {
            vm.savePhoto(
                it, asCopy, inkStrokes.map { s -> s.serialize() }, texts.toList(),
                currentViewportWidth, currentViewportHeight,
            ) { context.finish() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_edit_photo), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { context.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.undo() }, enabled = canUndo) { IconUndo() }
                    IconButton(onClick = { vm.redo() }, enabled = canRedo) {
                        Text("↻", fontSize = 20.sp)
                    }
                    Box {
                        IconButton(onClick = { showSaveMenu = true }) { IconSave() }
                        DropdownMenu(expanded = showSaveMenu, onDismissRequest = { showSaveMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_save)) },
                                onClick = { showSaveMenu = false; doSave(false) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_save_as_copy)) },
                                onClick = { showSaveMenu = false; doSave(true) },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).background(Color.Black),
        ) {
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val display = preview ?: baseBitmap
                if (display != null) {
                    val ratio = display.width.toFloat() / display.height.toFloat()
                    val containerRatio = maxW / maxH
                    val (vpW, vpH) = if (ratio > containerRatio) maxW to (maxW / ratio) else (maxH * ratio) to maxH

                    val density = LocalDensity.current
                    val densityFloat = density.density
                    val vpWdp = with(density) { vpW.toDp() }
                    val vpHdp = with(density) { vpH.toDp() }

                    val isInkDrawing = isDrawing && activeTool != DrawingTool.Pointer && activeTool != DrawingTool.Text
                    Box(
                        modifier = Modifier
                            .size(vpWdp, vpHdp)
                            .onGloballyPositioned {
                                currentViewportWidth = it.size.width.toFloat()
                                currentViewportHeight = it.size.height.toFloat()
                            }
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offset.x; translationY = offset.y; clip = false
                            }
                            .then(
                                if (activeTool == DrawingTool.Text && isDrawing) Modifier.pointerInput(activeTool) {
                                    detectTapGestures { tapOffset ->
                                        val newId = UUID.randomUUID().toString()
                                        texts.add(
                                            TextElement(
                                                id = newId, text = "New Text",
                                                x = tapOffset.x / size.width, y = tapOffset.y / size.height,
                                                rotation = 0f, color = penColor.toArgb(), fontSize = textFontSize,
                                            )
                                        )
                                        selectedTextId = newId
                                        selectedTextIndex = texts.size - 1
                                        textToEdit = texts.last()
                                    }
                                }
                                else if (!isInkDrawing && activeTool != DrawingTool.Pointer && isDrawing)
                                    Modifier.transformable(state = transformState)
                                else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        display.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        val canvasTextElements by remember {
                            derivedStateOf {
                                texts.map { te ->
                                    CanvasTextElement(
                                        text = te.text, x = te.x * currentViewportWidth, y = te.y * currentViewportHeight,
                                        rotation = te.rotation, color = te.color, fontSize = te.fontSize,
                                    )
                                }
                            }
                        }

                        InkCanvasView(
                            currentBrush = currentBrush,
                            finishedStrokes = inkStrokes.toList(),
                            onStrokeFinished = { inkStrokes.add(it) },
                            onStrokeErased = { inkStrokes.remove(it) },
                            eraserMode = activeTool == DrawingTool.Eraser,
                            enabled = isDrawing && activeTool != DrawingTool.Pointer && activeTool != DrawingTool.Text,
                            textElements = canvasTextElements,
                            selectedStrokeIndex = selectedStrokeIndex,
                            selectedTextIndex = selectedTextIndex,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (isDrawing && activeTool == DrawingTool.Pointer) {
                            Box(
                                modifier = Modifier.fillMaxSize().pointerInput(
                                    selectedStrokeIndex, selectedTextIndex,
                                    currentViewportWidth, currentViewportHeight,
                                ) {
                                    detectTapGestures(
                                        onDoubleTap = { tapOffset ->
                                            val ti = hitTestText(tapOffset.x, tapOffset.y, texts, currentViewportWidth, currentViewportHeight, densityFloat)
                                            if (ti != null) textToEdit = texts.getOrNull(ti)
                                        },
                                        onTap = { tapOffset ->
                                            val ti = hitTestText(tapOffset.x, tapOffset.y, texts, currentViewportWidth, currentViewportHeight, densityFloat)
                                            if (ti != null) {
                                                selectedTextIndex = ti; selectedStrokeIndex = null; selectedTextId = texts.getOrNull(ti)?.id
                                            } else {
                                                val si = hitTestStroke(tapOffset.x, tapOffset.y, inkStrokes)
                                                selectedStrokeIndex = si; selectedTextIndex = null; selectedTextId = null
                                            }
                                        },
                                    )
                                }.pointerInput(selectedStrokeIndex, selectedTextIndex, currentViewportWidth, currentViewportHeight) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            selectedStrokeIndex?.let { idx ->
                                                if (idx in inkStrokes.indices) inkStrokes[idx] = inkStrokes[idx].translate(dragAmount.x, dragAmount.y)
                                            }
                                            selectedTextIndex?.let { idx ->
                                                if (idx in texts.indices) {
                                                    val c = texts[idx]
                                                    texts[idx] = c.copy(x = c.x + dragAmount.x / currentViewportWidth, y = c.y + dragAmount.y / currentViewportHeight)
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                        }

                        // Tool overlays (only when not drawing)
                        if (!isDrawing && !isCropping) {
                            // Keep the committed selection visible in every mode
                            // (until cleared) so the user knows edits are scoped.
                            if (committedSel != null && selDragStart == null) {
                                SelectionPatternOverlay(committedSel!!)
                            }
                            val blurAdj = document.activeAdjustment<BlurAdj>()
                            if (editorMode == EditorMode.LensBlur && blurAdj != null && !blurAdj.blur.isIdentity()) {
                                BlurOverlay(blurParams = blurAdj.blur, onBlurChanged = { vm.updateActiveAdjustment(BlurAdj(it)) })
                            }
                            if (editorMode == EditorMode.Selective) {
                                MaskOverlay(mask = currentSelectiveMask, showMask = showSelectiveMask, onMaskChanged = { currentSelectiveMask = it })
                            }
                            if (editorMode == EditorMode.Healing) {
                                HealingOverlay(
                                    sourceX = healingSourceX, sourceY = healingSourceY, brushSize = healingBrushSize,
                                    isSettingSource = isSettingHealingSource,
                                    onSourceSet = { x, y -> healingSourceX = x; healingSourceY = y; isSettingHealingSource = false },
                                    onPaint = { x, y -> if (healingSourceX != null && healingSourceY != null) currentHealingPoints = currentHealingPoints + (x to y) },
                                )
                            }
                            if (editorMode == EditorMode.RedEye) {
                                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectTapGestures { o ->
                                        val nx = o.x / size.width; val ny = o.y / size.height
                                        vm.applyToActivePixelLayer { RedEyeSpots(listOf(RedEyeSpot(nx, ny, brushSize))).applyToBitmap(it) }
                                    }
                                })
                            }
                            if (editorMode == EditorMode.DodgeBurn || editorMode == EditorMode.Smudge) {
                                Box(modifier = Modifier.fillMaxSize().pointerInput(editorMode) {
                                    detectDragGestures(
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                            val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                            retouchPoints = retouchPoints + (nx to ny)
                                        },
                                    )
                                })
                            }
                            if (editorMode == EditorMode.Liquify) {
                                var liqStart by remember { mutableStateOf<Offset?>(null) }
                                Box(modifier = Modifier.fillMaxSize().pointerInput(liquifyTool) {
                                    detectDragGestures(
                                        onDragStart = { liqStart = it },
                                        onDragEnd = {
                                            val s = liqStart
                                            if (s != null) {
                                                val nx = (s.x / size.width).coerceIn(0f, 1f)
                                                val ny = (s.y / size.height).coerceIn(0f, 1f)
                                                val op = com.vayunmathur.photos.data.LiquifyOp(
                                                    tool = liquifyTool, x = nx, y = ny,
                                                    radius = liquifyRadius, strength = liquifyStrength,
                                                )
                                                vm.applyToActivePixelLayer {
                                                    com.vayunmathur.photos.data.LiquifyParams(listOf(op)).applyToBitmap(it)
                                                }
                                            }
                                            liqStart = null
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val s = liqStart ?: return@detectDragGestures
                                            if (liquifyTool == com.vayunmathur.photos.data.LiquifyTool.Push) {
                                                val nx = (s.x / size.width).coerceIn(0f, 1f)
                                                val ny = (s.y / size.height).coerceIn(0f, 1f)
                                                val ddx = (change.position.x - s.x) / size.width
                                                val ddy = (change.position.y - s.y) / size.height
                                                val op = com.vayunmathur.photos.data.LiquifyOp(
                                                    tool = com.vayunmathur.photos.data.LiquifyTool.Push,
                                                    x = nx, y = ny, dx = ddx, dy = ddy,
                                                    radius = liquifyRadius, strength = liquifyStrength,
                                                )
                                                vm.applyToActivePixelLayer {
                                                    com.vayunmathur.photos.data.LiquifyParams(listOf(op)).applyToBitmap(it)
                                                }
                                                liqStart = change.position
                                            }
                                        },
                                    )
                                })
                            }
                            if (editorMode == EditorMode.Selection) {
                                SelectionOverlay(
                                    isEllipse = selectionIsEllipse,
                                    dragStart = selDragStart,
                                    dragCurrent = selDragCurrent,
                                    onStart = { selDragStart = it; selDragCurrent = it },
                                    onDrag = { selDragCurrent = it },
                                    onEnd = {
                                        val s = selDragStart; val e = selDragCurrent
                                        if (s != null && e != null) {
                                            val l = (minOf(s.x, e.x) / currentViewportWidth).coerceIn(0f, 1f)
                                            val t = (minOf(s.y, e.y) / currentViewportHeight).coerceIn(0f, 1f)
                                            val r = (maxOf(s.x, e.x) / currentViewportWidth).coerceIn(0f, 1f)
                                            val b = (maxOf(s.y, e.y) / currentViewportHeight).coerceIn(0f, 1f)
                                            if (r - l > 0.01f && b - t > 0.01f) {
                                                commitSelection(Rect(l, t, r, b), selectionIsEllipse, false, selectionFeather)
                                            }
                                        }
                                        selDragStart = null; selDragCurrent = null
                                    },
                                )
                            }
                        }

                        if (isCropping) {
                            CropOverlay(
                                cx = cropCx, cy = cropCy, hx = cropHx, hy = cropHy, angleDeg = cropAngle,
                                onChange = { ncx, ncy, nhx, nhy ->
                                    cropCx = ncx; cropCy = ncy; cropHx = nhx; cropHy = nhy; cropAspect = null
                                },
                                onAngle = { cropAngle = it },
                            )
                        }
                    }
                }
            }

            // Commit healing stroke
            LaunchedEffect(currentHealingPoints.size) {
                if (currentHealingPoints.isNotEmpty() && editorMode == EditorMode.Healing) {
                    kotlinx.coroutines.delay(300)
                    val sx = healingSourceX; val sy = healingSourceY
                    if (sx != null && sy != null && currentHealingPoints.isNotEmpty()) {
                        val stroke = HealingStroke(sx, sy, currentHealingPoints, healingBrushSize, HealMode.Heal)
                        vm.applyToActivePixelLayer { HealingStrokes(listOf(stroke)).applyHealingToBitmap(it) }
                        currentHealingPoints = emptyList()
                    }
                }
            }

            // Commit dodge/burn/smudge stroke
            LaunchedEffect(retouchPoints.size) {
                if (retouchPoints.isNotEmpty() && (editorMode == EditorMode.DodgeBurn || editorMode == EditorMode.Smudge)) {
                    kotlinx.coroutines.delay(300)
                    val pts = retouchPoints
                    if (pts.isNotEmpty()) {
                        when (editorMode) {
                            EditorMode.DodgeBurn -> {
                                val s = DodgeBurnStroke(pts, dodgeBurnMode, exposure = 0.5f, brushSize = brushSize)
                                vm.applyToActivePixelLayer { DodgeBurnStrokes(listOf(s)).applyToBitmap(it) }
                            }
                            EditorMode.Smudge -> {
                                val s = SmudgeStroke(pts, strength = 0.5f, brushSize = brushSize)
                                vm.applyToActivePixelLayer { SmudgeStrokes(listOf(s)).applyToBitmap(it) }
                            }
                            else -> {}
                        }
                        retouchPoints = emptyList()
                    }
                }
            }

            // Bottom controls: Home (category bar) or a tool screen
            val cat = activeCategory
            Surface(color = MaterialTheme.colorScheme.surface) {
                when (cat) {
                null -> CategoryBar(onSelect = { openCategory(it) })
                ToolCategory.Draw -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { goHome() }) { IconBack() }
                            Text("Draw", fontWeight = FontWeight.Bold)
                            InfoHint("Draw freehand, highlight, erase, or tap to add text. Tap a tool again to change its color and size.")
                            Spacer(Modifier.weight(1f))
                            if (selectedTextId != null) {
                                IconButton(onClick = {
                                    texts.find { it.id == selectedTextId }?.let { te ->
                                        backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, te.color, te.fontSize, 1f))
                                    }
                                }) { IconEdit() }
                            }
                        }
                        DrawingToolbar(
                            activeTool = activeTool,
                            onSelectPointer = { activeTool = DrawingTool.Pointer; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null },
                            onSelectPen = {
                                if (activeTool == DrawingTool.Pen) backStack.add(EditRoute.DrawingSettings(DrawingTool.Pen, penColor.toArgb(), penSize, 1f))
                                else { activeTool = DrawingTool.Pen; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null }
                            },
                            onSelectHighlighter = {
                                if (activeTool == DrawingTool.Highlighter) backStack.add(EditRoute.DrawingSettings(DrawingTool.Highlighter, highlighterColor.toArgb(), highlighterSize, highlighterOpacity))
                                else { activeTool = DrawingTool.Highlighter; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null }
                            },
                            onSelectEraser = { activeTool = DrawingTool.Eraser; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null },
                            onSelectText = {
                                if (selectedTextId != null || activeTool == DrawingTool.Text) {
                                    if (selectedTextId != null) {
                                        texts.find { it.id == selectedTextId }?.let { te ->
                                            backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, te.color, te.fontSize, 1f))
                                        }
                                    } else backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, penColor.toArgb(), textFontSize, 1f))
                                } else { activeTool = DrawingTool.Text; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null }
                            },
                        )
                    }
                }
                else -> ToolScreen(
                    category = cat,
                    editorMode = editorMode,
                    onToolSelected = { selectTool(it) },
                    onBack = { goHome() },
                ) {
                    if (editorMode == EditorMode.Crop) {
                        CropRotatePanel(
                            onRotate90 = {
                                // Quarter-turn: keep the crop center, swap the box's
                                // pixel dimensions, and rotate 90°. Swapping extents +
                                // the 90° turn preserves the selected footprint (and
                                // two turns return exactly to the original box).
                                val w = document.canvasWidth.coerceAtLeast(1).toFloat()
                                val h = document.canvasHeight.coerceAtLeast(1).toFloat()
                                val newHx = cropHy * h / w
                                val newHy = cropHx * w / h
                                cropHx = newHx
                                cropHy = newHy
                                cropAngle = (cropAngle + 90f) % 360f
                                cropAspect = null
                            },
                            selectedAspect = cropAspect,
                            onAspect = { ar ->
                                cropAspect = ar
                                if (ar == null) {
                                    cropCx = 0.5f; cropCy = 0.5f; cropHx = 0.5f; cropHy = 0.5f
                                } else {
                                    val w = document.canvasWidth.coerceAtLeast(1).toFloat()
                                    val h = document.canvasHeight.coerceAtLeast(1).toFloat()
                                    val ratioN = ar * h / w
                                    cropCx = 0.5f; cropCy = 0.5f
                                    if (ratioN >= 1f) { cropHx = 0.5f; cropHy = 0.5f / ratioN } else { cropHy = 0.5f; cropHx = 0.5f * ratioN }
                                }
                            },
                            onReset = {
                                cropCx = 0.5f; cropCy = 0.5f; cropHx = 0.5f; cropHy = 0.5f
                                cropAngle = 0f; cropAspect = null
                            },
                            onApply = { applyCrop() },
                            onCancel = { goHome() },
                        )
                    } else {
                    ActivePanel(
                        editorMode = editorMode,
                        document = document,
                        baseBitmap = baseBitmap,
                        selection = selection,
                        vm = vm,
                        selectedAdjustment = selectedAdjustment,
                        onSelectAdjustment = { selectedAdjustment = it },
                        selectedCurveChannel = selectedCurveChannel,
                        onCurveChannel = { selectedCurveChannel = it },
                        selectedHslRange = selectedHslRange,
                        onHslRange = { selectedHslRange = it },
                        currentSelectiveMask = currentSelectiveMask,
                        showSelectiveMask = showSelectiveMask,
                        onSelectiveMask = { currentSelectiveMask = it },
                        onShowSelectiveMask = { showSelectiveMask = it },
                        healingBrushSize = healingBrushSize,
                        isSettingHealingSource = isSettingHealingSource,
                        onHealingBrushSize = { healingBrushSize = it },
                        onSetHealingSource = { isSettingHealingSource = it },
                        dodgeBurnMode = dodgeBurnMode,
                        onDodgeBurnMode = { dodgeBurnMode = it },
                        brushSize = brushSize,
                        onBrushSize = { brushSize = it },
                        selectionIsEllipse = selectionIsEllipse,
                        onSelectionShape = { selectionIsEllipse = it },
                        selectionFeather = selectionFeather,
                        onSelectionFeather = {
                            selectionFeather = it
                            committedSel?.let { s ->
                                committedSel = s.copy(
                                    featherFracX = it / document.canvasWidth.coerceAtLeast(1),
                                    featherFracY = it / document.canvasHeight.coerceAtLeast(1),
                                )
                            }
                        },
                        liquifyTool = liquifyTool,
                        onLiquifyTool = { liquifyTool = it },
                        liquifyStrength = liquifyStrength,
                        onLiquifyStrength = { liquifyStrength = it },
                        liquifyRadius = liquifyRadius,
                        onLiquifyRadius = { liquifyRadius = it },
                        onSelectionInvert = { committedSel?.let { s -> commitSelection(s.rect, s.isEllipse, !s.inverted, selectionFeather) } },
                        onSelectionClear = { committedSel = null; vm.setSelection(null) },
                        onSelectionDelete = {
                            vm.applyToActivePixelLayer { src ->
                                android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
                            }
                        },
                        onSelectionFeatherCommit = { committedSel?.let { s -> commitSelection(s.rect, s.isEllipse, s.inverted, selectionFeather) } },
                    )
                    }
                }
                }
            }
        }
    }

    textToEdit?.let { textElement ->
        Dialog(onDismissRequest = { textToEdit = null }) {
            Surface(shape = RoundedCornerShape(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var localText by remember { mutableStateOf(textElement.text) }
                    TextField(
                        value = localText,
                        onValueChange = { newText ->
                            localText = newText
                            val index = texts.indexOfFirst { it.id == textElement.id }
                            if (index != -1) texts[index] = texts[index].copy(text = newText)
                        },
                        textStyle = TextStyle(fontSize = 18.sp),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Edit Text") },
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { textToEdit = null }) { IconCheck() }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBar(onSelect: (ToolCategory) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolCategory.entries.forEach { cat ->
            Column(
                modifier = Modifier.clickable { onSelect(cat) }.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CategoryIcon(cat)
                Text(cat.label, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CategoryIcon(category: ToolCategory) {
    when (category) {
        ToolCategory.Adjust -> IconSettings()
        ToolCategory.Filters -> IconStar()
        ToolCategory.Retouch -> IconBrush()
        ToolCategory.Select -> Icon(Icons.Outlined.HighlightAlt, contentDescription = null)
        ToolCategory.Transform -> IconCrop()
        ToolCategory.Draw -> IconDraw()
        ToolCategory.Layers -> IconCopy()
    }
}

@Composable
private fun ToolScreen(
    category: ToolCategory,
    editorMode: EditorMode,
    onToolSelected: (EditorMode) -> Unit,
    onBack: () -> Unit,
    panel: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { IconBack() }
            Text(category.label, fontWeight = FontWeight.Bold)
            InfoHint(category.description())
        }
        if (categoryTools[category].orEmpty().size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                categoryTools[category].orEmpty().forEach { entry ->
                    Surface(
                        modifier = Modifier.clickable { onToolSelected(entry.mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (editorMode == entry.mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(entry.label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
        val toolLabel = categoryTools[category]?.firstOrNull { it.mode == editorMode }?.label
        if (toolLabel != null && editorMode != EditorMode.None) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(toolLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                InfoHint(editorMode.description())
            }
        }
        panel()
    }
}

/** Positions a popup directly above its anchor (a "drop-up"). */
private class AbovePopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** A small ⓘ icon that toggles an inline drop-up description (not a dialog). */
@Composable
private fun InfoHint(text: String) {
    if (text.isBlank()) return
    var open by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    Box {
        Text(
            "ⓘ",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).clickable { open = !open },
        )
        if (open) {
            Popup(
                popupPositionProvider = AbovePopupPositionProvider(gapPx),
                onDismissRequest = { open = false },
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.widthIn(max = 240.dp).padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CropRotatePanel(
    onRotate90: () -> Unit,
    selectedAspect: Float?,
    onAspect: (Float?) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val aspects: List<Pair<String, Float?>> = listOf(
        "Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f,
    )
    PanelContainer(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aspect ratio", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint("Locks the crop to a fixed width:height shape. \"Free\" lets you crop to any size.")
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            aspects.forEach { (label, ar) ->
                SelectableChip(label, selectedAspect == ar, { onAspect(ar) })
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rotate", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint("Drag the round handle above the crop box to tilt and straighten the photo.")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.clickable { onRotate90() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconRotateRight(); Text("Rotate 90°", fontSize = 13.sp) }
            }
            Surface(
                modifier = Modifier.clickable { onReset() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) { Text("Reset", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Surface(
                modifier = Modifier.clickable { onCancel() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconClose(); Text("Cancel", fontSize = 13.sp) }
            }
            Surface(
                modifier = Modifier.clickable { onApply() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconCheck(); Text("Apply", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun ActivePanel(
    editorMode: EditorMode,
    document: EditDocument,
    baseBitmap: android.graphics.Bitmap?,
    selection: Selection?,
    vm: PhotoEditViewModel,
    selectedAdjustment: AdjustmentType,
    onSelectAdjustment: (AdjustmentType) -> Unit,
    selectedCurveChannel: CurveChannel,
    onCurveChannel: (CurveChannel) -> Unit,
    selectedHslRange: HslColorRange,
    onHslRange: (HslColorRange) -> Unit,
    currentSelectiveMask: SelectiveMask,
    showSelectiveMask: Boolean,
    onSelectiveMask: (SelectiveMask) -> Unit,
    onShowSelectiveMask: (Boolean) -> Unit,
    healingBrushSize: Float,
    isSettingHealingSource: Boolean,
    onHealingBrushSize: (Float) -> Unit,
    onSetHealingSource: (Boolean) -> Unit,
    dodgeBurnMode: DodgeBurnMode,
    onDodgeBurnMode: (DodgeBurnMode) -> Unit,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    selectionIsEllipse: Boolean,
    onSelectionShape: (Boolean) -> Unit,
    selectionFeather: Float,
    onSelectionFeather: (Float) -> Unit,
    liquifyTool: com.vayunmathur.photos.data.LiquifyTool,
    onLiquifyTool: (com.vayunmathur.photos.data.LiquifyTool) -> Unit,
    liquifyStrength: Float,
    onLiquifyStrength: (Float) -> Unit,
    liquifyRadius: Float,
    onLiquifyRadius: (Float) -> Unit,
    onSelectionInvert: () -> Unit,
    onSelectionClear: () -> Unit,
    onSelectionDelete: () -> Unit,
    onSelectionFeatherCommit: () -> Unit,
) {
    when (editorMode) {
        EditorMode.Adjust -> {
            val basic = document.activeAdjustment<BasicAdjustment>()?.adjustments ?: ImageAdjustments()
            AdjustmentPanel(
                adjustments = basic,
                selectedAdjustment = selectedAdjustment,
                onSelectAdjustment = onSelectAdjustment,
                onUpdateAdjustment = { update -> vm.updateActiveAdjustment(BasicAdjustment(update(basic))) },
                onReset = { vm.updateActiveAdjustment(BasicAdjustment(ImageAdjustments())) },
            )
        }
        EditorMode.Filters -> {
            val basic = document.activeAdjustment<BasicAdjustment>()?.adjustments ?: ImageAdjustments()
            FilterPresetPanel(
                bitmap = baseBitmap,
                adjustments = basic,
                onSelectFilter = { filter -> vm.updateActiveAdjustment(BasicAdjustment(filter.adjustments)) },
            )
        }
        EditorMode.Curves -> {
            val curves = document.activeAdjustment<CurvesAdj>()?.curves ?: CurvesAdjustment()
            CurvesPanel(curves, selectedCurveChannel, onCurveChannel) { vm.updateActiveAdjustment(CurvesAdj(it)) }
        }
        EditorMode.HSL -> {
            val hsl = document.activeAdjustment<HslAdj>()?.hsl ?: HslAdjustments()
            HslPanel(hsl, selectedHslRange, onHslRange) { vm.updateActiveAdjustment(HslAdj(it)) }
        }
        EditorMode.Levels -> {
            val levels = document.activeAdjustment<LevelsAdj>()?.levels ?: com.vayunmathur.photos.data.LevelsAdjustment()
            LevelsPanel(levels) { vm.updateActiveAdjustment(LevelsAdj(it)) }
        }
        EditorMode.ColorBalance -> {
            val cb = document.activeAdjustment<ColorBalanceAdj>()?.balance ?: com.vayunmathur.photos.data.ColorBalanceAdjustment()
            ColorBalancePanel(cb) { vm.updateActiveAdjustment(ColorBalanceAdj(it)) }
        }
        EditorMode.ChannelMixer -> {
            val mx = document.activeAdjustment<ChannelMixerAdj>()?.mixer ?: com.vayunmathur.photos.data.ChannelMixerAdjustment()
            ChannelMixerPanel(mx) { vm.updateActiveAdjustment(ChannelMixerAdj(it)) }
        }
        EditorMode.BlackWhite -> {
            val bw = document.activeAdjustment<BlackAndWhiteAdj>()?.bw ?: com.vayunmathur.photos.data.BlackAndWhiteAdjustment(enabled = true)
            BlackWhitePanel(bw) { vm.updateActiveAdjustment(BlackAndWhiteAdj(it.copy(enabled = true))) }
        }
        EditorMode.GradientMap -> {
            GradientMapPanel { stops -> vm.updateActiveAdjustment(GradientMapAdj(com.vayunmathur.photos.data.GradientMapAdjustment(stops))) }
        }
        EditorMode.LensBlur -> {
            val blur = document.activeAdjustment<BlurAdj>()?.blur ?: BlurParams()
            BlurPanel(blur) { vm.updateActiveAdjustment(BlurAdj(it)) }
        }
        EditorMode.Selective -> {
            val sel = document.activeAdjustment<SelectiveAdj>()?.selective ?: SelectiveEdits()
            SelectiveEditPanel(
                mask = currentSelectiveMask,
                showMask = showSelectiveMask,
                onMaskChanged = onSelectiveMask,
                onShowMaskChanged = onShowSelectiveMask,
                onAddMask = {
                    vm.updateActiveAdjustment(SelectiveAdj(sel.copy(masks = sel.masks + currentSelectiveMask)))
                    onSelectiveMask(SelectiveMask())
                },
            )
        }
        EditorMode.FilterFx -> FiltersPanel { transform -> vm.applyToActivePixelLayer(transform) }
        EditorMode.Liquify -> LiquifyPanel(liquifyTool, onLiquifyTool, liquifyStrength, onLiquifyStrength, liquifyRadius, onLiquifyRadius)
        EditorMode.Healing -> HealingPanel(healingBrushSize, isSettingHealingSource, onHealingBrushSize, onSetHealingSource)
        EditorMode.RedEye -> SimpleBrushPanel("Tap each eye. Brush", brushSize, onBrushSize)
        EditorMode.DodgeBurn -> DodgeBurnPanel(dodgeBurnMode, onDodgeBurnMode, brushSize, onBrushSize)
        EditorMode.Smudge -> SimpleBrushPanel("Drag to smudge. Brush", brushSize, onBrushSize)
        EditorMode.Selection -> SelectionPanel(
            isEllipse = selectionIsEllipse, onShape = onSelectionShape,
            feather = selectionFeather, onFeather = onSelectionFeather,
            onFeatherCommit = onSelectionFeatherCommit,
            hasSelection = selection != null,
            onInvert = onSelectionInvert,
            onClear = onSelectionClear,
            onDelete = onSelectionDelete,
        )
        EditorMode.Layers -> LayersPanel(
            document = document,
            hasSelection = selection != null,
            onSelectLayer = { vm.setActiveLayer(it) },
            onToggleVisibility = { i, v -> vm.setLayerVisibility(i, v) },
            onOpacityChange = { i, o -> vm.setLayerOpacity(i, o) },
            onBlendModeChange = { i, m -> vm.setLayerBlendMode(i, m) },
            onAddAdjustment = { vm.addAdjustmentLayer(it) },
            onAddPixelLayer = { vm.addEmptyPixelLayer() },
            onDuplicate = { vm.duplicateLayer(it) },
            onMergeDown = { vm.mergeDown(it) },
            onDelete = { vm.removeLayer(it) },
            onFlatten = { vm.flatten() },
            onAddMaskFromSelection = { vm.selectionToActiveMask() },
            onDeleteMask = { vm.deleteLayerMask(it) },
            onInvertMask = { vm.invertLayerMask(it) },
        )
        EditorMode.Crop -> {}
        EditorMode.None -> {}
    }
}

@Composable
private fun LiquifyPanel(
    tool: com.vayunmathur.photos.data.LiquifyTool,
    onTool: (com.vayunmathur.photos.data.LiquifyTool) -> Unit,
    strength: Float,
    onStrength: (Float) -> Unit,
    radius: Float,
    onRadius: (Float) -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.vayunmathur.photos.data.LiquifyTool.entries.forEach { t ->
                SelectableChip(t.name, tool == t, { onTool(t) }, horizontalPadding = 10.dp)
            }
        }
        LabeledSlider("Strength", strength * 100f, 5f..100f) { onStrength(it / 100f) }
        LabeledSlider("Size", radius * 100f, 5f..50f) { onRadius(it / 100f) }
    }
}

@Composable
private fun SimpleBrushPanel(label: String, brushSize: Float, onBrushSize: (Float) -> Unit) {
    PanelContainer {
        LabeledSlider(label, brushSize * 100f, 1f..20f) { onBrushSize(it / 100f) }
    }
}

@Composable
private fun DodgeBurnPanel(mode: DodgeBurnMode, onMode: (DodgeBurnMode) -> Unit, brushSize: Float, onBrushSize: (Float) -> Unit) {
    PanelContainer {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DodgeBurnMode.entries.forEach { m ->
                SelectableChip(m.name, mode == m, { onMode(m) })
            }
        }
        LabeledSlider("Brush", brushSize * 100f, 1f..20f) { onBrushSize(it / 100f) }
    }
}

@Composable
private fun SelectionPanel(
    isEllipse: Boolean,
    onShape: (Boolean) -> Unit,
    feather: Float,
    onFeather: (Float) -> Unit,
    onFeatherCommit: () -> Unit,
    hasSelection: Boolean,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "Rectangle", true to "Ellipse").forEach { (ell, label) ->
                SelectableChip(label, isEllipse == ell, { onShape(ell) })
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Edge softness", fontSize = 12.sp, modifier = Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint("Blurs the selection's edge so edits fade in gradually. 0 = a hard, crisp edge (this is normal, not \"nothing selected\").")
            androidx.compose.material3.Slider(value = feather, onValueChange = onFeather, onValueChangeFinished = onFeatherCommit, valueRange = 0f..50f, modifier = Modifier.weight(1f))
            Text("${feather.roundToInt()}", fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Invert", hasSelection, onInvert)
            InfoHint("Swaps inside and outside, so your edits apply everywhere except the selected area.")
            SmallButton("Delete selected area", hasSelection, onDelete)
            InfoHint("Erases the pixels inside the selection on the current layer, leaving transparency.")
            SmallButton("Clear selection", hasSelection, onClear)
        }
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) { Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
}

@Composable
private fun SelectionOverlay(
    isEllipse: Boolean,
    dragStart: Offset?,
    dragCurrent: Offset?,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(isEllipse) {
            detectDragGestures(
                onDragStart = { onStart(it) },
                onDrag = { change, _ -> change.consume(); onDrag(change.position) },
                onDragEnd = { onEnd() },
            )
        },
    ) {
        val s = dragStart; val c = dragCurrent
        if (s != null && c != null) {
            val topLeft = Offset(minOf(s.x, c.x), minOf(s.y, c.y))
            val sz = Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y))
            val effect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            if (isEllipse) drawOval(Color.White, topLeft, sz, style = Stroke(width = 2f, pathEffect = effect))
            else drawRect(Color.White, topLeft, sz, style = Stroke(width = 2f, pathEffect = effect))
        }
    }
}

@Composable
private fun SelectionPatternOverlay(shape: SelShape) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val fx = shape.featherFracX * w
        val fy = shape.featherFracY * h
        val r = shape.rect
        val outer = Rect(r.left * w - fx, r.top * h - fy, r.right * w + fx, r.bottom * h + fy)
        val inner = Rect(r.left * w + fx, r.top * h + fy, r.right * w - fx, r.bottom * h - fy)
        fun shapePath(rect: Rect): Path = Path().apply {
            if (rect.width <= 0f || rect.height <= 0f) return@apply
            if (shape.isEllipse) addOval(rect) else addRect(rect)
        }
        val outerPath = shapePath(outer)
        val innerPath = shapePath(inner)
        val bandPath = Path().apply {
            addPath(outerPath); addPath(innerPath); fillType = PathFillType.EvenOdd
        }
        val fullEffectPath = if (shape.inverted) {
            Path().apply { addRect(Rect(0f, 0f, w, h)); addPath(outerPath); fillType = PathFillType.EvenOdd }
        } else {
            innerPath
        }
        clipPath(fullEffectPath) { drawDiagonalHatch(w, h, 24f, Color.White.copy(alpha = 0.55f)) }
        clipPath(bandPath) { drawDiagonalHatch(w, h, 9f, Color.White.copy(alpha = 0.75f)) }
        drawPath(innerPath, Color.White, style = Stroke(width = 2f))
        if (shape.featherFracX > 0f || shape.featherFracY > 0f) {
            drawPath(outerPath, Color.White, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
        }
    }
}

private fun DrawScope.drawDiagonalHatch(w: Float, h: Float, spacing: Float, color: Color) {
    var x = -h
    while (x < w) {
        drawLine(color, Offset(x, 0f), Offset(x + h, h), strokeWidth = 1.5f)
        x += spacing
    }
}

@Composable
private fun DrawingToolbar(
    activeTool: DrawingTool,
    onSelectPointer: () -> Unit,
    onSelectPen: () -> Unit,
    onSelectHighlighter: () -> Unit,
    onSelectEraser: () -> Unit,
    onSelectText: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIcon(active = activeTool == DrawingTool.Pointer, onClick = onSelectPointer) { IconVisible() }
        ToolIcon(active = activeTool == DrawingTool.Pen, onClick = onSelectPen) { IconDraw() }
        ToolIcon(active = activeTool == DrawingTool.Highlighter, onClick = onSelectHighlighter) { IconBrush() }
        ToolIcon(active = activeTool == DrawingTool.Eraser, onClick = onSelectEraser) { IconEraser() }
        ToolIcon(active = activeTool == DrawingTool.Text, onClick = onSelectText) {
            Text("T", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ToolIcon(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier.size(40.dp).then(
                if (active) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape) else Modifier
            ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
fun CropOverlay(
    cx: Float,
    cy: Float,
    hx: Float,
    hy: Float,
    angleDeg: Float,
    onChange: (cx: Float, cy: Float, hx: Float, hy: Float) -> Unit,
    onAngle: (Float) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val onChangeNow by rememberUpdatedState(onChange)
        val onAngleNow by rememberUpdatedState(onAngle)
        val armGapPx = with(LocalDensity.current) { 40.dp.toPx() }
        val minPx = with(LocalDensity.current) { 24.dp.toPx() }

        val a = Math.toRadians(angleDeg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        // rotate a vector by +angle
        fun rot(vx: Float, vy: Float) = Offset(vx * ca - vy * sa, vx * sa + vy * ca)
        // rotate a vector by -angle (into local frame)
        fun unrot(vx: Float, vy: Float) = Offset(vx * ca + vy * sa, -vx * sa + vy * ca)

        val cpx = cx * width
        val cpy = cy * height
        val phx = hx * width
        val phy = hy * height
        fun corner(sx: Int, sy: Int): Offset {
            val r = rot(sx * phx, sy * phy)
            return Offset(cpx + r.x, cpy + r.y)
        }
        val tl = corner(-1, -1)
        val tr = corner(1, -1)
        val br = corner(1, 1)
        val bl = corner(-1, 1)
        fun mid(p: Offset, q: Offset) = Offset((p.x + q.x) / 2f, (p.y + q.y) / 2f)
        val topMid = mid(tl, tr)
        val up = rot(0f, -1f)
        val rotateHandle = Offset(topMid.x + up.x * armGapPx, topMid.y + up.y * armGapPx)

        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val quad = Path().apply {
                moveTo(tl.x, tl.y); lineTo(tr.x, tr.y); lineTo(br.x, br.y); lineTo(bl.x, bl.y); close()
            }
            val dim = Path().apply {
                addRect(Rect(0f, 0f, width, height)); addPath(quad); fillType = PathFillType.EvenOdd
            }
            drawPath(dim, Color.Black.copy(alpha = 0.5f))
            drawPath(quad, Color.White, style = Stroke(width = 2.dp.toPx()))
            drawLine(Color.White, topMid, rotateHandle, strokeWidth = 2.dp.toPx())
        }

        // Body drag (move the whole quad).
        Handle(Offset(cpx, cpy)) { d ->
            onChangeNow((cpx + d.x) / width, (cpy + d.y) / height, hx, hy)
        }

        // Corner handles: keep the opposite corner fixed.
        fun cornerDrag(sx: Int, sy: Int, d: Offset) {
            val opp = corner(-sx, -sy)
            val newC = Offset(corner(sx, sy).x + d.x, corner(sx, sy).y + d.y)
            val nCenter = Offset((opp.x + newC.x) / 2f, (opp.y + newC.y) / 2f)
            val local = unrot(newC.x - opp.x, newC.y - opp.y)
            val nhpx = (abs(local.x) / 2f).coerceAtLeast(minPx)
            val nhpy = (abs(local.y) / 2f).coerceAtLeast(minPx)
            onChangeNow(nCenter.x / width, nCenter.y / height, nhpx / width, nhpy / height)
        }
        Handle(tl) { d -> cornerDrag(-1, -1, d) }
        Handle(tr) { d -> cornerDrag(1, -1, d) }
        Handle(br) { d -> cornerDrag(1, 1, d) }
        Handle(bl) { d -> cornerDrag(-1, 1, d) }

        // Edge handles: move one edge along its local normal, opposite edge fixed.
        fun edgeDrag(edge: Int, d: Offset) {
            val local = unrot(d.x, d.y)
            var nhpx = phx
            var nhpy = phy
            var shiftLocalX = 0f
            var shiftLocalY = 0f
            when (edge) {
                0 -> { nhpx = phx - local.x / 2f; shiftLocalX = local.x / 2f } // left
                1 -> { nhpx = phx + local.x / 2f; shiftLocalX = local.x / 2f } // right
                2 -> { nhpy = phy - local.y / 2f; shiftLocalY = local.y / 2f } // top
                3 -> { nhpy = phy + local.y / 2f; shiftLocalY = local.y / 2f } // bottom
            }
            nhpx = nhpx.coerceAtLeast(minPx)
            nhpy = nhpy.coerceAtLeast(minPx)
            val shift = rot(shiftLocalX, shiftLocalY)
            onChangeNow((cpx + shift.x) / width, (cpy + shift.y) / height, nhpx / width, nhpy / height)
        }
        Handle(mid(tl, bl)) { d -> edgeDrag(0, d) }
        Handle(mid(tr, br)) { d -> edgeDrag(1, d) }
        Handle(topMid) { d -> edgeDrag(2, d) }
        Handle(mid(bl, br)) { d -> edgeDrag(3, d) }

        // Rotate handle.
        Handle(rotateHandle) { d ->
            val px = rotateHandle.x + d.x
            val py = rotateHandle.y + d.y
            val ang = Math.toDegrees(atan2((px - cpx).toDouble(), (cpy - py).toDouble())).toFloat()
            onAngleNow(ang)
        }
    }
}

@Composable
fun Handle(offset: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 24.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset { IntOffset((offset.x - handleRadiusPx).roundToInt(), (offset.y - handleRadiusPx).roundToInt()) }
            .size(handleSize)
            .background(Color.White, CircleShape)
            .border(1.dp, Color.Black, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount -> change.consume(); currentOnDrag(dragAmount) }
            },
    )
}

@Composable
private fun PanelContainer(
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    cornerRadius: Dp = 6.dp,
    horizontalPadding: Dp = 12.dp,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp))
    }
}

@Composable
private fun AdjustmentPanel(
    adjustments: ImageAdjustments,
    selectedAdjustment: AdjustmentType,
    onSelectAdjustment: (AdjustmentType) -> Unit,
    onUpdateAdjustment: ((ImageAdjustments) -> ImageAdjustments) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdjustmentType.entries.forEach { type ->
                val value = type.get(adjustments)
                Surface(
                    modifier = Modifier.clickable { onSelectAdjustment(type) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selectedAdjustment == type) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        if (value != 0f) "${type.label} ${value.roundToInt()}" else type.label,
                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        val currentValue = selectedAdjustment.get(adjustments)
        LabeledSlider(selectedAdjustment.label, currentValue, selectedAdjustment.min..selectedAdjustment.max) {
            onUpdateAdjustment { adj -> selectedAdjustment.set(adj, it) }
        }
        if (adjustments != ImageAdjustments()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Surface(
                    modifier = Modifier.clickable { onReset() }.padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text("Reset All", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun FilterPresetPanel(
    bitmap: android.graphics.Bitmap?,
    adjustments: ImageAdjustments,
    onSelectFilter: (PhotoFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoFilters.all.forEach { filter ->
            val isSelected = if (filter.adjustments == ImageAdjustments()) adjustments == ImageAdjustments() else adjustments == filter.adjustments
            Column(
                modifier = Modifier.width(72.dp).clickable { onSelectFilter(filter) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                bitmap?.let { bmp ->
                    val filterMatrix = remember(filter) { filter.adjustments.toColorMatrix() }
                    val hasFilter = filter.adjustments != ImageAdjustments()
                    Box(
                        modifier = Modifier.size(64.dp).then(
                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier
                        ),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = filter.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            colorFilter = if (hasFilter) androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(filterMatrix.array)) else null,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(filter.name, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun hitTestText(
    x: Float, y: Float, texts: List<TextElement>, viewportWidth: Float, viewportHeight: Float, density: Float,
): Int? {
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    for (i in texts.indices.reversed()) {
        val elem = texts[i]
        paint.textSize = elem.fontSize * density
        val textWidth = paint.measureText(elem.text)
        val textHeight = paint.textSize
        val ex = elem.x * viewportWidth
        val ey = elem.y * viewportHeight
        if (x in ex..(ex + textWidth) && y in ey..(ey + textHeight + 4f)) return i
    }
    return null
}

private fun hitTestStroke(x: Float, y: Float, strokes: List<InkStroke>): Int? {
    val hitRadius = 20f
    for (i in strokes.indices.reversed()) {
        strokes[i].shape.computeBoundingBox()?.let { box ->
            if (box.xMin <= x + hitRadius && box.xMax >= x - hitRadius && box.yMin <= y + hitRadius && box.yMax >= y - hitRadius) return i
        }
    }
    return null
}
