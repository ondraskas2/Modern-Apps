package com.vayunmathur.photos.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import com.vayunmathur.photos.data.Drawing
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.toSerializable
import com.vayunmathur.library.util.ResultEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.IconBrush
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconEraser
import com.vayunmathur.library.ui.IconNavigation
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

fun Drawing.computeBoundingBox(width: Float, height: Float): Rect {
    if (points.isEmpty()) return Rect.Zero
    var minX = points[0].x
    var maxX = points[0].x
    var minY = points[0].y
    var maxY = points[0].y
    points.forEach { p ->
        minX = minOf(minX, p.x)
        maxX = maxOf(maxX, p.x)
        minY = minOf(minY, p.y)
        maxY = maxOf(maxY, p.y)
    }
    val padding = 0.02f
    return Rect(
        (minX - padding) * width,
        (minY - padding) * height,
        (maxX + padding) * width,
        (maxY + padding) * height
    )
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
    val scope = rememberCoroutineScope()

    var isCropping by remember { mutableStateOf(false) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var cropRect by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var startCropRect by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var showSaveMenu by remember { mutableStateOf(false) }

    var isDrawing by remember { mutableStateOf(false) }
    val currentDrawingPoints = remember { mutableStateListOf<Offset>() }
    val drawings = remember { mutableStateListOf<Drawing>() }

    var activeTool by remember { mutableStateOf(DrawingTool.Pointer) }

    var penColor by remember { mutableStateOf(Color.Red) }
    var penWidth by remember { mutableFloatStateOf(10f) }

    var highlighterColor by remember { mutableStateOf(Color.Yellow) }
    var highlighterWidth by remember { mutableFloatStateOf(40f) }
    var highlighterOpacity by remember { mutableFloatStateOf(0.5f) }

    var eraserWidth by remember { mutableFloatStateOf(30f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        if (activeTool == DrawingTool.Pointer) {
            scale *= zoomChange
            offset += offsetChange
        }
    }

    val texts = remember { mutableStateListOf<TextElement>() }
    var selectedDrawingId by remember { mutableStateOf<String?>(null) }
    var selectedTextId by remember { mutableStateOf<String?>(null) }
    var textToEdit by remember { mutableStateOf<TextElement?>(null) }
    var textFontSize by remember { mutableFloatStateOf(40f) }
    var currentViewportWidth by remember { mutableFloatStateOf(1f) }
    var currentViewportHeight by remember { mutableFloatStateOf(1f) }

    data class EditState(
        val rotation: Float,
        val cropRect: Rect,
        val drawings: List<Drawing>,
        val texts: List<TextElement>
    )

    val history = remember { mutableStateListOf<EditState>() }

    fun pushState() {
        history.add(EditState(rotation, cropRect, drawings.toList(), texts.toList()))
    }

    fun undo() {
        if (history.isNotEmpty()) {
            val lastState = history.removeAt(history.size - 1)
            rotation = lastState.rotation
            cropRect = lastState.cropRect
            drawings.clear()
            drawings.addAll(lastState.drawings)
            texts.clear()
            texts.addAll(lastState.texts)
        }
    }

    val currentStrokeColor = when (activeTool) {
        DrawingTool.Pen -> penColor
        DrawingTool.Highlighter -> highlighterColor
        DrawingTool.Eraser -> Color.Transparent
        DrawingTool.Text -> penColor
        DrawingTool.Pointer -> Color.Transparent
    }

    val currentStrokeWidth = when (activeTool) {
        DrawingTool.Pen -> penWidth
        DrawingTool.Highlighter -> highlighterWidth
        DrawingTool.Eraser -> eraserWidth
        DrawingTool.Text -> 0f
        DrawingTool.Pointer -> 0f
    }

    val currentStrokeOpacity = if (activeTool == DrawingTool.Highlighter) highlighterOpacity else 1f

    ResultEffect<DrawingSettingsResult>("drawing_settings") { result ->
        var changed = false
        selectedDrawingId?.let { id ->
            val index = drawings.indexOfFirst { it.id == id }
            if (index != -1) {
                pushState()
                drawings[index] = drawings[index].copy(
                    tool = result.tool,
                    color = result.color,
                    strokeWidth = result.thickness,
                    opacity = result.opacity
                )
                changed = true
            }
        }

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
                    penWidth = result.thickness
                }

                DrawingTool.Highlighter -> {
                    highlighterColor = Color(result.color)
                    highlighterWidth = result.thickness
                    highlighterOpacity = result.opacity
                }

                DrawingTool.Eraser -> {
                    eraserWidth = result.thickness
                }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_edit_photo)) },
                navigationIcon = {
                    IconNavigation(backStack)
                },
                actions = {
                    if (isCropping) {
                        IconButton(onClick = {
                            if (cropRect != startCropRect) {
                                history.add(
                                    EditState(
                                        rotation,
                                        startCropRect,
                                        drawings.toList(),
                                        texts.toList()
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
                        if (selectedDrawingId != null || selectedTextId != null) {
                            IconButton(onClick = {
                                selectedDrawingId?.let { id ->
                                    drawings.find { it.id == id }?.let { drawing ->
                                        backStack.add(
                                            EditRoute.DrawingSettings(
                                                drawing.tool,
                                                drawing.color,
                                                drawing.strokeWidth,
                                                drawing.opacity
                                            )
                                        )
                                    }
                                }
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
                                    onClick = {
                                        showSaveMenu = false
                                        photo?.let {
                                            photoEditViewModel.savePhoto(
                                                it,
                                                rotation,
                                                cropRect,
                                                drawings.toList(),
                                                texts.toList(),
                                                currentViewportWidth,
                                                false,
                                            ) { context.finish() }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_save_as_copy)) },
                                    onClick = {
                                        showSaveMenu = false
                                        photo?.let {
                                            photoEditViewModel.savePhoto(
                                                it,
                                                rotation,
                                                cropRect,
                                                drawings.toList(),
                                                texts.toList(),
                                                currentViewportWidth,
                                                true,
                                            ) { context.finish() }
                                        }
                                    }
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
                    .padding(32.dp),
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
                    val viewportWidthDp = with(density) { viewportWidth.toDp() }
                    val viewportHeightDp = with(density) { viewportHeight.toDp() }

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
                            .transformable(state = transformState)
                            .pointerInput(activeTool) {
                                detectTapGestures { tapOffset ->
                                    if (activeTool == DrawingTool.Pointer) {
                                        selectedTextId = null
                                        selectedDrawingId = null
                                    } else if (activeTool == DrawingTool.Text) {
                                        pushState()
                                        val id = UUID.randomUUID().toString()
                                        texts.add(
                                            TextElement(
                                                id = id,
                                                text = "New Text",
                                                x = tapOffset.x / size.width,
                                                y = tapOffset.y / size.height,
                                                rotation = 0f,
                                                color = penColor.toArgb(),
                                                fontSize = textFontSize
                                            )
                                        )
                                        selectedTextId = id
                                        selectedDrawingId = null
                                        textToEdit = texts.last()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        transformedBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (isDrawing) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(activeTool) {
                                        if (activeTool == DrawingTool.Pointer) {
                                            detectTapGestures { tapOffset ->
                                                val hitDrawing = drawings.findLast { drawing ->
                                                    val bounds = drawing.computeBoundingBox(
                                                        size.width.toFloat(),
                                                        size.height.toFloat()
                                                    )
                                                    bounds.contains(tapOffset)
                                                }
                                                if (hitDrawing != null) {
                                                    selectedDrawingId = hitDrawing.id
                                                    selectedTextId = null
                                                }
                                            }
                                        }
                                    }
                                    .then(
                                        if (activeTool == DrawingTool.Pen || activeTool == DrawingTool.Highlighter || activeTool == DrawingTool.Eraser) {
                                            Modifier.pointerInput(
                                                activeTool,
                                                currentStrokeColor,
                                                currentStrokeWidth,
                                                currentStrokeOpacity
                                            ) {
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        currentDrawingPoints.add(
                                                            Offset(
                                                                offset.x / size.width,
                                                                offset.y / size.height
                                                            )
                                                        )
                                                    },
                                                    onDragEnd = {
                                                        if (currentDrawingPoints.isNotEmpty()) {
                                                            pushState()
                                                            val id = UUID.randomUUID().toString()
                                                            drawings.add(
                                                                Drawing(
                                                                    id = id,
                                                                    points = currentDrawingPoints.map { it.toSerializable() },
                                                                    tool = activeTool,
                                                                    color = currentStrokeColor.toArgb(),
                                                                    strokeWidth = currentStrokeWidth,
                                                                    opacity = currentStrokeOpacity
                                                                )
                                                            )
                                                            selectedDrawingId = id
                                                            selectedTextId = null
                                                            currentDrawingPoints.clear()
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        currentDrawingPoints.clear()
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        val last = currentDrawingPoints.lastOrNull()
                                                            ?: Offset.Zero
                                                        currentDrawingPoints.add(
                                                            last + Offset(
                                                                dragAmount.x / size.width,
                                                                dragAmount.y / size.height
                                                            )
                                                        )
                                                    }
                                                )
                                            }
                                        } else if (activeTool == DrawingTool.Pointer) {
                                            Modifier.pointerInput(selectedDrawingId) {
                                                detectDragGestures(
                                                    onDragStart = { if (selectedDrawingId != null) pushState() },
                                                    onDrag = { change, dragAmount ->
                                                        selectedDrawingId?.let { id ->
                                                            val index =
                                                                drawings.indexOfFirst { it.id == id }
                                                            if (index != -1) {
                                                                change.consume()
                                                                val d = drawings[index]
                                                                drawings[index] = d.copy(
                                                                    points = d.points.map {
                                                                        it.copy(
                                                                            x = it.x + dragAmount.x / size.width,
                                                                            y = it.y + dragAmount.y / size.height
                                                                        )
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        } else Modifier
                                    )
                                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            ) {
                                drawIntoCanvas {
                                    drawings.forEach { drawing ->
                                        val path = Path().apply {
                                            drawing.points.firstOrNull()?.let { first ->
                                                moveTo(first.x * size.width, first.y * size.height)
                                                drawing.points.drop(1).forEach { next ->
                                                    lineTo(
                                                        next.x * size.width,
                                                        next.y * size.height
                                                    )
                                                }
                                            }
                                        }

                                        drawPath(
                                            path = path,
                                            color = Color(drawing.color),
                                            alpha = drawing.opacity,
                                            style = Stroke(
                                                width = drawing.strokeWidth,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            ),
                                            blendMode = if (drawing.tool == DrawingTool.Eraser) BlendMode.Clear else BlendMode.SrcOver
                                        )

                                        if (selectedDrawingId == drawing.id && activeTool == DrawingTool.Pointer) {
                                            val bounds =
                                                drawing.computeBoundingBox(size.width, size.height)
                                            drawRect(
                                                color = Color.White,
                                                topLeft = Offset(
                                                    bounds.left - 1.dp.toPx(),
                                                    bounds.top - 1.dp.toPx()
                                                ),
                                                size = androidx.compose.ui.geometry.Size(
                                                    bounds.width + 2.dp.toPx(),
                                                    bounds.height + 2.dp.toPx()
                                                ),
                                                style = Stroke(width = 1.dp.toPx())
                                            )
                                            drawRect(
                                                color = Color.Black,
                                                topLeft = Offset(bounds.left, bounds.top),
                                                size = androidx.compose.ui.geometry.Size(
                                                    bounds.width,
                                                    bounds.height
                                                ),
                                                style = Stroke(width = 1.dp.toPx())
                                            )
                                        }
                                    }

                                    if (currentDrawingPoints.isNotEmpty()) {
                                        val path = Path().apply {
                                            moveTo(
                                                currentDrawingPoints.first().x * size.width,
                                                currentDrawingPoints.first().y * size.height
                                            )
                                            currentDrawingPoints.drop(1).forEach { next ->
                                                lineTo(next.x * size.width, next.y * size.height)
                                            }
                                        }
                                        drawPath(
                                            path = path,
                                            color = currentStrokeColor,
                                            alpha = currentStrokeOpacity,
                                            style = Stroke(
                                                width = currentStrokeWidth,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            ),
                                            blendMode = if (activeTool == DrawingTool.Eraser) BlendMode.Clear else BlendMode.SrcOver
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            texts.forEach { textElement ->
                                key(textElement.id) {
                                    val isSelected = selectedTextId == textElement.id
                                    Box(
                                        modifier = Modifier
                                            .offset {
                                                IntOffset(
                                                    (textElement.x * currentViewportWidth).roundToInt(),
                                                    (textElement.y * currentViewportHeight).roundToInt()
                                                )
                                            }
                                            .rotate(textElement.rotation)
                                            .then(
                                                if (isSelected && activeTool == DrawingTool.Pointer) {
                                                    Modifier
                                                        .border(1.dp, Color.White)
                                                        .padding(1.dp)
                                                        .border(1.dp, Color.Black)
                                                } else Modifier
                                            )
                                            .pointerInput(activeTool) {
                                                if (activeTool == DrawingTool.Pointer) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            if (selectedTextId == textElement.id) {
                                                                pushState()
                                                                textToEdit =
                                                                    texts.find { it.id == textElement.id }
                                                            } else {
                                                                selectedTextId = textElement.id
                                                                selectedDrawingId = null
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                            .pointerInput(activeTool) {
                                                if (activeTool == DrawingTool.Pointer) {
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            if (selectedTextId == textElement.id) {
                                                                pushState()
                                                            }
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            if (selectedTextId == textElement.id) {
                                                                change.consume()
                                                                val idx =
                                                                    texts.indexOfFirst { it.id == textElement.id }
                                                                if (idx != -1) {
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
                                            }
                                            .padding(4.dp)
                                    ) {
                                        Text(
                                            text = textElement.text,
                                            color = Color(textElement.color),
                                            fontSize = textElement.fontSize.sp
                                        )

                                        if (isSelected && activeTool == DrawingTool.Pointer) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopCenter)
                                                    .offset(y = (-30).dp)
                                                    .size(20.dp)
                                                    .background(Color.White, CircleShape)
                                                    .border(1.dp, Color.Black, CircleShape)
                                                    .pointerInput(activeTool) {
                                                        detectDragGestures(
                                                            onDragStart = { pushState() },
                                                            onDrag = { change, dragAmount ->
                                                                if (selectedTextId == textElement.id) {
                                                                    change.consume()
                                                                    val idx =
                                                                        texts.indexOfFirst { it.id == textElement.id }
                                                                    if (idx != -1) {
                                                                        val current = texts[idx]
                                                                        texts[idx] = current.copy(
                                                                            rotation = current.rotation + dragAmount.x
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
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
                        selectedDrawingId = null
                        selectedTextId = null
                    }) {
                        IconVisible(tint = if (activeTool == DrawingTool.Pointer) Color.Yellow else Color.White)
                    }
                    IconButton(onClick = {
                        val isSelectedMatch =
                            selectedDrawingId?.let { id -> drawings.find { it.id == id }?.tool == DrawingTool.Pen }
                                ?: false
                        if (isSelectedMatch || activeTool == DrawingTool.Pen) {
                            val drawing = drawings.find { it.id == selectedDrawingId }
                            backStack.add(
                                EditRoute.DrawingSettings(
                                    DrawingTool.Pen,
                                    drawing?.color ?: penColor.toArgb(),
                                    drawing?.strokeWidth ?: penWidth,
                                    1f
                                )
                            )
                        } else {
                            activeTool = DrawingTool.Pen
                            selectedDrawingId = null
                            selectedTextId = null
                        }
                    }) {
                        IconDraw(tint = if (activeTool == DrawingTool.Pen) penColor else Color.White)
                    }
                    IconButton(onClick = {
                        val isSelectedMatch =
                            selectedDrawingId?.let { id -> drawings.find { it.id == id }?.tool == DrawingTool.Highlighter }
                                ?: false
                        if (isSelectedMatch || activeTool == DrawingTool.Highlighter) {
                            val drawing = drawings.find { it.id == selectedDrawingId }
                            backStack.add(
                                EditRoute.DrawingSettings(
                                    DrawingTool.Highlighter,
                                    drawing?.color ?: highlighterColor.toArgb(),
                                    drawing?.strokeWidth ?: highlighterWidth,
                                    drawing?.opacity ?: highlighterOpacity
                                )
                            )
                        } else {
                            activeTool = DrawingTool.Highlighter
                            selectedDrawingId = null
                            selectedTextId = null
                        }
                    }) {
                        IconBrush(tint = if (activeTool == DrawingTool.Highlighter) highlighterColor else Color.White)
                    }
                    IconButton(onClick = {
                        val isSelectedMatch =
                            selectedDrawingId?.let { id -> drawings.find { it.id == id }?.tool == DrawingTool.Eraser }
                                ?: false
                        if (isSelectedMatch || activeTool == DrawingTool.Eraser) {
                            val drawing = drawings.find { it.id == selectedDrawingId }
                            backStack.add(
                                EditRoute.DrawingSettings(
                                    DrawingTool.Eraser,
                                    Color.Transparent.toArgb(),
                                    drawing?.strokeWidth ?: eraserWidth,
                                    1f
                                )
                            )
                        } else {
                            activeTool = DrawingTool.Eraser
                            selectedDrawingId = null
                            selectedTextId = null
                        }
                    }) {
                        IconEraser(tint = if (activeTool == DrawingTool.Eraser) Color.Yellow else Color.White)
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
                            selectedDrawingId = null
                            selectedTextId = null
                        }
                    }) {
                        Text(
                            "T",
                            color = if (activeTool == DrawingTool.Text) penColor else Color.White,
                            fontSize = 24.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
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
        Canvas(modifier = Modifier.fillMaxSize()) {
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