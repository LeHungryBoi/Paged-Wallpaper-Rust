package dev.pagedwallpaper.rust

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal object WallpaperDiagnosticsRecorder {
    private const val PREFERENCES = "paged_wallpaper_diagnostics"
    private const val HISTORY_KEY = "history"
    private const val MAX_HISTORY = 10
    private const val MAX_INTERVAL_NANOS = 500_000_000L
    private const val OFFSET_EPSILON = 0.0001f

    private val lock = Any()
    private var session: Session? = null
    private var wallpaperVisible = false

    fun isRecording(): Boolean = synchronized(lock) { session != null }

    fun start() {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtimeNanos()
            session = Session(
                startNanos = now,
                startedAtMillis = System.currentTimeMillis(),
                visibleSinceNanos = if (wallpaperVisible) now else null,
            )
        }
    }

    fun stop(context: Context): String? {
        synchronized(lock) {
            val current = session ?: return null
            val result = current.snapshot(SystemClock.elapsedRealtimeNanos())
            session = null
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val oldHistory = loadHistory(preferences.getString(HISTORY_KEY, null))
            val history = JSONArray().apply {
                put(result)
                for (index in 0 until oldHistory.length().coerceAtMost(MAX_HISTORY - 1)) {
                    put(oldHistory.getJSONObject(index))
                }
            }
            preferences.edit().putString(HISTORY_KEY, history.toString()).apply()
            return result.optString("summary")
        }
    }

    fun summary(context: Context): String = synchronized(lock) {
        val current = session?.snapshot(SystemClock.elapsedRealtimeNanos())
        val history = loadHistory(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(HISTORY_KEY, null),
        )
        buildString {
            if (current != null) {
                append("Recording · ")
                append(current.optString("summary"))
            } else if (history.length() > 0) {
                append("Last recording · ")
                append(history.getJSONObject(0).optString("summary"))
            } else {
                append("No recordings yet.")
            }
            if (history.length() > 1) {
                append("\nSaved recordings: ")
                append(history.length())
            }
        }
    }

    fun visibilityChanged(visible: Boolean) {
        synchronized(lock) {
            wallpaperVisible = visible
            session?.visibilityChanged(visible, SystemClock.elapsedRealtimeNanos())
        }
    }

    fun offsetChanged(offset: Float, visible: Boolean) {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtimeNanos()
            session?.offsetChanged(offset, visible, now)
        }
    }

    fun frameSubmitted(fromOffset: Boolean, visible: Boolean) {
        synchronized(lock) {
            session?.frameSubmitted(fromOffset, visible, SystemClock.elapsedRealtimeNanos())
        }
    }

    private fun loadHistory(encoded: String?): JSONArray {
        if (encoded.isNullOrBlank()) return JSONArray()
        return try {
            JSONArray(encoded)
        } catch (_: org.json.JSONException) {
            JSONArray()
        }
    }

    private class Session(
        private val startNanos: Long,
        private val startedAtMillis: Long,
        visibleSinceNanos: Long?,
    ) {
        private var isVisible = visibleSinceNanos != null
        private var visibleSince = visibleSinceNanos
        private var visibleDuration = 0L
        private var lastOffset: Float? = null
        private var lastChangedOffsetNanos: Long? = null
        private var lastOffsetFrameNanos: Long? = null
        private var offsetCallbacks = 0
        private var changedOffsets = 0
        private var movingOffsetIntervals = 0
        private var movingOffsetDuration = 0L
        private var submittedFrames = 0
        private var offsetFrames = 0
        private var movingFrameIntervals = 0
        private var movingFrameDuration = 0L

        fun visibilityChanged(visible: Boolean, now: Long) {
            if (visible == isVisible) return
            if (visible) {
                visibleSince = now
            } else {
                visibleSince?.let { visibleDuration += (now - it).coerceAtLeast(0L) }
                visibleSince = null
            }
            isVisible = visible
            lastOffset = null
            lastChangedOffsetNanos = null
            lastOffsetFrameNanos = null
        }

        fun offsetChanged(offset: Float, visible: Boolean, now: Long) {
            visibilityChanged(visible, now)
            if (!visible || !offset.isFinite()) return
            offsetCallbacks++
            val previous = lastOffset
            if (previous != null && abs(offset - previous) > OFFSET_EPSILON) {
                changedOffsets++
                lastChangedOffsetNanos?.let { last ->
                    val interval = now - last
                    if (interval in 1..MAX_INTERVAL_NANOS) {
                        movingOffsetIntervals++
                        movingOffsetDuration += interval
                    }
                }
                lastChangedOffsetNanos = now
            }
            lastOffset = offset
        }

        fun frameSubmitted(fromOffset: Boolean, visible: Boolean, now: Long) {
            visibilityChanged(visible, now)
            if (!visible) return
            submittedFrames++
            if (!fromOffset) return
            offsetFrames++
            lastOffsetFrameNanos?.let { last ->
                val interval = now - last
                if (interval in 1..MAX_INTERVAL_NANOS) {
                    movingFrameIntervals++
                    movingFrameDuration += interval
                }
            }
            lastOffsetFrameNanos = now
        }

        fun snapshot(now: Long): JSONObject {
            val visibleTime = visibleDuration +
                (visibleSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L)
            val duration = (now - startNanos).coerceAtLeast(0L)
            val callbackRate = rate(offsetCallbacks, visibleTime)
            val frameRate = rate(submittedFrames, visibleTime)
            val movingOffsetRate = rateOrNull(movingOffsetIntervals, movingOffsetDuration)
            val movingFrameRate = rateOrNull(movingFrameIntervals, movingFrameDuration)
            return JSONObject()
                .put("recordedAtMillis", startedAtMillis)
                .put("durationMillis", duration / 1_000_000L)
                .put("visibleDurationNanos", visibleTime)
                .put("visibleOffsetCallbacks", offsetCallbacks)
                .put("changedOffsetCallbacks", changedOffsets)
                .put("movingOffsetHz", movingOffsetRate ?: JSONObject.NULL)
                .put("visibleFramesSubmitted", submittedFrames)
                .put("offsetDrivenFramesSubmitted", offsetFrames)
                .put("movingFrameSubmissionHz", movingFrameRate ?: JSONObject.NULL)
                .put(
                    "summary",
                    "${duration / 1_000_000_000L}s · ${callbackRate.formatRate()} callbacks/s · " +
                        "${frameRate.formatRate()} submitted frames/s · " +
                        "moving ${movingOffsetRate.formatRate()} / ${movingFrameRate.formatRate()} Hz",
                )
        }

        private fun rate(count: Int, durationNanos: Long): Float =
            if (durationNanos > 0L) count * 1_000_000_000f / durationNanos else 0f

        private fun rateOrNull(count: Int, durationNanos: Long): Float? =
            if (count > 0 && durationNanos > 0L) rate(count, durationNanos) else null

        private fun Float?.formatRate(): String =
            this?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"
    }
}
