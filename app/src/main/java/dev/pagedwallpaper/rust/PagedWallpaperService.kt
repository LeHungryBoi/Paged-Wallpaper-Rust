package dev.pagedwallpaper.rust

import android.animation.ValueAnimator
import android.app.WallpaperService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.animation.LinearInterpolator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

class PagedWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = PagedEngine()

    private inner class PagedEngine : Engine() {
        private val images = mutableMapOf<String, Pair<Bitmap, Bitmap>>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 22, 28, 40)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textAlign = Paint.Align.CENTER
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 210, 220, 235)
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        private val xDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 220, 255) }
        private val yDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 180, 0) }
        private val frameColors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }
        private var snapshot = JSONObject()
        private var appState = JSONObject()
        private var pageCount = 3
        private var surfaceWidth = 1080
        private var surfaceHeight = 2400
        private var currentOffset = 0.5f
        private var renderedOffset = 0.5f
        private var currentYOffset = 0f
        private var offsetDirection = 1
        private var hasLauncherOffset = false
        private var sawZeroOffsetWhileHidden = false
        private var isVisibleNow = false
        private var resumeFade = 1f
        private var fadeAnimator: ValueAnimator? = null
        private var animationPosted = false
        private var lastAnimationFrameNanos = 0L
        private var nextFrameDeadlineNanos = 0L
        private var surfaceFrameRate: Int? = null
        private var receivedXOffset: Float? = null
        private var receivedYOffset: Float? = null
        private var frameSequence = 0
        private var lastRenderedPagePosition: Float? = null
        private var lastRenderedFrameNanos = 0L
        private var extraDimObserver: ContentObserver? = null
        private var receiverRegistered = false

        private val choreographer by lazy { Choreographer.getInstance() }
        private val scheduleHandler = Handler(Looper.getMainLooper())
        private val scheduledDimCheck = object : Runnable {
            override fun run() {
                if (!isVisibleNow) return
                drawFrame()
                scheduleHandler.postDelayed(this, SCHEDULE_CHECK_INTERVAL_MS)
            }
        }
        private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
            animationPosted = false
            drawSmoothedFrame(frameTimeNanos)
        }
        private val configurationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                loadState(clearImages = true)
                drawFrame()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            NativeCore.initialize(filesDir.absolutePath)
            loadState(clearImages = true)
            currentOffset = snapshot.optDouble("lastKnownOffset", homeOffset()).toFloat().coerceIn(0f, 1f)
            renderedOffset = currentOffset
            setOffsetNotificationsEnabled(true)
            registerConfigurationReceiver()
            extraDimObserver = try {
                val uri: Uri = Settings.Secure.getUriFor(REDUCE_BRIGHT_COLORS_ACTIVATED)
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        drawFrame()
                    }
                }
                contentResolver.registerContentObserver(uri, false, observer)
                observer
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not observe Android Extra Dim changes.", error)
                null
            }
        }

        override fun onDestroy() {
            stopOffsetAnimation()
            fadeAnimator?.cancel()
            fadeAnimator = null
            unregisterConfigurationReceiver()
            scheduleHandler.removeCallbacks(scheduledDimCheck)
            extraDimObserver?.let(contentResolver::unregisterContentObserver)
            extraDimObserver = null
            releaseImages()
            lastRenderedPagePosition = null
            NativeCore.flushLastOffset()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val becameVisible = visible && !isVisibleNow
            isVisibleNow = visible
            WallpaperDiagnosticsRecorder.visibilityChanged(visible)
            if (visible) {
                val home = homeOffset()
                if (becameVisible && pageHomeIndex() != 0 &&
                    (sawZeroOffsetWhileHidden || !hasLauncherOffset || currentOffset <= 0.001f)
                ) {
                    currentOffset = home
                    renderedOffset = home
                    NativeCore.setLastOffset(home)
                }
                sawZeroOffsetWhileHidden = false
                if (becameVisible) startResumeFade()
                updateScheduledDimCheck()
                drawFrame()
            } else {
                scheduleHandler.removeCallbacks(scheduledDimCheck)
                stopOffsetAnimation()
                fadeAnimator?.cancel()
                fadeAnimator = null
                resumeFade = 1f
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (surfaceWidth != width || surfaceHeight != height) releaseImages()
            surfaceWidth = width
            surfaceHeight = height
            surfaceFrameRate = null
            updateSurfaceFrameRate()
            drawFrame()
            if (isVisibleNow && offsetAnimationNeeded()) scheduleOffsetAnimation()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopOffsetAnimation()
            super.onSurfaceDestroyed(holder)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            WallpaperDiagnosticsRecorder.offsetChanged(xOffset, isVisibleNow)
            receivedXOffset = xOffset
            receivedYOffset = yOffset
            val home = homeOffset()
            if (!isVisibleNow && pageHomeIndex() != 0 && xOffset <= 0.001f) {
                sawZeroOffsetWhileHidden = true
                return
            }
            if (pageHomeIndex() != 0 && isVisibleNow && xOffset == 0f && xOffsetStep <= 0f) {
                currentOffset = home
                renderedOffset = home
                NativeCore.setLastOffset(home)
                drawFrame(fromOffsetUpdate = true)
                return
            }
            val next = xOffset.coerceIn(0f, 1f)
            if (next > currentOffset + OFFSET_EPSILON) offsetDirection = 1
            if (next < currentOffset - OFFSET_EPSILON) offsetDirection = -1
            currentOffset = next
            currentYOffset = yOffset
            hasLauncherOffset = true
            NativeCore.setLastOffset(next)
            if (!isVisibleNow) return
            if (offsetAnimationNeeded()) {
                scheduleOffsetAnimation()
            } else {
                stopOffsetAnimation()
                if (abs(next - renderedOffset) > OFFSET_EPSILON) {
                    renderedOffset = next
                    drawFrame(fromOffsetUpdate = true)
                } else {
                    drawFrame(fromOffsetUpdate = true)
                }
            }
        }

        private fun registerConfigurationReceiver() {
            val filter = IntentFilter(ACTION_CONFIGURATION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(configurationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(configurationReceiver, filter)
            }
            receiverRegistered = true
        }

        private fun unregisterConfigurationReceiver() {
            if (!receiverRegistered) return
            unregisterReceiver(configurationReceiver)
            receiverRegistered = false
        }

        private fun loadState(clearImages: Boolean) {
            snapshot = JSONObject(NativeCore.state())
            appState = snapshot.optJSONObject("app") ?: JSONObject()
            pageCount = appState.optJSONObject("calibration")?.optInt("pageCount", 3)?.coerceIn(2, 7) ?: 3
            if (clearImages) releaseImages()
            updateScheduledDimCheck()
        }

        private fun updateScheduledDimCheck() {
            scheduleHandler.removeCallbacks(scheduledDimCheck)
            if (isVisibleNow &&
                appState.optJSONObject("darken")?.optString("triggerMode") == "SCHEDULED"
            ) {
                scheduleHandler.postDelayed(scheduledDimCheck, SCHEDULE_CHECK_INTERVAL_MS)
            }
        }

        private fun releaseImages() {
            images.values.forEach { (image, lowResolution) ->
                if (!image.isRecycled) image.recycle()
                if (!lowResolution.isRecycled) lowResolution.recycle()
            }
            images.clear()
        }

        private fun bitmapForPage(index: Int): Pair<Bitmap?, Bitmap?> {
            val pages = appState.optJSONArray("pages") ?: return null to null
            val page = pages.optJSONObject(index) ?: return null to null
            if (page.optBoolean("isBlank")) return null to null
            val id = page.optString("originalId")
            val fileName = if (id.isNotBlank() && id != "null") {
                val media = mediaById(id) ?: return null to null
                File(File(filesDir, "originals"), media.optString("fileName"))
            } else {
                val cropped = page.optString("croppedFileName")
                if (cropped.isBlank() || cropped == "null") return null to null
                File(File(filesDir, "cropped"), cropped)
            }
            val blur = page.optDouble("blurAmount", 0.65).toFloat().coerceIn(0f, 1f)
            val key = "${fileName.absolutePath}:blur-${(blur * 100).roundToInt()}"
            images[key]?.let { return it }
            if (!fileName.isFile) return null to null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(fileName.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null to null
            val sample = (maxOf(bounds.outWidth / surfaceWidth, bounds.outHeight / surfaceHeight) / 2)
                .coerceAtLeast(1)
            val image = BitmapFactory.decodeFile(
                fileName.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return null to null
            val blurScale = (2f + blur * 14f)
            val lowResolution = Bitmap.createScaledBitmap(
                image,
                (image.width / blurScale).toInt().coerceAtLeast(1),
                (image.height / blurScale).toInt().coerceAtLeast(1),
                true,
            )
            val loaded = image to lowResolution
            images[key] = loaded
            return loaded
        }

        private fun mediaById(id: String): JSONObject? {
            val media = snapshot.optJSONArray("originals") ?: return null
            for (index in 0 until media.length()) {
                val item = media.optJSONObject(index) ?: continue
                if (item.optString("id") == id) return item
            }
            return null
        }

        private fun drawFrame(fromOffsetUpdate: Boolean = false) {
            val holder = surfaceHolder ?: return
            val frame = try {
                holder.lockCanvas()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Could not lock wallpaper surface.", error)
                return
            } ?: return
            var submitted = false
            val frameTimeNanos = SystemClock.elapsedRealtimeNanos()
            try {
                val (pageA, pageB, rawProgress) = pageTransition(renderedOffset)
                val state = appState
                val easing = mapOffsetProgress(rawProgress)
                val pagePosition = pageA + easing
                val pageDelta = if (
                    state.optBoolean("motionBlurEnabled", true) &&
                    isVisibleNow &&
                    fromOffsetUpdate &&
                    lastRenderedPagePosition != null &&
                    frameTimeNanos - lastRenderedFrameNanos in 1..200_000_000L
                ) pagePosition - lastRenderedPagePosition!! else 0f
                val blurStrength = state.optInt("motionBlurStrengthPercent", 100).coerceIn(0, 200) / 100f
                val blurRadius = (abs(pageDelta) * surfaceWidth * blurStrength)
                    .toInt().coerceAtMost(surfaceWidth / 4)
                val (imageA, blurA) = bitmapForPage(pageA)
                val (imageB, blurB) = if (pageA == pageB) null to null else bitmapForPage(pageB)
                NativeRenderer.draw(
                    frame,
                    imageA,
                    blurA,
                    imageB,
                    blurB,
                    pageA,
                    pageB,
                    easing,
                    offsetDirection >= 0,
                    frame.width,
                    frame.height,
                    isDimConditionActive(),
                    blurRadius,
                    if (pageDelta > 0f) 1 else if (pageDelta < 0f) -1 else 0,
                )
                if (resumeFade < 1f) {
                    val fade = Paint().apply {
                        color = Color.BLACK
                        alpha = ((1f - resumeFade) * 255).toInt().coerceIn(0, 255)
                    }
                    frame.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), fade)
                }
                drawDebug(frame)
                submitted = true
                lastRenderedPagePosition = pagePosition
                lastRenderedFrameNanos = frameTimeNanos
            } catch (error: RuntimeException) {
                Log.e(TAG, "Rust wallpaper rendering failed.", error)
            } finally {
                try {
                    holder.unlockCanvasAndPost(frame)
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Could not post wallpaper frame.", error)
                    submitted = false
                }
            }
            if (submitted) WallpaperDiagnosticsRecorder.frameSubmitted(fromOffsetUpdate, isVisibleNow)
        }

        private fun pageTransition(offset: Float): Triple<Int, Int, Float> {
            val calibration = appState.optJSONObject("calibration") ?: JSONObject()
            val offsets = calibration.optJSONArray("customOffsets") ?: JSONArray()
            if (pageCount <= 1) return Triple(0, 0, 0f)
            if (offset <= offsets.optDouble(0, 0.0)) return Triple(0, 0, 0f)
            if (offset >= offsets.optDouble(pageCount - 1, 1.0)) return Triple(pageCount - 1, pageCount - 1, 0f)
            for (index in 0 until pageCount - 1) {
                val start = offsets.optDouble(index, index.toDouble() / (pageCount - 1))
                val end = offsets.optDouble(index + 1, (index + 1).toDouble() / (pageCount - 1))
                if (offset <= end) {
                    val progress = ((offset - start) / (end - start).coerceAtLeast(0.0001))
                        .toFloat().coerceIn(0f, 1f)
                    return Triple(index, index + 1, progress)
                }
            }
            return Triple(0, 0, 0f)
        }

        private fun mapOffsetProgress(progress: Float): Float {
            val p = progress.coerceIn(0f, 1f)
            if (!appState.optBoolean("offsetEasingEnabled")) return p
            val zone = appState.optInt("offsetEasingLinearZonePercent", 60).coerceIn(0, 100) / 100f
            val strength = appState.optInt("offsetEasingStrength", 50).coerceIn(0, 100) / 100f
            if (strength <= 0f) return p
            fun blend(value: Float): Float {
                val eased = 2f * value * value - value * value * value
                return value * (1f - strength) + eased * strength
            }
            return if (appState.optString("offsetEasingStyle") == "FAST_START_SLOW_END") {
                val easedZone = 1f - zone
                if (easedZone <= 0f || p <= zone) p
                else 1f - easedZone * blend((1f - p) / easedZone)
            } else {
                val edge = (1f - zone) / 2f
                when {
                    edge <= 0f -> p
                    p < edge -> edge * blend(p / edge)
                    p > 1f - edge -> 1f - edge * blend((1f - p) / edge)
                    else -> p
                }
            }
        }

        private fun startResumeFade() {
            fadeAnimator?.cancel()
            resumeFade = if (appState.optBoolean("resumeFadeEnabled")) 0f else 1f
            if (resumeFade >= 1f) return
            fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250L
                interpolator = LinearInterpolator()
                addUpdateListener {
                    resumeFade = it.animatedValue as Float
                    drawFrame()
                }
                start()
            }
        }

        private fun offsetAnimationNeeded(): Boolean = abs(currentOffset - renderedOffset) > OFFSET_EPSILON

        private fun scheduleOffsetAnimation() {
            if (!isVisibleNow || animationPosted) return
            updateSurfaceFrameRate()
            animationPosted = true
            choreographer.postFrameCallback(frameCallback)
        }

        private fun stopOffsetAnimation() {
            if (animationPosted) choreographer.removeFrameCallback(frameCallback)
            animationPosted = false
            lastAnimationFrameNanos = 0L
            nextFrameDeadlineNanos = 0L
            setSurfaceFrameRate(null)
        }

        private fun drawSmoothedFrame(frameTimeNanos: Long) {
            if (!isVisibleNow) {
                stopOffsetAnimation()
                return
            }
            val frameInterval = 1_000_000_000L / appState.optInt("animationFrameRate", 60).coerceIn(30, 165)
            if (nextFrameDeadlineNanos == 0L) nextFrameDeadlineNanos = frameTimeNanos
            if (frameTimeNanos < nextFrameDeadlineNanos) {
                scheduleOffsetAnimation()
                return
            }
            val elapsed = if (lastAnimationFrameNanos == 0L) frameInterval
            else (frameTimeNanos - lastAnimationFrameNanos).coerceAtLeast(1L)
            val smoothingNanos = maxOf(frameInterval, elapsed) * 1.5
            val fraction = (1.0 - exp(-elapsed.toDouble() / smoothingNanos)).toFloat()
            renderedOffset += (currentOffset - renderedOffset) * fraction
            if (!offsetAnimationNeeded()) renderedOffset = currentOffset
            drawFrame(fromOffsetUpdate = true)
            lastAnimationFrameNanos = frameTimeNanos
            val intervals = (frameTimeNanos - nextFrameDeadlineNanos) / frameInterval + 1L
            nextFrameDeadlineNanos += intervals * frameInterval
            if (offsetAnimationNeeded()) scheduleOffsetAnimation() else stopOffsetAnimation()
        }

        private fun updateSurfaceFrameRate() {
            setSurfaceFrameRate(
                if (isVisibleNow && offsetAnimationNeeded()) appState.optInt("animationFrameRate", 60) else null,
            )
        }

        private fun setSurfaceFrameRate(frameRate: Int?) {
            val surface = surfaceHolder?.surface ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
            val requested = frameRate ?: 0
            if (surfaceFrameRate == requested) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    requested.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                surface.setFrameRate(requested.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            surfaceFrameRate = requested
        }

        private fun drawDebug(canvas: Canvas) {
            if (appState.optBoolean("showOffsetDebugOverlay")) {
                val density = resources.displayMetrics.density
                val width = 240f * density
                val height = 82f * density
                val left = (canvas.width - width) / 2f
                val top = 20f * density
                val bounds = RectF(left, top, left + width, top + height)
                labelPaint.textSize = 11f * density
                trackPaint.strokeWidth = 3f * density
                canvas.drawRoundRect(bounds, 12f * density, 12f * density, overlayPaint)
                canvas.drawText("Launcher offset received", canvas.width / 2f, top + 19f * density, labelPaint)
                val start = left + 42f * density
                val end = left + width - 16f * density
                val yX = top + 42f * density
                val yY = top + 64f * density
                canvas.drawText("X", left + 16f * density, yX + 4f * density, labelPaint.apply { textAlign = Paint.Align.LEFT })
                canvas.drawText("Y", left + 16f * density, yY + 4f * density, labelPaint)
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawLine(start, yX, end, yX, trackPaint)
                canvas.drawLine(start, yY, end, yY, trackPaint)
                receivedXOffset?.let { canvas.drawCircle(start + (end - start) * it.coerceIn(0f, 1f), yX, 5f * density, xDotPaint) }
                receivedYOffset?.let { canvas.drawCircle(start + (end - start) * it.coerceIn(0f, 1f), yY, 5f * density, yDotPaint) }
            }
            if (appState.optBoolean("showFrameDebugIndicator") && isVisibleNow) {
                val density = resources.displayMetrics.density
                val size = 28f * density
                val margin = 20f * density
                val left = canvas.width - size - margin
                val top = margin
                frameBorderPaint.strokeWidth = 2f * density
                canvas.drawRect(left, top, left + size, top + size, frameBorderPaint)
                framePaint.color = frameColors[frameSequence % frameColors.size]
                canvas.drawRect(left + 2f * density, top + 2f * density, left + size - 2f * density, top + size - 2f * density, framePaint)
                frameSequence = (frameSequence + 1) % frameColors.size
            }
        }

        private fun isDimConditionActive(): Boolean {
            val darken = appState.optJSONObject("darken") ?: return false
            return when (darken.optString("triggerMode", "EXTRA_DIM")) {
                "EXTRA_DIM" -> try {
                    Settings.Secure.getInt(contentResolver, REDUCE_BRIGHT_COLORS_ACTIVATED, 0) == 1
                } catch (error: SecurityException) {
                    Log.w(TAG, "Could not read Android Extra Dim state.", error)
                    false
                }
                "DARK_THEME" -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                "SCHEDULED" -> isScheduled(darken)
                "ALWAYS" -> true
                else -> false
            }
        }

        private fun isScheduled(config: JSONObject): Boolean {
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val start = config.optInt("scheduleStartHour", 20) * 60 + config.optInt("scheduleStartMinute", 0)
            val end = config.optInt("scheduleEndHour", 7) * 60 + config.optInt("scheduleEndMinute", 0)
            return if (start < end) minute in start until end else minute >= start || minute < end
        }

        private fun homeOffset(): Float =
            appState.optJSONObject("calibration")?.let { calibration ->
                val index = calibration.optInt("homePageIndex", 1)
                (calibration.optJSONArray("customOffsets")?.optDouble(index) ?: -1.0)
                    .takeIf { it in 0.0..1.0 }?.toFloat()
                    ?: index.toFloat() / (pageCount - 1).coerceAtLeast(1)
            } ?: 0.5f

        private fun pageHomeIndex(): Int =
            appState.optJSONObject("calibration")?.optInt("homePageIndex", 1) ?: 1
    }

    companion object {
        const val ACTION_CONFIGURATION_CHANGED = "dev.pagedwallpaper.rust.CONFIGURATION_CHANGED"
        private const val REDUCE_BRIGHT_COLORS_ACTIVATED = "reduce_bright_colors_activated"
        private const val OFFSET_EPSILON = 0.00005f
        private const val SCHEDULE_CHECK_INTERVAL_MS = 60_000L
        private const val TAG = "PagedWallpaperRust"
    }
}
