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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            MaterialTheme {
                RandomGalleryScreen()
            }
        }
    }
}

private fun openFolder(
    context: android.content.Context,
    uri: Uri
): DocumentFile? {
    return try {
        DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
    } catch (_: Exception) {
        null
    }
}

private suspend fun listChildFolders(
    context: android.content.Context,
    uri: Uri
): List<FolderNode> = withContext(Dispatchers.IO) {
    try {
        openFolder(context, uri)
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.map {
                FolderNode(
                    uri = it.uri,
                    name = it.name ?: "Unnamed folder"
                )
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun mediaType(file: DocumentFile): Boolean? {
    val mime = file.type.orEmpty()
    val extension = file.name
        ?.substringAfterLast('.', "")
        ?.lowercase()
        .orEmpty()

    return when {
        mime.startsWith("video/") ||
                extension in setOf(
            "mp4", "m4v", "mov", "3gp", "mkv", "webm"
        ) -> true

        mime.startsWith("image/") ||
                extension in setOf(
            "jpg", "jpeg", "png", "webp",
            "gif", "bmp", "heic", "heif", "avif"
        ) -> false

        else -> null
    }
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
        folderPathFromUri(context, Uri.parse(uriString))
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val roots = remember { mutableStateListOf<Uri>() }
    val loadedChildren = remember {
        mutableStateMapOf<String, List<FolderNode>>()
    }
    val expandedIds = remember {
        mutableStateMapOf<String, Boolean>()
    }
    val selectionRules = remember {
        mutableStateMapOf<String, Boolean>()
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
        val childrenSnapshot = loadedChildren.toMap()

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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    roots.forEach { rootUri ->
                        val root = FolderNode(
                            uri = rootUri,
                            name = openFolder(context, rootUri)?.name
                                ?: "Selected folder"
                        )

                        FolderTreeRow(
                            node = root,
                            depth = 0,
                            inheritedSelected = true,
                            loadedChildren = loadedChildren,
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
    loadedChildren: SnapshotStateMap<String, List<FolderNode>>,
    expandedIds: SnapshotStateMap<String, Boolean>,
    selectionRules: SnapshotStateMap<String, Boolean>,
    onExpand: (FolderNode) -> Unit
) {
    val key = node.uri.toString()
    val selected = selectionRules[key] ?: inheritedSelected
    val expanded = expandedIds[key] == true
    val children = loadedChildren[key].orEmpty()

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
            node.name,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }

    if (expanded) {
        children.forEach { child ->
            FolderTreeRow(
                node = child,
                depth = depth + 1,
                inheritedSelected = selected,
                loadedChildren = loadedChildren,
                expandedIds = expandedIds,
                selectionRules = selectionRules,
                onExpand = onExpand
            )
        }
    }
}

private fun getFileDetails(
    context: android.content.Context,
    uri: Uri
): Pair<String, String> {
    var name = "Unknown file"
    var folder = "Unknown folder"

    try {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
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

                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }

                if (pathIndex >= 0) {
                    folder = cursor.getString(pathIndex)
                        ?.trimEnd('/')
                        ?: folder
                }
            }
        }
    } catch (_: Exception) {
        // Keep fallback details if metadata isn't available.
    }

    return name to folder
}

@Composable
fun FullscreenViewer(
    media: List<GalleryMedia>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val (fileName, folderPath) = remember(media[index].uri) {
        getFileDetails(context, media[index].uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(index, media.size) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        dragDistance += amount
                        change.consume()
                    },
                    onDragEnd = {
                        if (dragDistance < -60 && index < media.lastIndex) {
                            onIndexChange(index + 1)
                        } else if (dragDistance > 60 && index > 0) {
                            onIndexChange(index - 1)
                        }
                        dragDistance = 0f
                    }
                )
            }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
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
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

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
                .padding(bottom = 76.dp, start = 16.dp, end = 16.dp)
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
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { onIndexChange(index - 1) },
                enabled = index > 0
            ) {
                Text("Previous")
            }

            Button(
                onClick = { onIndexChange(index + 1) },
                enabled = index < media.lastIndex
            ) {
                Text("Next")
            }
        }
    }
}
