package com.example.randomgallery

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FolderNode(
    val uri: Uri,
    val name: String
)

data class GalleryMedia(
    val uri: Uri,
    val isVideo: Boolean
)

private data class IndexedFolderPath(
    val volume: String,
    val path: String
)

private data class IndexedSelectionRule(
    val folder: IndexedFolderPath,
    val selected: Boolean
)

private fun folderPathFromUri(
    context: android.content.Context,
    uri: Uri
): IndexedFolderPath? {
    return try {
        val documentId = when {
            DocumentsContract.isDocumentUri(context, uri) ->
                DocumentsContract.getDocumentId(uri)

            DocumentsContract.isTreeUri(uri) ->
                DocumentsContract.getTreeDocumentId(uri)

            else -> return null
        }

        val colon = documentId.indexOf(':')
        if (colon < 0) return null

        val volume = documentId.substring(0, colon)
        val path = documentId.substring(colon + 1)
            .trim('/')
            .replace(Regex("/+"), "/")

        IndexedFolderPath(volume, path)
    } catch (_: Exception) {
        null
    }
}

private fun normalizeMediaVolume(volume: String): String {
    return if (volume.equals("primary", ignoreCase = true)) {
        "external_primary"
    } else {
        volume
    }
}

private fun pathIsWithin(
    candidate: String,
    parent: String
): Boolean {
    return candidate == parent ||
            (parent.isNotEmpty() && candidate.startsWith("$parent/")) ||
            parent.isEmpty()
}

private fun hasMediaAccess(
    context: android.content.Context
): Boolean {
    fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    return if (Build.VERSION.SDK_INT >= 34) {
        granted(android.Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(android.Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else if (Build.VERSION.SDK_INT >= 33) {
        granted(android.Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(android.Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun requiredMediaPermissions(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO
        )

        else -> arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme (
                colorScheme = darkColorScheme()
            ) {
                RandomGalleryScreen()
            }
        }
    }
}

private suspend fun listChildFolders(
    context: android.content.Context,
    uri: Uri
): List<FolderNode> = withContext(Dispatchers.IO) {
    try {
        val authority = uri.authority ?: return@withContext emptyList()
        val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
        val documentId = if (DocumentsContract.isTreeUri(uri)) {
            treeDocumentId
        } else {
            DocumentsContract.getDocumentId(uri)
        }

        val treeUri = DocumentsContract.buildTreeDocumentUri(
            authority,
            treeDocumentId
        )

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                documentId
            )

        val folders = mutableListOf<FolderNode>()

        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            val mimeColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            while (cursor.moveToNext()) {
                if (
                    cursor.getString(mimeColumn) ==
                    DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    val childId = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn)
                        ?: "Unnamed folder"

                    folders.add(
                        FolderNode(
                            uri = DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                childId
                            ),
                            name = name
                        )
                    )
                }
            }
        }

        folders.sortedBy { it.name.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }
}

private data class VisibleFolderRow(
    val node: FolderNode,
    val depth: Int,
    val inheritedSelected: Boolean
)

private fun folderNameFromUri(uri: Uri): String {
    return try {
        DocumentsContract.getTreeDocumentId(uri)
            .substringAfter(':')
            .trim('/')
            .substringAfterLast('/')
            .ifBlank { "Selected folder" }
    } catch (_: Exception) {
        "Selected folder"
    }
}

private fun buildVisibleFolderRows(
    roots: List<Uri>,
    loadedChildren: Map<String, List<FolderNode>>,
    expandedIds: Map<String, Boolean>,
    selectionRules: Map<String, Boolean>
): List<VisibleFolderRow> {
    val rows = mutableListOf<VisibleFolderRow>()

    fun addFolder(
        node: FolderNode,
        depth: Int,
        inheritedSelected: Boolean
    ) {
        rows.add(
            VisibleFolderRow(
                node = node,
                depth = depth,
                inheritedSelected = inheritedSelected
            )
        )

        val key = node.uri.toString()
        val selected = selectionRules[key] ?: inheritedSelected

        if (expandedIds[key] == true) {
            loadedChildren[key].orEmpty().forEach { child ->
                addFolder(child, depth + 1, selected)
            }
        }
    }

    roots.forEach { uri ->
        addFolder(
            FolderNode(uri, folderNameFromUri(uri)),
            depth = 0,
            inheritedSelected = true
        )
    }

    return rows
}

private suspend fun scanSelectedMedia(
    context: android.content.Context,
    roots: List<Uri>,
    rules: Map<String, Boolean>,
    onProgress: (Int) -> Unit
): List<GalleryMedia> = withContext(Dispatchers.IO) {

    if (Build.VERSION.SDK_INT < 29) {
        throw IllegalStateException(
            "Indexed folder filtering requires Android 10 or later."
        )
    }

    val rootPaths = roots.mapNotNull { uri ->
        folderPathFromUri(context, uri)
    }

    val selectionRules = rules.mapNotNull { (uriString, selected) ->
        folderPathFromUri(context, uriString.toUri())
            ?.let { IndexedSelectionRule(it, selected) }
    }

    if (rootPaths.isEmpty()) {
        throw IllegalStateException(
            "Couldn't identify the selected folder paths."
        )
    }

    val collection = MediaStore.Files.getContentUri("external")

    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.VOLUME_NAME
    )

    val selection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"

    val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )

    val result = mutableListOf<GalleryMedia>()
    val seen = mutableSetOf<String>()
    var examined = 0

    context.contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->

        val idColumn = cursor.getColumnIndexOrThrow(
            MediaStore.MediaColumns._ID
        )
        val typeColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        val pathColumn = cursor.getColumnIndexOrThrow(
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val volumeColumn = cursor.getColumnIndexOrThrow(
            MediaStore.MediaColumns.VOLUME_NAME
        )

        while (cursor.moveToNext()) {
            examined++

            if (examined % 500 == 0) {
                onProgress(examined)
            }

            val relativePath = cursor.getString(pathColumn)
                ?.trim('/')
                ?.replace(Regex("/+"), "/")
                ?: continue

            val volume = normalizeMediaVolume(
                cursor.getString(volumeColumn) ?: continue
            )

            // Ignore media outside every selected root.
            val matchingRoot = rootPaths
                .filter {
                    normalizeMediaVolume(it.volume).equals(
                        volume,
                        ignoreCase = true
                    ) && pathIsWithin(relativePath, it.path)
                }
                .maxByOrNull { it.path.length }
                ?: continue

            // The most specific matching rule wins.
            // This allows a checked child inside an unchecked parent.
            val matchingRule = selectionRules
                .filter { rule ->
                    normalizeMediaVolume(rule.folder.volume).equals(
                        volume,
                        ignoreCase = true
                    ) &&
                            pathIsWithin(relativePath, rule.folder.path) &&
                            pathIsWithin(
                                rule.folder.path,
                                matchingRoot.path
                            )
                }
                .maxByOrNull { it.folder.path.length }

            // Roots are selected by default.
            if (matchingRule?.selected == false) continue

            val id = cursor.getLong(idColumn)
            val mediaType = cursor.getInt(typeColumn)

            val uri = ContentUris.withAppendedId(collection, id)

            if (seen.add(uri.toString())) {
                result.add(
                    GalleryMedia(
                        uri = uri,
                        isVideo = mediaType ==
                                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    )
                )
            }
        }
    } ?: throw IllegalStateException(
        "Android couldn't query its media index. Check media permissions."
    )

    onProgress(examined)
    result
}

@Composable
fun RandomGalleryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember(context) {
        context.getSharedPreferences(
            "random_gallery_settings",
            android.content.Context.MODE_PRIVATE
        )
    }

    val roots = remember(context) {
        mutableStateListOf<Uri>().apply {
            try {
                val saved = JSONArray(prefs.getString("roots", "[]"))
                for (i in 0 until saved.length()) {
                    add(Uri.parse(saved.getString(i)))
                }
            } catch (_: Exception) {
            }
        }
    }

    val loadedChildren = remember {
        mutableStateMapOf<String, List<FolderNode>>()
    }

    val expandedIds = remember(context) {
        mutableStateMapOf<String, Boolean>().apply {
            try {
                val saved = JSONObject(
                    prefs.getString("expanded", "{}") ?: "{}"
                )
                saved.keys().forEach { key ->
                    put(key, saved.getBoolean(key))
                }
            } catch (_: Exception) {
            }
        }
    }

    val selectionRules = remember(context) {
        mutableStateMapOf<String, Boolean>().apply {
            try {
                val saved = JSONObject(
                    prefs.getString("selection_rules", "{}") ?: "{}"
                )
                saved.keys().forEach { key ->
                    put(key, saved.getBoolean(key))
                }
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(
        roots.toList(),
        selectionRules.toMap(),
        expandedIds.toMap()
    ) {
        val savedRoots = JSONArray()
        roots.forEach { savedRoots.put(it.toString()) }

        val savedRules = JSONObject()
        selectionRules.forEach { (uri, selected) ->
            savedRules.put(uri, selected)
        }

        val savedExpanded = JSONObject()
        expandedIds.forEach { (uri, expanded) ->
            savedExpanded.put(uri, expanded)
        }

        prefs.edit {
            putString("roots", savedRoots.toString())
            putString("selection_rules", savedRules.toString())
            putString("expanded", savedExpanded.toString())
        }
    }

    LaunchedEffect(Unit) {
        val previouslyExpanded = expandedIds
            .filterValues { it }
            .keys
            .toList()

        for (key in previouslyExpanded) {
            try {
                loadedChildren[key] = listChildFolders(
                    context,
                    key.toUri()
                )
            } catch (_: Exception) {
            }
        }
    }

    var showFolders by remember { mutableStateOf(false) }
    var loadingFolders by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var gallery by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var viewerIndex by remember { mutableIntStateOf(-1) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                if (uri !in roots) {
                    roots.add(uri)
                    selectionRules[uri.toString()] = true
                }

                showFolders = true
            } catch (e: Exception) {
                errorMessage = "Couldn't access this folder: ${e.message}"
            }
        }
    }

    fun expandFolder(node: FolderNode) {
        val key = node.uri.toString()
        if (expandedIds[key] == true) {
            expandedIds[key] = false
            return
        }

        expandedIds[key] = true
        if (loadedChildren.containsKey(key)) return

        loadingFolders = true
        scope.launch {
            try {
                loadedChildren[key] = listChildFolders(context, node.uri)
            } finally {
                loadingFolders = false
            }
        }
    }

    fun scanMedia() {
        scanning = true
        scanProgress = 0
        errorMessage = null

        val rootsSnapshot = roots.toList()
        val rulesSnapshot = selectionRules.toMap()

        scope.launch {
            try {
                val found = scanSelectedMedia(
                    context = context,
                    roots = rootsSnapshot,
                    rules = rulesSnapshot
                ) { count ->
                    scanProgress = count
                }

                media = found
                gallery = found.shuffled()
            } catch (e: Exception) {
                errorMessage = "Media scan failed: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMediaAccess(context)) {
            scanMedia()
        } else {
            errorMessage = "Please allow photo and video access to scan your gallery."
        }
    }

    fun requestMediaScan() {
        if (hasMediaAccess(context)) {
            scanMedia()
        } else {
            mediaPermissionLauncher.launch(requiredMediaPermissions())
        }
    }

    LaunchedEffect(Unit) {
        if (roots.isNotEmpty()) {
            requestMediaScan()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                text = "Random Gallery",
                style = MaterialTheme.typography.headlineMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { picker.launch(null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Add folder")
                }

                OutlinedButton(
                    onClick = { showFolders = !showFolders },
                    modifier = Modifier.weight(1f),
                    enabled = roots.isNotEmpty()
                ) {
                    Text(if (showFolders) "Hide folders" else "Folders")
                }
            }

            if (showFolders) {
                Text(
                    "Selected folders",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )

                val visibleFolderRows = buildVisibleFolderRows(
                    roots = roots.toList(),
                    loadedChildren = loadedChildren,
                    expandedIds = expandedIds,
                    selectionRules = selectionRules
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(
                        items = visibleFolderRows,
                        key = { it.node.uri.toString() }
                    ) { row ->
                        FolderTreeRow(
                            node = row.node,
                            depth = row.depth,
                            inheritedSelected = row.inheritedSelected,
                            expandedIds = expandedIds,
                            selectionRules = selectionRules,
                            onExpand = { expandFolder(it) }
                        )
                    }
                }

                Text(
                    "Folders load when expanded. Check a parent to include its descendants; uncheck a child to exclude it.",
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = {
                        showFolders = false
                        requestMediaScan()
                    },
                    enabled = !scanning && roots.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply folders and scan media")
                }
            }

            if (loadingFolders) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (scanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Text("Searching Android's media index…")
            }

            errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                "${gallery.size} media items",
                modifier = Modifier.padding(top = 8.dp)
            )

            Button(
                onClick = { gallery = media.shuffled() },
                enabled = gallery.isNotEmpty() && !scanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Shuffle")
            }

            if (gallery.isEmpty()) {
                Text("Add a folder, then apply your selection.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = gallery,
                        key = { it.uri.toString() }
                    ) { item ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    viewerIndex = gallery.indexOf(item)
                                }
                        ) {
                            if (item.isVideo) {
                                VideoThumbnail(
                                    uri = item.uri,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = "Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (viewerIndex in gallery.indices) {
        Dialog(
            onDismissRequest = { viewerIndex = -1 },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            FullscreenViewer(
                media = gallery,
                index = viewerIndex,
                onIndexChange = { viewerIndex = it },
                onClose = { viewerIndex = -1 }
            )
        }
    }
}

@Composable
fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val bitmapState = produceState<Bitmap?>(
        initialValue = null,
        key1 = uri
    ) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(
                        uri,
                        Size(320, 320),
                        null
                    )
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = bitmapState.value

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Video thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                "VIDEO",
                color = Color.White,
                fontSize = 12.sp
            )
        }

        Text(
            "▶",
            color = Color.White,
            fontSize = 28.sp
        )
    }
}

@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val player = androidx.compose.runtime.remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
            }
        },
        update = { view ->
            view.player = player
        },
        modifier = modifier
    )
}

@Composable
fun FolderTreeRow(
    node: FolderNode,
    depth: Int,
    inheritedSelected: Boolean,
    expandedIds: SnapshotStateMap<String, Boolean>,
    selectionRules: SnapshotStateMap<String, Boolean>,
    onExpand: (FolderNode) -> Unit
) {
    val key = node.uri.toString()
    val selected = selectionRules[key] ?: inheritedSelected
    val expanded = expandedIds[key] == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { onExpand(node) },
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            Text(if (expanded) "▼" else "▶")
        }

        Checkbox(
            checked = selected,
            onCheckedChange = {
                selectionRules[key] = it
            }
        )

        Text(
            text = node.name,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun getFileDetails(
    context: android.content.Context,
    uri: Uri
): Triple<String, String, String> {
    var name = "Unknown file"
    var folder = "Unknown folder"
    var modified = "Unknown date"

    try {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(
                    MediaStore.MediaColumns.DISPLAY_NAME
                )
                val pathIndex = cursor.getColumnIndex(
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
                val modifiedIndex = cursor.getColumnIndex(
                    MediaStore.MediaColumns.DATE_MODIFIED
                )

                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }

                if (pathIndex >= 0) {
                    folder = cursor.getString(pathIndex)
                        ?.trimEnd('/')
                        ?: folder
                }

                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    val timestamp = cursor.getLong(modifiedIndex)
                    if (timestamp > 0L) {
                        modified = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date(timestamp * 1000L))
                    }
                }
            }
        }
    } catch (_: Exception) {
        // Keep fallback details if metadata isn't available.
    }

    return Triple(name, folder, modified)
}

@Composable
fun FullscreenViewer(
    media: List<GalleryMedia>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val context = LocalContext.current

    val (fileName, folderPath, modifiedTime) =
        remember(media[index].uri) {
            getFileDetails(context, media[index].uri)
        }

    LaunchedEffect(index) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        controlsVisible = true
    }

    val transformState = rememberTransformableState {
            zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)

        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(index, media.size, scale) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        if (scale <= 1f) {
                            dragDistance += amount
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        if (scale <= 1f) {
                            if (
                                dragDistance < -60 &&
                                index < media.lastIndex
                            ) {
                                onIndexChange(index + 1)
                            } else if (
                                dragDistance > 60 &&
                                index > 0
                            ) {
                                onIndexChange(index - 1)
                            }
                        }
                        dragDistance = 0f
                    }
                )
            }
    ) {
        if (media[index].isVideo) {
            VideoPlayer(
                uri = media[index].uri,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = media[index].uri,
                contentDescription = "Fullscreen photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
                    .pointerInput(index) {
                        detectTapGestures(
                            onTap = {
                                controlsVisible = !controlsVisible
                            },
                            onDoubleTap = {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        )
                    }
                    .transformable(
                        state = transformState
                    )
            )
        }

        if (controlsVisible) {
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Text("Close", color = Color.White)
            }

            Text(
                "${index + 1} / ${media.size}",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 22.dp)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        bottom = 76.dp,
                        start = 16.dp,
                        end = 16.dp
                    )
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(10.dp)
            ) {
                Text(
                    text = fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = folderPath,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    text = "Last modified: $modifiedTime",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
            }


            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (index > 0) onIndexChange(index - 1)
                    },
                    enabled = index > 0
                ) {
                    Text("Previous")
                }

                Button(
                    onClick = {
                        controlsVisible = !controlsVisible
                    }
                ) {
                    Text(if (controlsVisible) "Hide" else "Show")
                }

                Button(
                    onClick = {
                        if (index < media.lastIndex) {
                            onIndexChange(index + 1)
                        }
                    },
                    enabled = index < media.lastIndex
                ) {
                    Text("Next")
                }
            }
        }
    }
}
