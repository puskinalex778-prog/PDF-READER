package com.example.simplepdfreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialUri = if (intent?.action == Intent.ACTION_VIEW) intent.data else null

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PdfReaderScreen(initialUri = initialUri)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(initialUri: Uri? = null) {
    val context = LocalContext.current
    var pdfUri by remember { mutableStateOf(initialUri) }
    var fileName by remember { mutableStateOf("PDF Читалка") }
    var pageCount by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    val rendererMutex = remember { Mutex() }

    // Масштабирование и панорамирование
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val listState = rememberLazyListState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pdfUri = uri
            scale = 1f
            offset = Offset.Zero
        }
    }

    // Открытие и закрытие PdfRenderer при смене файла
    DisposableEffect(pdfUri) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        val currentUri = pdfUri
        if (currentUri != null) {
            try {
                // Получаем имя файла
                context.contentResolver.query(currentUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: "Документ.pdf"
                    }
                }

                pfd = context.contentResolver.openFileDescriptor(currentUri, "r")
                if (pfd != null) {
                    renderer = PdfRenderer(pfd)
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                    errorMessage = null
                } else {
                    errorMessage = "Не удалось открыть файл"
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка чтения PDF: ${e.localizedMessage}"
                pdfRenderer = null
                pageCount = 0
            }
        }

        onDispose {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Exception) {
            }
        }
    }

    val currentPage by remember {
        derivedStateOf {
            if (pageCount > 0) listState.firstVisibleItemIndex + 1 else 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (pageCount > 0) {
                            Text(
                                text = "Страница $currentPage из $pageCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (scale > 1.01f) {
                        IconButton(onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        }) {
                            Icon(
                                imageVector = Icons.Default.ZoomOutMap,
                                contentDescription = "Сбросить масштаб"
                            )
                        }
                    }
                    IconButton(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Открыть PDF"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFEEEEEE))
        ) {
            when {
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: "Ошибка",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                            Text("Выбрать другой файл")
                        }
                    }
                }

                pdfRenderer == null || pageCount == 0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Выберите PDF-файл для чтения",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Открыть PDF")
                        }
                    }
                }

                else -> {
                    val configuration = LocalConfiguration.current
                    val density = LocalDensity.current
                    val targetWidthPx = remember(configuration.screenWidthDp) {
                        with(density) { (configuration.screenWidthDp.dp * 2).roundToPx() }
                            .coerceAtLeast(1080)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.2f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.2f
                                        }
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 4f)
                                    scale = newScale
                                    if (newScale > 1f) {
                                        offset += pan
                                    } else {
                                        offset = Offset.Zero
                                    }
                                }
                            }
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                ),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(count = pageCount, key = { it }) { pageIndex ->
                                PdfPageItem(
                                    renderer = pdfRenderer!!,
                                    mutex = rendererMutex,
                                    pageIndex = pageIndex,
                                    targetWidthPx = targetWidthPx
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PdfPageItem(
    renderer: PdfRenderer,
    mutex: Mutex,
    pageIndex: Int,
    targetWidthPx: Int
) {
    var bitmap by remember(renderer, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var aspectRatio by remember(renderer, pageIndex) { mutableFloatStateOf(0.707f) } // ~A4

    LaunchedEffect(renderer, pageIndex, targetWidthPx) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    renderer.openPage(pageIndex).use { page ->
                        val ratio = page.width.toFloat() / page.height.toFloat().coerceAtLeast(1f)
                        aspectRatio = ratio
                        val heightPx = (targetWidthPx / ratio).toInt().coerceAtLeast(1)

                        val bmp = Bitmap.createBitmap(
                            targetWidthPx,
                            heightPx,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bmp)
                        canvas.drawColor(AndroidColor.WHITE)

                        page.render(
                            bmp,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        bitmap = bmp
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "Страница ${pageIndex + 1}",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
    }
}
