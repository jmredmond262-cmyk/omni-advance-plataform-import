package com.example.ui.screens

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R

/**
 * Reproduce la presentación original de OMNI desde recursos locales.
 * El acceso se habilita únicamente al terminar el video o si el reproductor falla.
 */
@Composable
fun OmniSplashScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val latestOnFinished = rememberUpdatedState(onFinished)
    val videoView = remember(context) { VideoView(context) }

    DisposableEffect(videoView) {
        videoView.setVideoURI(
            Uri.parse("android.resource://${context.packageName}/${R.raw.omni_intro}")
        )
        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            player.start()
        }
        videoView.setOnCompletionListener {
            latestOnFinished.value.invoke()
        }
        videoView.setOnErrorListener { _, _, _ ->
            latestOnFinished.value.invoke()
            true
        }
        onDispose {
            videoView.stopPlayback()
            videoView.setOnCompletionListener(null)
            videoView.setOnErrorListener(null)
            videoView.setOnPreparedListener(null)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { videoView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
