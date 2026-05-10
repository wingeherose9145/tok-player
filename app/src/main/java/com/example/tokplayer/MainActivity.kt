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
    var isNavVisible by remember { mutableStateOf(false) } // 导航栏显隐状态
    
    val availableFolders = remember { getAvailableVideoFolders(context) }
    val videos = remember(currentPlaylistName) { getVideos(context, currentPlaylistName) }

    // 自动隐藏逻辑：如果显示了，3秒后自动关闭
    LaunchedEffect(isNavVisible) {
        if (isNavVisible) {
            delay(3000)
            isNavVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 视频播放器
        TikTokPlayer(videos = videos, onScreenClick = { yOffset, screenHeight ->
            // 如果点击的是屏幕上方 1/3，则切换导航栏显示
            if (yOffset < screenHeight / 3) {
                isNavVisible = !isNavVisible
            }
        })

        // 导航栏：带进入/退出动画
        AnimatedVisibility(
            visible = isNavVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f)) // 导航呼出时带点半透明底，方便看清字
                    .padding(top = 50.dp, bottom = 15.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    CategoryTab("全部", currentPlaylistName == null) { 
                        currentPlaylistName = null
                        isNavVisible = false // 选中后隐藏
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
fun TikTokPlayer(videos: List<Uri>, onScreenClick: (Float, Int) -> Unit) {
    if (videos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未找到视频", color = Color.White.copy(alpha = 0.3f))
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { videos.size })
    
    VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        VideoPage(
            uri = videos[page],
            play = pagerState.currentPage == page,
            onToggleNav = onScreenClick
        )
    }
}

@Composable
fun VideoPage(uri: Uri, play: Boolean, onToggleNav: (Float, Int) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pausedByUser by remember { mutableStateOf(false) }
    var isAppInForeground by remember { mutableStateOf(true) }

    // 生命周期监听
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
        modifier = Modifier.fillMaxSize().pointerInteropFilter { event ->
            // 利用坐标判断点击位置
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val screenHeight = context.resources.displayMetrics.heightPixels
                if (event.y < screenHeight / 3) {
                    onToggleNav(event.y, screenHeight) // 点击上方触发导航
                } else {
                    pausedByUser = !pausedByUser // 点击下方触发播放/暂停
                }
            }
            true
        },
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
                modifier = Modifier.size(72.dp),
                tint = Color.White.copy(alpha = 0.15f) // 极浅
            )
        }
    }
}

// 注意：由于 GitHub 编辑环境需要最简依赖，
// 如果编译报错，请将上面的 `pointerInteropFilter` 换回简单的 `clickable`。
// 下方是 `clickable` 版更稳定的 VideoPage 点击实现：

/* Box(
    modifier = Modifier.fillMaxSize().clickable { pausedByUser = !pausedByUser }
) { ... }
并在 MainScreen 的 TikTokPlayer 外部包裹一个透明的控制层，专门拦截顶部点击。
*/

// 保持之前 getAvailableVideoFolders 和 getVideos 函数不变...
// (为节省篇幅，此处省略这两个数据函数，请沿用上一版本的)
