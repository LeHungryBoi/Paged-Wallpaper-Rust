package dev.pagedwallpaper.rust

import android.graphics.Bitmap
import android.graphics.Canvas

internal object NativeCore {
    init {
        System.loadLibrary("paged_wallpaper")
    }

    external fun initialize(filesDirectory: String): String
    external fun state(): String
    external fun reload(): String
    external fun validateBackup(directory: String)
    external fun apply(commandJson: String): String
    external fun setLastOffset(offset: Float)
    external fun flushLastOffset()
}

internal object NativeRenderer {
    external fun draw(
        canvas: Canvas,
        imageA: Bitmap?,
        blurredA: Bitmap?,
        imageB: Bitmap?,
        blurredB: Bitmap?,
        pageA: Int,
        pageB: Int,
        progress: Float,
        forward: Boolean,
        width: Int,
        height: Int,
        dimActive: Boolean,
        motionBlurRadius: Int,
        motionBlurDirection: Int,
    )
}
