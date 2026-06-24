package com.example.kidgallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemUi()
        setContent { KidGalleryApp() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}

data class MediaItem(val uri: Uri, val mimeType: String, val name: String, val modified: Long) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

@Composable
fun KidGalleryApp() {
    val context = LocalContext.current
    var folderUri by remember { mutableStateOf(loadFolderUri(context)) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveFolderUri(context, uri)
                folderUri = uri
            }
        }
    }

    LaunchedEffect(folderUri) {
        val current = folderUri
        if (current == null) {
            loading = false
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            folderPicker.launch(intent)
        } else {
            loading = true
            media = loadMediaFromTree(context, current)
            loading = false
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                folderUri == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sélectionne le dossier photos/vidéos", color = Color.White)
                }
                media.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aucune photo ou vidéo dans ce dossier", color = Color.White)
                }
                else -> GalleryPager(media)
            }
        }
    }
}

@Composable
fun GalleryPager(items: List<MediaItem>) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val item = items[page]
        if (item.isVideo) {
            VideoPage(item.uri)
        } else {
            ImagePage(item.uri)
        }
    }
}

@Composable
fun ImagePage(uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            }.getOrNull()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } ?: CircularProgressIndicator()
    }
}

@Composable
fun VideoPage(uri: Uri) {
    var ended by remember(uri) { mutableStateOf(false) }
    var videoView by remember(uri) { mutableStateOf<VideoView?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setVideoURI(uri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        ended = false
                        start()
                    }
                    setOnCompletionListener {
                        pause()
                        seekTo(duration)
                        ended = true
                    }
                    videoView = this
                }
            },
            update = { view ->
                videoView = view
                if (!ended && !view.isPlaying) {
                    view.setVideoURI(uri)
                    view.start()
                }
            }
        )

        if (ended) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(Color.White.copy(alpha = 0.25f))
                    .clickable {
                        ended = false
                        videoView?.seekTo(0)
                        videoView?.start()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = Color.White, fontSize = 54.sp)
            }
        }
    }
}

private fun loadFolderUri(context: Context): Uri? {
    return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("folderUri", null)
        ?.let(Uri::parse)
}

private fun saveFolderUri(context: Context, uri: Uri) {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putString("folderUri", uri.toString())
        .apply()
}

private suspend fun loadMediaFromTree(context: Context, treeUri: Uri): List<MediaItem> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    val result = mutableListOf<MediaItem>()
    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        while (cursor.moveToNext()) {
            val docId = cursor.getString(idIndex)
            val name = cursor.getString(nameIndex) ?: ""
            val mime = cursor.getString(mimeIndex) ?: ""
            val modified = runCatching { cursor.getLong(modifiedIndex) }.getOrDefault(0L)
            if (mime.startsWith("image/") || mime.startsWith("video/")) {
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                result.add(MediaItem(documentUri, mime, name, modified))
            }
        }
    }
    result.sortedWith(compareBy<MediaItem> { it.modified }.thenBy { it.name })
}
