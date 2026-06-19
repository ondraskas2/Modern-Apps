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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.hasPixelEffects
import com.vayunmathur.photos.data.PhotoFilter
import com.vayunmathur.photos.data.PhotoFilters
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.toBrush
import com.vayunmathur.photos.data.toColorMatrix
import com.vayunmathur.photos.data.CurveChannel
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.HslColorRange
import com.vayunmathur.photos.data.BlurParams
import com.vayunmathur.photos.data.SelectiveEdits
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.HealingStrokes
import com.vayunmathur.photos.data.HealingStroke
import com.vayunmathur.photos.data.PerspectiveCorners
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.SerializedStroke
import com.vayunmathur.library.util.deserialize
import com.vayunmathur.library.util.serialize
import com.vayunmathur.library.ui.CanvasTextElement
import com.vayunmathur.library.ui.InkCanvasView
import com.vayunmathur.library.util.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke as InkStroke
import com.vayunmathur.library.ui.IconBrush
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconEraser
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.vayunmathur.library.ui.IconRotateLeft
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.util.PhotoEditViewModel
import java.util.UUID
import kotlin.math.roundToInt

private enum class EditorMode { None, Adjust, Filters, Curves, HSL, Blur, Selective, Healing, Perspective }

private enum class AdjustmentType(val label: String, val min: Float, val max: Float) {
    Brightness("Brightness", -100f, 100f),
    Contrast("Contrast", -100f, 100f),
    Saturation("Saturation", -100f, 100f),
    Warmth("Warmth", -100f, 100f),
    Exposure("Exposure", -100f, 100f),
    Highlights("Highlights", -100f, 100f),
    Shadows("Shadows", -100f, 100f),
    Sharpness("Sharpness", 0f, 100f),
    Vignette("Vignette", 0f, 100f),
    Grain("Grain", 0f, 100f),
    Fade("Fade", 0f, 100f),
    Tint("Tint", -100f, 100f),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPhotoPage(
    backStack: NavBackStack<EditRoute>,
    photoDao: PhotoDao,
    photoEditViewModel: PhotoEditViewModel,
    id: Long,
    initialUri: String? = null
) {
    val context = LocalActivity.current!!
    val photoFromDb by photoDao.getByIdFlow(id).collectAsState(initial = null)
    val photo = remember(photoFromDb, initialUri) {
        photoFromDb ?: initialUri?.let { uri ->
            Photo(
                id = 0,
                name = uri.substringAfterLast("/"),
                uri = uri,
                date = System.currentTimeMillis(),
                width = 0,
                height = 0,
                dateModified = System.currentTimeMillis() / 1000,
                exifSet = false,
                lat = null,
                long = null,
                videoData = null
            )
        }
    }

    var isCropping by remember { mutableStateOf(false) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var cropRect by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var startCropRect by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var showSaveMenu by remember { mutableStateOf(false) }

    var editorMode by remember { mutableStateOf(EditorMode.None) }
    var selectedAdjustment by remember { mutableStateOf(AdjustmentType.Brightness) }
    val adjustments by photoEditViewModel.adjustments.collectAsState()
    val selectedFilter by photoEditViewModel.selectedFilter.collectAsState()

    val curvesAdjustment by photoEditViewModel.curvesAdjustment.collectAsState()
    val hslAdjustments by photoEditViewModel.hslAdjustments.collectAsState()
    val blurParams by photoEditViewModel.blurParams.collectAsState()
    val selectiveEdits by photoEditViewModel.selectiveEdits.collectAsState()
    val healingStrokes by photoEditViewModel.healingStrokes.collectAsState()
    val perspectiveCorners by photoEditViewModel.perspectiveCorners.collectAsState()
    val previewBitmap by photoEditViewModel.previewBitmap.collectAsState()

    var selectedCurveChannel by remember { mutableStateOf(CurveChannel.Combined) }
    var selectedHslRange by remember { mutableStateOf(HslColorRange.Red) }
    var currentSelectiveMask by remember { mutableStateOf(SelectiveMask()) }
    var showSelectiveMask by remember { mutableStateOf(false) }
    var healingBrushSize by remember { mutableFloatStateOf(0.02f) }
    var isSettingHealingSource by remember { mutableStateOf(true) }
    var healingSourceX by remember { mutableStateOf<Float?>(null) }
    var healingSourceY by remember { mutableStateOf<Float?>(null) }
    var currentHealingPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    var isDrawing by remember { mutableStateOf(false) }
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
        if (activeTool == DrawingTool.Pointer) {
            scale *= zoomChange
            offset += offsetChange
        }
    }

    val texts = remember { mutableStateListOf<TextElement>() }
    var selectedTextId by remember { mutableStateOf<String?>(null) }
    var selectedStrokeIndex by remember { mutableStateOf<Int?>(null) }
    var selectedTextIndex by remember { mutableStateOf<Int?>(null) }
    var textToEdit by remember { mutableStateOf<TextElement?>(null) }
    var currentViewportWidth by remember { mutableFloatStateOf(1f) }
    var currentViewportHeight by remember { mutableFloatStateOf(1f) }

    val currentBrush: Brush = remember(activeTool, penColor, penSize, highlighterColor, highlighterSize, highlighterOpacity) {
        when (activeTool) {
            DrawingTool.Pen -> Brush.createWithColorIntArgb(
family = StockBrushes.pressurePen(),
                colorIntArgb = penColor.toArgb(),
                size = penSize,
                epsilon = 0.1f,
            )
            DrawingTool.Highlighter -> {
                val argb = highlighterColor.toArgb()
                val alpha = (highlighterOpacity * 255).roundToInt()
                val colorWithAlpha = (alpha shl 24) or (argb and 0x00FFFFFF)
                Brush.createWithColorIntArgb(
                    family = StockBrushes.highlighter(),
                    colorIntArgb = colorWithAlpha,
                    size = highlighterSize,
                    epsilon = 0.1f,
                )
            }
            else -> Brush.createWithColorIntArgb(
family = StockBrushes.pressurePen(),
                colorIntArgb = penColor.toArgb(),
                size = penSize,
                epsilon = 0.1f,
            )
        }
    }

    data class EditState(
        val rotation: Float,
        val cropRect: Rect,
        val strokes: List<SerializedStroke>,
        val texts: List<TextElement>,
        val adjustments: ImageAdjustments = ImageAdjustments(),
        val curves: CurvesAdjustment = CurvesAdjustment(),
        val hsl: HslAdjustments = HslAdjustments(),
        val blur: BlurParams = BlurParams(),
        val selective: SelectiveEdits = SelectiveEdits(),
        val healing: HealingStrokes = HealingStrokes(),
        val perspective: PerspectiveCorners = PerspectiveCorners(),
    )

    val history = remember { mutableStateListOf<EditState>() }

    fun pushState() {
        history.add(EditState(
            rotation, cropRect, inkStrokes.map { it.serialize() }, texts.toList(),
            adjustments, curvesAdjustment, hslAdjustments, blurParams,
            selectiveEdits, healingStrokes, perspectiveCorners,
        ))
    }

    fun undo() {
        if (history.isNotEmpty()) {
            val lastState = history.removeAt(history.size - 1)
            rotation = lastState.rotation
            cropRect = lastState.cropRect
            inkStrokes.clear()
            inkStrokes.addAll(lastState.strokes.mapNotNull { try { it.deserialize() } catch (_: Exception) { null } })
            texts.clear()
            texts.addAll(lastState.texts)
            photoEditViewModel.updateAdjustment { lastState.adjustments }
            photoEditViewModel.updateCurves(lastState.curves)
            photoEditViewModel.updateHsl(lastState.hsl)
            photoEditViewModel.updateBlur(lastState.blur)
            photoEditViewModel.updateSelective(lastState.selective)
            photoEditViewModel.updateHealing(lastState.healing)
            photoEditViewModel.updatePerspective(lastState.perspective)
        }
    }

    ResultEffect<DrawingSettingsResult>("drawing_settings") { result ->
        var changed = false

        selectedTextId?.let { id ->
            val index = texts.indexOfFirst { it.id == id }
            if (index != -1) {
                pushState()
                texts[index] = texts[index].copy(
                    color = result.color,
                    fontSize = result.thickness
                )
                changed = true
            }
        }

        if (!changed) {
            activeTool = result.tool
            when (result.tool) {
                DrawingTool.Pen -> {
                    penColor = Color(result.color)
                    penSize = result.thickness
                }
                DrawingTool.Highlighter -> {
                    highlighterColor = Color(result.color)
                    highlighterSize = result.thickness
                    highlighterOpacity = result.opacity
                }
                DrawingTool.Eraser -> {}
                DrawingTool.Text -> {
                    penColor = Color(result.color)
                    textFontSize = result.thickness
                }
                DrawingTool.Pointer -> {}
            }
        }
    }

    val originalBitmap by photoEditViewModel.originalBitmap.collectAsState()
    val transformedBitmap by photoEditViewModel.transformedBitmap.collectAsState()

    LaunchedEffect(photo?.uri) {
        val uri = photo?.uri?.toUri() ?: return@LaunchedEffect
        photoEditViewModel.decode(uri)
    }

    LaunchedEffect(originalBitmap, rotation, isCropping, if (isCropping) Unit else cropRect) {
        if (originalBitmap != null) {
            photoEditViewModel.applyTransform(rotation, cropRect, isCropping)
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            photoEditViewModel.onWritePermissionGranted()
        } else {
            photoEditViewModel.onWritePermissionDenied()
        }
    }

    val writePermissionRequest by photoEditViewModel.writePermissionRequest.collectAsState()
    LaunchedEffect(writePermissionRequest) {
        writePermissionRequest?.let { intentSender ->
            writePermissionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    fun doSave(asCopy: Boolean) {
        photo?.let {
            photoEditViewModel.savePhoto(
                it, rotation, cropRect,
                inkStrokes.map { s -> s.serialize() },
                texts.toList(), currentViewportWidth, currentViewportHeight, asCopy, adjustments,
                curvesAdjustment, hslAdjustments, blurParams,
                selectiveEdits, healingStrokes, perspectiveCorners,
            ) { context.finish() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_edit_photo)) },
                navigationIcon = {
                    IconButton(onClick = { context.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (isCropping) {
                        IconButton(onClick = {
                    if (cropRect != startCropRect) {
                                history.add(
                                    EditState(
                                        rotation, startCropRect,
                                        inkStrokes.map { it.serialize() }, texts.toList(),
                                        adjustments, curvesAdjustment, hslAdjustments, blurParams,
                                        selectiveEdits, healingStrokes, perspectiveCorners,
                                    )
                                )
                            }
                            isCropping = false
                        }) {
                            IconCheck()
                        }
                        IconButton(onClick = {
                            cropRect = startCropRect
                            isCropping = false
                        }) {
                            IconClose()
                        }
                    } else {
                        IconButton(
                            onClick = { undo() },
                            enabled = history.isNotEmpty()
                        ) {
                            IconUndo()
                        }
                        if (selectedTextId != null) {
                            IconButton(onClick = {
                                selectedTextId?.let { id ->
                                    texts.find { it.id == id }?.let { textElement ->
                                        backStack.add(
                                            EditRoute.DrawingSettings(
                                                DrawingTool.Text,
                                                textElement.color,
                                                textElement.fontSize,
                                                1f
                                            )
                                        )
                                    }
                                }
                            }) {
                                IconEdit()
                            }
                        }
                        if (!isDrawing) {
                            IconButton(onClick = {
                                startCropRect = cropRect
                                isCropping = true
                                isDrawing = false
                                editorMode = EditorMode.None
                            }) {
                                IconCrop()
                            }
                            IconButton(onClick = {
                                pushState()
                                rotation -= 90f
                            }) {
                                IconRotateLeft()
                            }
                            IconButton(onClick = {
                                pushState()
                                rotation += 90f
                            }) {
                                IconRotateRight()
                            }
                        }
                        IconButton(onClick = {
                            isDrawing = !isDrawing
                            isCropping = false
                            editorMode = EditorMode.None
                        }) {
                            if (isDrawing) IconClose() else IconDraw()
                        }
                        Box {
                            IconButton(onClick = { showSaveMenu = true }) {
                                IconSave()
                            }
                            DropdownMenu(
                                expanded = showSaveMenu,
                                onDismissRequest = { showSaveMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_save)) },
                                    onClick = { showSaveMenu = false; doSave(false) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_save_as_copy)) },
                                    onClick = { showSaveMenu = false; doSave(true) }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                val maxWidth = constraints.maxWidth.toFloat()
                val maxHeight = constraints.maxHeight.toFloat()

                photo?.let { p ->
                    val isFlipped = (rotation / 90f).roundToInt() % 2 != 0
                    val actualWidth =
                        if (p.width > 0) p.width.toFloat() else originalBitmap?.width?.toFloat()
                            ?: 1f
                    val actualHeight =
                        if (p.height > 0) p.height.toFloat() else originalBitmap?.height?.toFloat()
                            ?: 1f
                    val photoRatio =
                        if (isFlipped) actualHeight / actualWidth else actualWidth / actualHeight

                    val displayRatio =
                        if (isCropping) photoRatio else (cropRect.width / cropRect.height) * photoRatio
                    val containerRatio = maxWidth / maxHeight

                    val (viewportWidth, viewportHeight) = if (displayRatio > containerRatio) {
                        maxWidth to (maxWidth / displayRatio)
                    } else {
                        (maxHeight * displayRatio) to maxHeight
                    }
                    currentViewportWidth = viewportWidth
                    currentViewportHeight = viewportHeight

                    val density = LocalDensity.current
                    val densityFloat = density.density
                    val viewportWidthDp = with(density) { viewportWidth.toDp() }
                    val viewportHeightDp = with(density) { viewportHeight.toDp() }

                    val isInkDrawing = isDrawing && activeTool != DrawingTool.Pointer && activeTool != DrawingTool.Text
                    Box(
                        modifier = Modifier
                            .size(viewportWidthDp, viewportHeightDp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                clip = false
                            }
                            .then(
                                if (activeTool == DrawingTool.Text) Modifier
                                    .pointerInput(activeTool) {
                                        detectTapGestures { tapOffset ->
                                            pushState()
                                            val newId = UUID.randomUUID().toString()
                                            texts.add(
                                                TextElement(
                                                    id = newId,
                                                    text = "New Text",
                                                    x = tapOffset.x / size.width,
                                                    y = tapOffset.y / size.height,
                                                    rotation = 0f,
                                                    color = penColor.toArgb(),
                                                    fontSize = textFontSize
                                                )
                                            )
                                            selectedTextId = newId
                                            selectedTextIndex = texts.size - 1
                                            textToEdit = texts.last()
                                        }
                                    }
                                else if (!isInkDrawing && activeTool != DrawingTool.Pointer) Modifier
                                    .transformable(state = transformState)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val displayBitmap = when {
                            editorMode == EditorMode.HSL || editorMode == EditorMode.Blur || editorMode == EditorMode.Selective -> previewBitmap ?: transformedBitmap
                            adjustments.hasPixelEffects() -> previewBitmap ?: transformedBitmap
                            else -> transformedBitmap
                        }
                        displayBitmap?.let { bitmap ->
                            val adjMatrix = remember(adjustments) { adjustments.toColorMatrix() }
                            val curvesMatrix = remember(curvesAdjustment) {
                                if (!curvesAdjustment.isIdentity()) curvesAdjustment.toColorMatrix() else null
                            }
                            val hasAdjustments = adjustments != ImageAdjustments()
                            val combinedMatrix = remember(adjMatrix, curvesMatrix, hasAdjustments) {
                                if (curvesMatrix != null) {
                                    val combined = android.graphics.ColorMatrix(adjMatrix.array)
                                    combined.postConcat(curvesMatrix)
                                    ColorMatrix(combined.array)
                                } else if (hasAdjustments) {
                                    ColorMatrix(adjMatrix.array)
                                } else null
                            }

                            val perspectiveMatrix = remember(perspectiveCorners) {
                                if (!perspectiveCorners.isIdentity()) {
                                    perspectiveCorners.toMatrix(1f, 1f)
                                } else null
                            }

                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (perspectiveMatrix != null) {
                                            val values = FloatArray(9)
                                            perspectiveMatrix.getValues(values)
                                            Modifier.graphicsLayer {
                                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                                val m = androidx.compose.ui.graphics.Matrix()
                                                m[0, 0] = values[0]; m[0, 1] = values[1]; m[0, 3] = values[2]
                                                m[1, 0] = values[3]; m[1, 1] = values[4]; m[1, 3] = values[5]
                                                m[3, 0] = values[6]; m[3, 1] = values[7]; m[3, 3] = values[8]
                                            }
                                        } else Modifier
                                    ),
                                colorFilter = combinedMatrix?.let { ColorFilter.colorMatrix(it) },
                            )
                        }

                        val canvasTextElements = remember(texts.toList(), currentViewportWidth, currentViewportHeight) {
                            texts.map { te ->
                                CanvasTextElement(
                                    text = te.text,
                                    x = te.x * currentViewportWidth,
                                    y = te.y * currentViewportHeight,
                                    rotation = te.rotation,
                                    color = te.color,
                                    fontSize = te.fontSize,
                                )
                            }
                        }

                        InkCanvasView(
                            currentBrush = currentBrush,
                            finishedStrokes = inkStrokes.toList(),
                            onStrokeFinished = { stroke ->
                                pushState()
                                inkStrokes.add(stroke)
                            },
                            onStrokeErased = { stroke ->
                                pushState()
                                inkStrokes.remove(stroke)
                            },
                            eraserMode = activeTool == DrawingTool.Eraser,
                            enabled = isDrawing && activeTool != DrawingTool.Pointer && activeTool != DrawingTool.Text,
                            textElements = canvasTextElements,
                            selectedStrokeIndex = selectedStrokeIndex,
                            selectedTextIndex = selectedTextIndex,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (isDrawing && activeTool == DrawingTool.Pointer) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(
                                        inkStrokes.toList(),
                                        texts.toList(),
                                        selectedStrokeIndex,
                                        selectedTextIndex,
                                        currentViewportWidth,
                                        currentViewportHeight
                                    ) {
                                        detectTapGestures(
                                            onDoubleTap = { tapOffset ->
                                                val textIdx = hitTestText(
                                                    tapOffset.x,
                                                    tapOffset.y,
                                                    texts,
                                                    currentViewportWidth,
                                                    currentViewportHeight,
                                                    densityFloat
                                                )
                                                if (textIdx != null) {
                                                    pushState()
                                                    textToEdit = texts.getOrNull(textIdx)
                                                }
                                            },
                                            onTap = { tapOffset ->
                                                val textIdx = hitTestText(
                                                    tapOffset.x,
                                                    tapOffset.y,
                                                    texts,
                                                    currentViewportWidth,
                                                    currentViewportHeight,
                                                    densityFloat
                                                )
                                                if (textIdx != null) {
                                                    selectedTextIndex = textIdx
                                                    selectedStrokeIndex = null
                                                    selectedTextId = texts.getOrNull(textIdx)?.id
                                                } else {
                                                    val strokeIdx = hitTestStroke(
                                                        tapOffset.x,
                                                        tapOffset.y,
                                                        inkStrokes
                                                    )
                                                    if (strokeIdx != null) {
                                                        selectedStrokeIndex = strokeIdx
                                                        selectedTextIndex = null
                                                        selectedTextId = null
                                                    } else {
                                                        selectedStrokeIndex = null
                                                        selectedTextIndex = null
                                                        selectedTextId = null
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    .pointerInput(
                                        selectedStrokeIndex,
                                        selectedTextIndex,
                                        currentViewportWidth,
                                        currentViewportHeight
                                    ) {
                                        detectDragGestures(
                                            onDragStart = {
                                                if (selectedStrokeIndex != null || selectedTextIndex != null) {
                                                    pushState()
                                                }
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                selectedStrokeIndex?.let { idx ->
                                                    if (idx in inkStrokes.indices) {
                                                        inkStrokes[idx] = inkStrokes[idx].translate(
                                                            dragAmount.x,
                                                            dragAmount.y
                                                        )
                                                    }
                                                }
                                                selectedTextIndex?.let { idx ->
                                                    if (idx in texts.indices) {
                                                        val current = texts[idx]
                                                        texts[idx] = current.copy(
                                                            x = current.x + dragAmount.x / currentViewportWidth,
                                                            y = current.y + dragAmount.y / currentViewportHeight
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    }
                            )
                        }

                        if (editorMode == EditorMode.Blur && !blurParams.isIdentity()) {
                            BlurOverlay(
                                blurParams = blurParams,
                                onBlurChanged = {
                                    pushState()
                                    photoEditViewModel.updateBlur(it)
                                },
                            )
                        }

                        if (editorMode == EditorMode.Selective) {
                            MaskOverlay(
                                mask = currentSelectiveMask,
                                showMask = showSelectiveMask,
                                onMaskChanged = { currentSelectiveMask = it },
                            )
                        }

                        if (editorMode == EditorMode.Healing) {
                            HealingOverlay(
                                sourceX = healingSourceX,
                                sourceY = healingSourceY,
                                brushSize = healingBrushSize,
                                isSettingSource = isSettingHealingSource,
                                onSourceSet = { x, y ->
                                    healingSourceX = x
                                    healingSourceY = y
                                    isSettingHealingSource = false
                                },
                                onPaint = { x, y ->
                                    if (healingSourceX != null && healingSourceY != null) {
                                        currentHealingPoints = currentHealingPoints + (x to y)
                                    }
                                },
                            )
                        }

                        if (editorMode == EditorMode.Perspective && !perspectiveCorners.isIdentity()) {
                            val corners = perspectiveCorners
                            Handle(offset = Offset(corners.topLeft.first * viewportWidth, corners.topLeft.second * viewportHeight), onDrag = { delta ->
                                pushState()
                                photoEditViewModel.updatePerspective(corners.copy(topLeft = (corners.topLeft.first + delta.x / viewportWidth).coerceIn(0f, 1f) to (corners.topLeft.second + delta.y / viewportHeight).coerceIn(0f, 1f)))
                            })
                            Handle(offset = Offset(corners.topRight.first * viewportWidth, corners.topRight.second * viewportHeight), onDrag = { delta ->
                                pushState()
                                photoEditViewModel.updatePerspective(corners.copy(topRight = (corners.topRight.first + delta.x / viewportWidth).coerceIn(0f, 1f) to (corners.topRight.second + delta.y / viewportHeight).coerceIn(0f, 1f)))
                            })
                            Handle(offset = Offset(corners.bottomLeft.first * viewportWidth, corners.bottomLeft.second * viewportHeight), onDrag = { delta ->
                                pushState()
                                photoEditViewModel.updatePerspective(corners.copy(bottomLeft = (corners.bottomLeft.first + delta.x / viewportWidth).coerceIn(0f, 1f) to (corners.bottomLeft.second + delta.y / viewportHeight).coerceIn(0f, 1f)))
                            })
                            Handle(offset = Offset(corners.bottomRight.first * viewportWidth, corners.bottomRight.second * viewportHeight), onDrag = { delta ->
                                pushState()
                                photoEditViewModel.updatePerspective(corners.copy(bottomRight = (corners.bottomRight.first + delta.x / viewportWidth).coerceIn(0f, 1f) to (corners.bottomRight.second + delta.y / viewportHeight).coerceIn(0f, 1f)))
                            })
                        }

                        if (isCropping) {
                            CropOverlay(
                                cropRect = cropRect,
                                onCropRectChange = { cropRect = it }
                            )
                        }
                    }
                }
            }

            if (!isDrawing && !isCropping) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    data class ToolButton(val mode: EditorMode, val label: String)
                    val tools = listOf(
                        ToolButton(EditorMode.Adjust, "Adjust"),
                        ToolButton(EditorMode.Filters, "Filters"),
                        ToolButton(EditorMode.Curves, "Curves"),
                        ToolButton(EditorMode.HSL, "HSL"),
                        ToolButton(EditorMode.Blur, "Blur"),
                        ToolButton(EditorMode.Selective, "Selective"),
                        ToolButton(EditorMode.Healing, "Heal"),
                        ToolButton(EditorMode.Perspective, "Perspective"),
                    )
                    tools.forEach { tool ->
                        Surface(
                            modifier = Modifier
                                .clickable {
                                    editorMode = if (editorMode == tool.mode) EditorMode.None else tool.mode
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (editorMode == tool.mode) MaterialTheme.colorScheme.primaryContainer
                                   else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                tool.label,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            if (editorMode == EditorMode.Adjust && !isDrawing && !isCropping) {
                AdjustmentPanel(
                    adjustments = adjustments,
                    selectedAdjustment = selectedAdjustment,
                    onSelectAdjustment = { selectedAdjustment = it },
                    onUpdateAdjustment = { update ->
                        pushState()
                        photoEditViewModel.updateAdjustment(update)
                        photoEditViewModel.applyFilter(null)
                    },
                    onReset = {
                        pushState()
                        photoEditViewModel.resetAdjustments()
                    },
                )
            }

            if (editorMode == EditorMode.Filters && !isDrawing && !isCropping) {
                FilterPanel(
                    bitmap = transformedBitmap,
                    adjustments = adjustments,
                    selectedFilter = selectedFilter,
                    onSelectFilter = { filter ->
                        pushState()
                        photoEditViewModel.applyFilter(filter)
                    },
                )
            }

            if (editorMode == EditorMode.Curves && !isDrawing && !isCropping) {
                CurvesPanel(
                    curves = curvesAdjustment,
                    selectedChannel = selectedCurveChannel,
                    onChannelSelected = { selectedCurveChannel = it },
                    onCurvesChanged = {
                        pushState()
                        photoEditViewModel.updateCurves(it)
                    },
                )
            }

            if (editorMode == EditorMode.HSL && !isDrawing && !isCropping) {
                HslPanel(
                    hsl = hslAdjustments,
                    selectedRange = selectedHslRange,
                    onRangeSelected = { selectedHslRange = it },
                    onHslChanged = {
                        pushState()
                        photoEditViewModel.updateHsl(it)
                    },
                )
            }

            if (editorMode == EditorMode.Blur && !isDrawing && !isCropping) {
                BlurPanel(
                    blurParams = blurParams,
                    onBlurChanged = {
                        pushState()
                        photoEditViewModel.updateBlur(it)
                    },
                )
            }

            if (editorMode == EditorMode.Selective && !isDrawing && !isCropping) {
                SelectiveEditPanel(
                    mask = currentSelectiveMask,
                    showMask = showSelectiveMask,
                    onMaskChanged = { currentSelectiveMask = it },
                    onShowMaskChanged = { showSelectiveMask = it },
                    onAddMask = {
                        pushState()
                        photoEditViewModel.updateSelective(
                            selectiveEdits.copy(masks = selectiveEdits.masks + currentSelectiveMask)
                        )
                        currentSelectiveMask = SelectiveMask()
                    },
                )
            }

            if (editorMode == EditorMode.Healing && !isDrawing && !isCropping) {
                HealingPanel(
                    brushSize = healingBrushSize,
                    isSettingSource = isSettingHealingSource,
                    onBrushSizeChanged = { healingBrushSize = it },
                    onSetSourceToggled = { isSettingHealingSource = it },
                )
            }

            if (editorMode == EditorMode.Perspective && !isDrawing && !isCropping) {
                PerspectivePanel(
                    onApply = {
                        // Perspective is already live via graphicsLayer; this is a no-op confirmation
                    },
                    onReset = {
                        pushState()
                        photoEditViewModel.resetPerspective()
                    },
                )
            }

            // Commit healing stroke when dragging ends
            LaunchedEffect(currentHealingPoints.size) {
                if (currentHealingPoints.isNotEmpty() && editorMode == EditorMode.Healing) {
                    kotlinx.coroutines.delay(300)
                    if (healingSourceX != null && healingSourceY != null && currentHealingPoints.isNotEmpty()) {
                        pushState()
                        val stroke = HealingStroke(
                            sourceX = healingSourceX!!,
                            sourceY = healingSourceY!!,
                            points = currentHealingPoints,
                            brushSize = healingBrushSize,
                        )
                        photoEditViewModel.updateHealing(
                            healingStrokes.copy(strokes = healingStrokes.strokes + stroke)
                        )
                        currentHealingPoints = emptyList()
                    }
                }
            }

            if (isDrawing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        activeTool = DrawingTool.Pointer
                        selectedTextId = null
                        selectedStrokeIndex = null
                        selectedTextIndex = null
                    }) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .then(
                                    if (activeTool == DrawingTool.Pointer) Modifier.background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        CircleShape
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconVisible()
                        }
                    }
                    IconButton(onClick = {
                        if (activeTool == DrawingTool.Pen) {
                            backStack.add(
                                EditRoute.DrawingSettings(
                                    DrawingTool.Pen,
                                    penColor.toArgb(),
                                    penSize,
                                    1f
                                )
                            )
                        } else {
                        activeTool = DrawingTool.Pen
                            selectedTextId = null
                            selectedStrokeIndex = null
                            selectedTextIndex = null
                        }
                    }) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .then(
                                    if (activeTool == DrawingTool.Pen) Modifier.background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        CircleShape
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconDraw()
                        }
                    }
                    IconButton(onClick = {
                        if (activeTool == DrawingTool.Highlighter) {
                            backStack.add(
                                EditRoute.DrawingSettings(
                                    DrawingTool.Highlighter,
                                    highlighterColor.toArgb(),
                                    highlighterSize,
                                    highlighterOpacity
                                )
                            )
                        } else {
                            activeTool = DrawingTool.Highlighter
                            selectedTextId = null
                            selectedStrokeIndex = null
                            selectedTextIndex = null
                        }
                    }) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .then(
                                    if (activeTool == DrawingTool.Highlighter) Modifier.background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        CircleShape
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconBrush()
                        }
                    }
                    IconButton(onClick = {
                        activeTool = DrawingTool.Eraser
                        selectedTextId = null
                        selectedStrokeIndex = null
                        selectedTextIndex = null
                    }) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .then(
                                    if (activeTool == DrawingTool.Eraser) Modifier.background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        CircleShape
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconEraser()
                        }
                    }
                    IconButton(onClick = {
                        if (selectedTextId != null || activeTool == DrawingTool.Text) {
                            if (selectedTextId != null) {
                                texts.find { it.id == selectedTextId }?.let { textElement ->
                                    backStack.add(
                                        EditRoute.DrawingSettings(
                                            DrawingTool.Text,
                                            textElement.color,
                                            textElement.fontSize,
                                            1f
                                        )
                                    )
                                }
                            } else {
                                backStack.add(
                                    EditRoute.DrawingSettings(
                                        DrawingTool.Text,
                                        penColor.toArgb(),
                                        textFontSize,
                                        1f
                                    )
                                )
                            }
                        } else {
                            activeTool = DrawingTool.Text
                            selectedTextId = null
                            selectedStrokeIndex = null
                            selectedTextIndex = null
                        }
                    }) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .then(
                                    if (activeTool == DrawingTool.Text) Modifier.background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        CircleShape
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "T",
                                fontSize = 24.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
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
                            if (index != -1) {
                                texts[index] = texts[index].copy(text = newText)
                            }
                        },
                        textStyle = TextStyle(fontSize = 18.sp),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Edit Text") }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = { textToEdit = null }) {
                            IconCheck()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CropOverlay(cropRect: Rect, onCropRectChange: (Rect) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = Rect(
                cropRect.left * width,
                cropRect.top * height,
                cropRect.right * width,
                cropRect.bottom * height
            )
            val path = Path().apply {
                addRect(Rect(0f, 0f, width, height))
                addRect(rect)
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, Color.Black.copy(alpha = 0.5f))
            drawRect(
                color = Color.White,
                topLeft = Offset(rect.left, rect.top),
                size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                style = Stroke(width = 2.dp.toPx())
            )
        }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (cropRect.left * width).roundToInt(),
                        (cropRect.top * height).roundToInt()
                    )
                }
                .size(
                    width = with(LocalDensity.current) { (cropRect.width * width).toDp() },
                    height = with(LocalDensity.current) { (cropRect.height * height).toDp() })
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x / width
                        val dy = dragAmount.y / height
                        val newLeft = (cropRect.left + dx).coerceIn(0f, 1f - cropRect.width)
                        val newTop = (cropRect.top + dy).coerceIn(0f, 1f - cropRect.height)
                        onCropRectChange(
                            Rect(
                                left = newLeft,
                                top = newTop,
                                right = newLeft + cropRect.width,
                                bottom = newTop + cropRect.height
                            )
                        )
                    }
                }
        )
        Handle(offset = Offset(cropRect.left * width, cropRect.top * height), onDrag = { delta ->
            val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.05f)
            val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.05f)
            onCropRectChange(cropRect.copy(left = newLeft, top = newTop))
        })
        Handle(offset = Offset(cropRect.right * width, cropRect.top * height), onDrag = { delta ->
            val newRight = (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.05f, 1f)
            val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.05f)
            onCropRectChange(cropRect.copy(right = newRight, top = newTop))
        })
        Handle(offset = Offset(cropRect.left * width, cropRect.bottom * height), onDrag = { delta ->
            val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.05f)
            val newBottom = (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.05f, 1f)
            onCropRectChange(cropRect.copy(left = newLeft, bottom = newBottom))
        })
        Handle(
            offset = Offset(cropRect.right * width, cropRect.bottom * height),
            onDrag = { delta ->
                val newRight =
                    (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.05f, 1f)
                val newBottom =
                    (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.05f, 1f)
                onCropRectChange(cropRect.copy(right = newRight, bottom = newBottom))
            })
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
            .offset {
                IntOffset(
                    (offset.x - handleRadiusPx).roundToInt(),
                    (offset.y - handleRadiusPx).roundToInt()
                )
            }
            .size(handleSize)
            .background(Color.White, CircleShape)
            .border(1.dp, Color.Black, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume(); currentOnDrag(
                    dragAmount
                )
                }
            }
    )
}

@Composable
private fun AdjustmentPanel(
    adjustments: ImageAdjustments,
    selectedAdjustment: AdjustmentType,
    onSelectAdjustment: (AdjustmentType) -> Unit,
    onUpdateAdjustment: ((ImageAdjustments) -> ImageAdjustments) -> Unit,
    onReset: () -> Unit,
) {
    fun getValue(type: AdjustmentType): Float = when (type) {
        AdjustmentType.Brightness -> adjustments.brightness
        AdjustmentType.Contrast -> adjustments.contrast
        AdjustmentType.Saturation -> adjustments.saturation
        AdjustmentType.Warmth -> adjustments.warmth
        AdjustmentType.Exposure -> adjustments.exposure
        AdjustmentType.Highlights -> adjustments.highlights
        AdjustmentType.Shadows -> adjustments.shadows
        AdjustmentType.Sharpness -> adjustments.sharpness
        AdjustmentType.Vignette -> adjustments.vignette
        AdjustmentType.Grain -> adjustments.grain
        AdjustmentType.Fade -> adjustments.fade
        AdjustmentType.Tint -> adjustments.tint
    }

    fun withValue(type: AdjustmentType, value: Float): (ImageAdjustments) -> ImageAdjustments = { adj ->
        when (type) {
            AdjustmentType.Brightness -> adj.copy(brightness = value)
            AdjustmentType.Contrast -> adj.copy(contrast = value)
            AdjustmentType.Saturation -> adj.copy(saturation = value)
            AdjustmentType.Warmth -> adj.copy(warmth = value)
            AdjustmentType.Exposure -> adj.copy(exposure = value)
            AdjustmentType.Highlights -> adj.copy(highlights = value)
            AdjustmentType.Shadows -> adj.copy(shadows = value)
            AdjustmentType.Sharpness -> adj.copy(sharpness = value)
            AdjustmentType.Vignette -> adj.copy(vignette = value)
            AdjustmentType.Grain -> adj.copy(grain = value)
            AdjustmentType.Fade -> adj.copy(fade = value)
            AdjustmentType.Tint -> adj.copy(tint = value)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdjustmentType.entries.forEach { type ->
                val value = getValue(type)
                FilterChip(
                    selected = selectedAdjustment == type,
                    onClick = { onSelectAdjustment(type) },
                    label = {
                        Text(
                            if (value != 0f) "${type.label} ${value.roundToInt()}" else type.label,
                            fontSize = 12.sp,
                        )
                    },
                )
            }
        }

        val currentValue = getValue(selectedAdjustment)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selectedAdjustment.label,
                fontSize = 12.sp,
                modifier = Modifier.width(72.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = currentValue,
                onValueChange = { newValue ->
                    onUpdateAdjustment(withValue(selectedAdjustment, newValue))
                },
                valueRange = selectedAdjustment.min..selectedAdjustment.max,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${currentValue.roundToInt()}",
                fontSize = 12.sp,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (adjustments != ImageAdjustments()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .clickable { onReset() }
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        "Reset All",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPanel(
    bitmap: android.graphics.Bitmap?,
    adjustments: ImageAdjustments,
    selectedFilter: PhotoFilter?,
    onSelectFilter: (PhotoFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoFilters.all.forEach { filter ->
            val isSelected = if (filter.adjustments == ImageAdjustments()) {
                selectedFilter == null && adjustments == ImageAdjustments()
            } else {
                selectedFilter?.name == filter.name
            }

            Column(
                modifier = Modifier
                    .width(72.dp)
                    .clickable { onSelectFilter(filter) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                bitmap?.let { bmp ->
                    val filterMatrix = remember(filter) { filter.adjustments.toColorMatrix() }
                    val hasFilter = filter.adjustments != ImageAdjustments()
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (isSelected) Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(8.dp)
                                ) else Modifier
                            ),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = filter.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            colorFilter = if (hasFilter) {
                                ColorFilter.colorMatrix(ColorMatrix(filterMatrix.array))
                            } else null,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    filter.name,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun hitTestText(
    x: Float,
    y: Float,
    texts: List<TextElement>,
    viewportWidth: Float,
    viewportHeight: Float,
    density: Float,
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
            if (box.xMin <= x + hitRadius && box.xMax >= x - hitRadius &&
                box.yMin <= y + hitRadius && box.yMax >= y - hitRadius
            ) return i
        }
    }
    return null
}
