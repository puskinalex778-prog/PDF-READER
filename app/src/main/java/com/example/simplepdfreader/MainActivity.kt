package com.example.simplepdfreader

import android.content.Context
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecentPdfItem(
    val uriString: String,
    val name: String,
    val lastPage: Int,
    val pageCount: Int,
    val lastOpened: Long
)

object RecentDocsManager {
    private const val PREFS_NAME = "pdf_reader_prefs"
    private const val KEY_RECENTS = "recent_documents"
    private const val MAX_ITEMS = 30

    fun getRecents(context: Context): List<RecentPdfItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RECENTS, "[]") ?: "[]"
        val result = mutableListOf<RecentPdfItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    RecentPdfItem(
                        uriString = obj.getString("uri"),
                        name = obj.optString("name", "Документ.pdf"),
                        lastPage = obj.optInt("lastPage", 0),
                        pageCount = obj.optInt("pageCount", 0),
                        lastOpened = obj.optLong("lastOpened", 0L)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return result.sortedByDescending { it.lastOpened }
    }

    fun saveOrUpdate(
        context: Context,
        uriString: String,
        name: String,
        lastPage: Int,
        pageCount: Int
    ): List<RecentPdfItem> {
        val current = getRecents(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.uriString == uriString }
        val resolvedName = if (name.isBlank() || name == "PDF Читалка") {
            current.getOrNull(existingIndex)?.name ?: "Документ.pdf"
        } else {
            name
        }
        val item = RecentPdfItem(
            uriString = uriString,
            name = resolvedName,
            lastPage = lastPage.coerceAtLeast(0),
            pageCount = pageCount.coerceAtLeast(0),
            lastOpened = System.currentTimeMillis()
        )
        if (existingIndex != -1) {
            current.removeAt(existingIndex)
        }
        current.add(0, item)
        val trimmed = current.take(MAX_ITEMS)
        saveList(context, trimmed)
        return trimmed
    }

    fun remove(context: Context, uriString: String): List<RecentPdfItem> {
        val current = getRecents(context).filterNot { it.uriString == uriString }
        saveList(context, current)
        return current
    }

    fun clearAll(context: Context): List<RecentPdfItem> {
        saveList(context, emptyList())
        return emptyList()
    }

    private fun saveList(context: Context, list: List<RecentPdfItem>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("uri", item.uriString)
                put("name", item.name)
                put("lastPage", item.lastPage)
                put("pageCount", item.pageCount)
                put("lastOpened", item.lastOpened)
            }
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENTS, array.toString())
            .apply()
    }
}

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

    var recents by remember { mutableStateOf(RecentDocsManager.getRecents(context)) }

    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    val rendererMutex = remember { Mutex() }

    // Масштабирование и панорамирование
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val listState = rememberLazyListState()

    // Возврат к списку недавних документов по кнопке "Назад"
    BackHandler(enabled = pdfUri != null) {
        pdfUri = null
        fileName = "PDF Читалка"
        pageCount = 0
        errorMessage = null
        scale = 1f
        offset = Offset.Zero
        recents = RecentDocsManager.getRecents(context)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Сохраняем постоянный доступ к файлу, чтобы его можно было открыть из истории после перезапуска
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
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
                var resolvedName = "Документ.pdf"
                context.contentResolver.query(currentUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        resolvedName = cursor.getString(nameIndex) ?: "Документ.pdf"
                    }
                }
                fileName = resolvedName

                pfd = context.contentResolver.openFileDescriptor(currentUri, "r")
                if (pfd != null) {
                    renderer = PdfRenderer(pfd)
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                    errorMessage = null

                    val savedPage = recents
                        .firstOrNull { it.uriString == currentUri.toString() }
                        ?.lastPage
                        ?.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                        ?: 0

                    recents = RecentDocsManager.saveOrUpdate(
                        context = context,
                        uriString = currentUri.toString(),
                        name = resolvedName,
                        lastPage = savedPage,
                        pageCount = renderer.pageCount
                    )
                } else {
                    errorMessage = "Не удалось открыть файл"
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка чтения PDF (возможно, файл был перемещён или удалён): ${e.localizedMessage}"
                pdfRenderer = null
                pageCount = 0
            }
        } else {
            pdfRenderer = null
            pageCount = 0
        }

        onDispose {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Exception) {
            }
        }
    }

    // Восстановление последней открытой страницы при открытии документа
    LaunchedEffect(pdfRenderer, pdfUri) {
        val currentUri = pdfUri
        if (pdfRenderer != null && currentUri != null && pageCount > 0) {
            val savedPage = RecentDocsManager.getRecents(context)
                .firstOrNull { it.uriString == currentUri.toString() }
                ?.lastPage
                ?.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                ?: 0
            listState.scrollToItem(savedPage)
        }
    }

    val currentPageIndex by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex
        }
    }

    // Автосохранение текущей страницы в историю при прокрутке
    LaunchedEffect(currentPageIndex, pdfUri, pageCount) {
        val currentUri = pdfUri
        if (currentUri != null && pageCount > 0) {
            recents = RecentDocsManager.saveOrUpdate(
                context = context,
                uriString = currentUri.toString(),
                name = fileName,
                lastPage = currentPageIndex,
                pageCount = pageCount
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (pdfUri != null) {
                        IconButton(onClick = {
                            pdfUri = null
                            fileName = "PDF Читалка"
                            pageCount = 0
                            errorMessage = null
                            scale = 1f
                            offset = Offset.Zero
                            recents = RecentDocsManager.getRecents(context)
                        }) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "К списку документов"
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = if (pdfUri != null) fileName else "PDF Читалка",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (pdfUri != null && pageCount > 0) {
                            Text(
                                text = "Страница ${currentPageIndex + 1} из $pageCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (pdfUri != null && scale > 1.01f) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                pdfUri = null
                                errorMessage = null
                            }) {
                                Text("К истории")
                            }
                            Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                                Text("Выбрать файл")
                            }
                        }
                    }
                }

                pdfRenderer == null || pageCount == 0 -> {
                    HomeRecentDocsContent(
                        recents = recents,
                        onOpenPicker = { launcher.launch(arrayOf("application/pdf")) },
                        onOpenRecent = { item ->
                            pdfUri = Uri.parse(item.uriString)
                            scale = 1f
                            offset = Offset.Zero
                        },
                        onRemoveRecent = { item ->
                            recents = RecentDocsManager.remove(context, item.uriString)
                        },
                        onClearAll = {
                            recents = RecentDocsManager.clearAll(context)
                        }
                    )
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
fun HomeRecentDocsContent(
    recents: List<RecentPdfItem>,
    onOpenPicker: () -> Unit,
    onOpenRecent: (RecentPdfItem) -> Unit,
    onRemoveRecent: (RecentPdfItem) -> Unit,
    onClearAll: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    if (recents.isEmpty()) {
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Недавно открытые документы появятся здесь автоматически",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onOpenPicker) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Открыть PDF")
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPicker() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Открыть другой PDF-файл",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Выбрать документ из памяти устройства",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Недавние документы",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onClearAll) {
                        Text("Очистить")
                    }
                }
            }

            items(items = recents, key = { it.uriString }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecent(item) },
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val pageInfo = if (item.pageCount > 0) {
                                "Остановились: стр. ${item.lastPage + 1} из ${item.pageCount}"
                            } else {
                                "Стр. ${item.lastPage + 1}"
                            }
                            Text(
                                text = "$pageInfo • ${dateFormat.format(Date(item.lastOpened))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRemoveRecent(item) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Удалить из истории",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
