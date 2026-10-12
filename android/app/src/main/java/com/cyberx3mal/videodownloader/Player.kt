package com.cyberx3mal.videodownloader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import java.io.File

/**
 * Минимальный оверлей-плеер для локального превью-файла.
 * Закрывается системной кнопкой «назад» — файл затем удаляется.
 */
object Player {

    fun isShowing(): Boolean = active != null

    private var active: VideoView? = null
    private var container: FrameLayout? = null
    private var currentFile: File? = null

    fun play(activity: Activity, file: File) {
        closeCurrent()

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val holder = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            id = R.id.player_overlay
        }

        val video = VideoView(activity)
        holder.addView(
            video,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        )

        val controller = MediaController(activity)
        video.setMediaController(controller)
        controller.setAnchorView(video)

        val backdrop = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xCC000000.toInt())
            setOnTouchListener { _, _ -> true }
        }

        holder.addView(
            backdrop,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val close = android.widget.TextView(activity).apply {
            text = "  Закрыть  "
            setTextColor(0xFF06121F.toInt())
            textSize = 14f
            setBackgroundColor(0xFF22D3EE.toInt())
            setPadding(28, 16, 28, 16)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = 40
            lp.rightMargin = 24
            layoutParams = lp
            setOnClickListener { closeCurrent() }
        }
        holder.addView(close)

        video.setOnCompletionListener { closeCurrent() }
        video.setOnErrorListener { _: MediaPlayer, what: Int, extra: Int ->
            Log.e("Player", "playback error what=$what extra=$extra")
            closeCurrent()
            true
        }

        root.addView(holder)
        container = holder
        active = video
        currentFile = file

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        video.setVideoURI(Uri.fromFile(file))
        video.start()
    }

    fun closeCurrent() {
        active?.apply {
            stopPlayback()
            setOnCompletionListener(null)
            setOnErrorListener(null)
        }
        active = null
        container?.parent?.let { (it as? ViewGroup)?.removeView(container) }
        container = null
        currentFile?.let { net ->
            currentFile = null
            YtdlpHelper.deleteQuietly(net)
        }
    }
}
