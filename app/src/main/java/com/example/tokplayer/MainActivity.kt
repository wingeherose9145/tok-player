package com.example.tokplayer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState // ✅ 新增：需要记住 LazyRow 的滚动状态
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_VIDEO), 1)
        }
        setContent { MainScreen() }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var currentPlaylistName by remember { mutableStateOf<String?>(null) }
    var isNavVisible by remember { mutableStateOf(false) } 
    
    val availableFolders = remember { getAvailableVideoFolders(context) }
    val videos = remember(currentPlaylistName) { getVideos(context, currentPlaylistName) }

    // ✅ 核心修改 1：为导航栏引入滚动状态管理
    val lazyListState = rememberLazyListState()
    
    // ✅ 核心修改 2：引入一个触发器状态，每次操作（比如点击顶栏空白处）时增加它，来强行重置计时器
    var hideTimerResetTrigger by remember { mutableIntStateOf(0) }

    // ✅ 核心修改 3：优化的计时隐藏逻辑。
    // `delay()` 会在这个 LaunchedEffect 被重新启动时被自动取消。
    // 这里我们观察四个状态，只要有一个变了，计时器都会重新开始计时：
    // 1. 是否可见 (isNavVisible)
    // 2. 触发器状态 (hideTimerResetTrigger)
    // 3. 是否正在滚动 (lazyListState.isScrollInProgress)
    LaunchedEffect(isNavVisible, hideTimerResetTrigger, lazyListState.isScrollInProgress) {
        if (isNavVisible) {
            
            // 如果用户正在滚动导航栏，我们就不执行计时器，而是让 Effect 等待下一次滚动状态变为了 false。
            if (lazyListState.isScrollInProgress) {
                return@LaunchedEffect
            }
            
            // 如果没操作，则开始3秒延迟
            delay(3000) 
            
            // 双重检查，防止在3秒内用户刚好手动隐藏了导航
            if (isNavVisible) {
                isNavVisible = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. 视频播放层
        TikTokPlayer(videos = videos)

        // 2. 透明交互层
        Column(modifier = Modifier.fillMaxSize()) {
            // 点击上方 1/3 区域显隐导航。
            // 当呼出导航时，重置计时器。
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clickable { 
                isNavVisible = !isNavVisible 
                if (isNavVisible) {
                    // 当显现时强制重置
                    hideTimerResetTrigger++
                }
            })
            Box(modifier = Modifier.fillMaxWidth().weight(2f)) 
        }

        // 3. 导航栏层
        AnimatedVisibility(
            visible = isNavVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            LazyRow(
                state = lazyListState, // ✅ 核心修改 4：将 LazyRow 与状态绑定，这样才能监听它的滚动状态。
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    // 如果你需要点击导航栏除文字外的空白区域也重置计时器，则可以加在这里（不推荐，可能会导致文字难以点击）
                    // .clickable(indication = null, interactionSource = remember { remember{ MutableInteractionSource() } }){ hideTimerResetTrigger++ }
                    .padding(top = 50.dp, bottom = 20.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(25.dp)
            ) {
                item {
                    CategoryTab("全部", currentPlaylistName == null) { 
                        currentPlaylistName = null
                        // 选中新列表时，通常立即隐藏导航栏，不需要延迟。符合标准 UX。
                        isNavVisible = false 
                    }
                }
                items(availableFolders) { folder ->
                    CategoryTab(folder, currentPlaylistName == folder) { 
                        currentPlaylistName = folder
                        isNavVisible = false 
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryTab(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Text(
        text = title,
        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.3f),
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        fontSize = if (isSelected) 16.sp else 14.sp,
        modifier = Modifier.clickable { onClick() }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TikTokPlayer(videos: List<Uri>) {
    if (videos.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { videos.size })
    VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        VideoPage(uri = videos[page], play = pagerState.currentPage == page)
    }
}

@Composable
fun VideoPage(uri: Uri, play: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pausedByUser by remember { mutableStateOf(false) }
    var isAppInForeground by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) isAppInForeground = false
            if (event == Lifecycle.Event.ON_RESUME) isAppInForeground = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(play, pausedByUser, isAppInForeground) {
        if (play && !pausedByUser && isAppInForeground) exoPlayer.play() else exoPlayer.pause()
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Box(
        modifier = Modifier.fillMaxSize().clickable { pausedByUser = !pausedByUser },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false } },
            modifier = Modifier.fillMaxSize()
        )
        if (pausedByUser) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.White.copy(alpha = 0.15f)
            )
        }
    }
}

fun getAvailableVideoFolders(context: android.content.Context): List<String> {
    val folders = mutableSetOf<String>()
    val projection = arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
        val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        while (cursor.moveToNext()) { cursor.getString(columnIndex)?.let { folders.add(it) } }
    }
    return folders.toList().sorted()
}

fun getVideos(context: android.content.Context, albumName: String? = null): List<Uri> {
    val videoList = mutableListOf<Uri>()
    val selection = if (albumName != null) "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ?" else null
    val selectionArgs = if (albumName != null) arrayOf(albumName) else null
    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID), selection, selectionArgs, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        while (cursor.moveToNext()) {
            videoList.add(Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn).toString()))
        }
    }
    return videoList
}
