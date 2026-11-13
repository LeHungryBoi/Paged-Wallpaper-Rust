package dev.pagedwallpaper.rust

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var screen: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var pageScroller: ScrollView
    private var currentTab = Tab.STUDIO
    private var selectedPageForImage: Int? = null
    private var enableAutoBackupAfterFolderSelection = false
    private var autoBackupInProgress = false
    private var appState = JSONObject()
    private var originals = JSONArray()

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importImage(uri)
        }
    private val backupExporter =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) writeBackup(uri)
        }
    private val backupImporter =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) restoreBackup(uri)
        }
    private val backupFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) saveBackupFolder(uri)
            else enableAutoBackupAfterFolderSelection = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            readSnapshot(NativeCore.initialize(filesDir.absolutePath))
        } catch (error: IllegalStateException) {
            Toast.makeText(this, "Rust core failed to start: ${error.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        } catch (error: UnsatisfiedLinkError) {
            Toast.makeText(this, "Rust native library is missing: ${error.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildShell()
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun buildShell() {
        screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 249, 252))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(label("PaperPages · Rust", 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(button("Apply") { openWallpaperPicker() })
        status = label("", 13f, false).apply {
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(titleRow)
        header.addView(status)
        screen.addView(header)

        pageScroller = ScrollView(this).apply {
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        pageScroller.addView(content)
        screen.addView(pageScroller, LinearLayout.LayoutParams(-1, 0, 1f))

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        Tab.entries.forEach { tab ->
            val item = button(tab.title) {
                currentTab = tab
                renderScreen()
            }
            navigation.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        screen.addView(navigation)
        setContentView(screen)
    }

    private fun refreshState() {
        try {
            readSnapshot(NativeCore.state())
            if (::content.isInitialized) renderScreen()
        } catch (error: IllegalStateException) {
            showMessage("Could not load Rust state: ${error.message}")
        }
    }

    private fun readSnapshot(encoded: String) {
        val snapshot = JSONObject(encoded)
        appState = snapshot.getJSONObject("app")
        originals = snapshot.optJSONArray("originals") ?: JSONArray()
        val active = WallpaperManager.getInstance(this).wallpaperInfo?.serviceInfo?.name ==
            PagedWallpaperService::class.java.name
        if (::status.isInitialized) {
            status.text = if (active) "Live wallpaper is active" else "Choose an image and apply the live wallpaper"
        }
    }

    private fun renderScreen() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        when (currentTab) {
            Tab.STUDIO -> renderStudio()
            Tab.EFFECTS -> renderEffects()
            Tab.LIBRARY -> renderLibrary()
            Tab.SETTINGS -> renderSettings()
            Tab.BACKUP -> renderBackup()
        }
        pageScroller.scrollTo(0, 0)
    }

    private fun renderStudio() {
        section("Presets", "Presets save page assignments, crops, and page transitions together.")
        val presets = appState.optJSONArray("presets") ?: JSONArray()
        val presetNames = (0 until presets.length()).map { index ->
            val preset = presets.getJSONObject(index)
            "${preset.optString("name", "Preset")}${if (preset.optString("id") == appState.optString("activePresetId")) "  ✓" else ""}"
        }
        if (presetNames.isNotEmpty()) {
            spinner(
                content,
                presetNames,
                selectedIndex = (0 until presets.length()).indexOfFirst {
                    presets.getJSONObject(it).optString("id") == appState.optString("activePresetId")
                }.coerceAtLeast(0),
            ) { selected ->
                if (selected in 0 until presets.length()) {
                    runCommand(command("preset", "operation" to "select", "id" to presets.getJSONObject(selected).optString("id")))
                }
            }
        }
        actionRow(
            button("Duplicate") {
                prompt("Duplicate preset", "Copy of ${activePresetName()}") {
                    runCommand(command("preset", "operation" to "duplicate", "id" to appState.optString("activePresetId"), "name" to it))
                }
            },
            button("Rename") {
                prompt("Rename preset", activePresetName()) {
                    runCommand(command("preset", "operation" to "rename", "id" to appState.optString("activePresetId"), "name" to it))
                }
            },
            button("Delete") {
                confirm("Delete ${activePresetName()}?") {
                    runCommand(command("preset", "operation" to "delete", "id" to appState.optString("activePresetId")))
                }
            },
        )

        section("Launcher pages", "Set the number of launcher screens and designate the page used when returning home.")
        pageCountPicker()
        homePagePicker()

        val pages = appState.optJSONArray("pages") ?: JSONArray()
        for (index in 0 until pages.length()) {
            renderPageCard(pages.getJSONObject(index), index)
        }

        section("Page transitions", "Choose an animation for each boundary. Direction-specific transitions and parallax motion are supported.")
        val transitions = appState.optJSONArray("transitions") ?: JSONArray()
        for (index in 0 until transitions.length()) {
            renderTransition(index, transitions.getJSONObject(index))
        }
    }

    private fun renderPageCard(page: JSONObject, index: Int) {
        val pageIndex = page.optInt("pageIndex", index)
        val media = mediaFor(page.optString("originalId"))
        val card = columnCard()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = media?.let { thumbnail(it) }
        if (preview != null) {
            row.addView(preview, LinearLayout.LayoutParams(dp(72), dp(100)))
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        val home = pageIndex == appState.optJSONObject("calibration")?.optInt("homePageIndex", 1)
        details.addView(label("Page ${pageIndex + 1}${if (home) " · HOME" else ""}", 16f, true))
        details.addView(label(media?.let { "${it.optInt("width")} × ${it.optInt("height")}" } ?: if (page.optBoolean("isBlank")) "Blank page" else "Built-in wallpaper", 13f))
        val fill = page.optString("cropFillMode", "BLUR").lowercase(Locale.ROOT)
        details.addView(label("Fill: $fill · Parallax: ${(page.optDouble("parallaxAmount") * 100).roundToInt()}%", 12f))
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        card.addView(
            actionRow(
                button("Choose image") { openPageMediaPicker(pageIndex) },
                button("Edit page") {
                    if (media == null) openPageMediaPicker(pageIndex) else editPage(page)
                },
                button(if (home) "Home page" else "Make home") {
                    runCommand(command("setHomePage", "index" to pageIndex))
                },
                button(if (page.optBoolean("isBlank")) "Use sample" else "Blank") {
                    val updated = JSONObject(page.toString())
                        .put("isBlank", !page.optBoolean("isBlank"))
                    runCommand(command("updatePage", "pageIndex" to pageIndex, "page" to updated))
                },
            ),
        )
        content.addView(card)
    }

    private fun renderTransition(index: Int, setting: JSONObject) {
        val card = columnCard()
        card.addView(label("Page ${index + 1} ↔ ${index + 2}", 15f, true))
        val separate = setting.optBoolean("separateDirections")
        card.addView(toggle("Separate directions", separate) { checked ->
            val updated = JSONObject(setting.toString()).put("separateDirections", checked)
            if (checked) {
                updated.put("leftTransitionId", setting.optString("universalTransitionId", "scroll"))
                    .put("rightTransitionId", setting.optString("universalTransitionId", "scroll"))
                updated.put("leftParallaxMotion", JSONObject(setting.optJSONObject("universalParallaxMotion")?.toString() ?: "{}"))
                    .put("rightParallaxMotion", JSONObject(setting.optJSONObject("universalParallaxMotion")?.toString() ?: "{}"))
            }
            updateBoundary(index, updated)
        })
        if (separate) {
            transitionSpinner(index, setting, "rightTransitionId", "Next page")
            transitionSpinner(index, setting, "leftTransitionId", "Previous page")
            renderParallaxMotion(index, setting, "right")
            renderParallaxMotion(index, setting, "left")
        } else {
            transitionSpinner(index, setting, "universalTransitionId", "Both directions")
            renderParallaxMotion(index, setting, "universal")
        }
        content.addView(card)
    }

    private fun transitionSpinner(index: Int, setting: JSONObject, key: String, title: String) {
        content.addView(label(title, 13f, true))
        val ids = listOf("scroll", "parallax", "parallax_classic", "fade", "soft_blur", "depth", "slide_over", "cube")
        val names = listOf("Classic Scroll", "Parallax Scroll", "Classic Parallax", "Cross Fade", "Soft Blur Fade", "Depth & Scale", "Slide Over & Shadow", "3D Cube Turn")
        spinner(content, names, ids.indexOf(setting.optString(key)).coerceAtLeast(0)) { selected ->
            updateBoundary(index, JSONObject(setting.toString()).put(key, ids[selected]))
        }
    }

    private fun renderParallaxMotion(index: Int, setting: JSONObject, direction: String) {
        val transitionKey = when (direction) {
            "left" -> "leftTransitionId"
            "right" -> "rightTransitionId"
            else -> "universalTransitionId"
        }
        if (setting.optString(transitionKey) !in setOf("parallax", "parallax_classic")) return
        val motionKey = "${direction}ParallaxMotion"
        val motion = setting.optJSONObject(motionKey) ?: JSONObject()
        val enabledKey = "slowImageMotionEnabled"
        content.addView(toggle("Slow image motion · $direction", motion.optBoolean(enabledKey)) { enabled ->
            updateBoundary(index, JSONObject(setting.toString()).put(motionKey, JSONObject(motion.toString()).put(enabledKey, enabled)))
        })
        if (motion.optBoolean(enabledKey)) {
            slider(
                content,
                "Image speed · ${direction}",
                (motion.optDouble("imageSpeed", 0.5) * 100).roundToInt(),
                0,
                100,
            ) { speed ->
                updateBoundary(index, JSONObject(setting.toString()).put(motionKey, JSONObject(motion.toString()).put("imageSpeed", speed / 100f)))
            }
        }
    }

    private fun updateBoundary(index: Int, setting: JSONObject) {
        runCommand(command("setBoundary", "index" to index, "value" to setting))
    }

    private fun renderEffects() {
        section("Motion", "Controls are applied by the Rust rendering engine while launcher pages move.")
        addSettingSwitch("Motion blur", "motionBlurEnabled")
        slider(
            content,
            "Motion blur strength",
            appState.optInt("motionBlurStrengthPercent", 100),
            0,
            200,
        ) { setSetting("motionBlurStrengthPercent", it) }
        addSettingSwitch("Offset easing", "offsetEasingEnabled")
        spinner(
            content,
            listOf("Symmetric ease in/out", "Fast start, slow end"),
            if (appState.optString("offsetEasingStyle") == "FAST_START_SLOW_END") 1 else 0,
        ) { setSetting("offsetEasingStyle", if (it == 1) "FAST_START_SLOW_END" else "SYMMETRIC") }
        slider(content, "Linear zone", appState.optInt("offsetEasingLinearZonePercent", 60), 0, 100) {
            setSetting("offsetEasingLinearZonePercent", it)
        }
        slider(content, "Easing strength", appState.optInt("offsetEasingStrength", 50), 0, 100) {
            setSetting("offsetEasingStrength", it)
        }
        addSettingSwitch("Fade in when returning from apps", "resumeFadeEnabled")
        spinner(
            content,
            listOf("60 FPS", "90 FPS", "120 FPS", "144 FPS", "165 FPS"),
            listOf(60, 90, 120, 144, 165).indexOf(appState.optInt("animationFrameRate", 60)).coerceAtLeast(0),
        ) { setSetting("animationFrameRate", listOf(60, 90, 120, 144, 165)[it]) }

        section("Wallpaper darkening", "Darkening can follow Extra Dim, the system dark theme, a schedule, or stay enabled.")
        val darken = appState.optJSONObject("darken") ?: JSONObject()
        val triggers = listOf("OFF", "EXTRA_DIM", "DARK_THEME", "SCHEDULED", "ALWAYS")
        spinner(content, listOf("Disabled", "Android Extra Dim", "System dark theme", "Scheduled hours", "Always"), triggers.indexOf(darken.optString("triggerMode", "EXTRA_DIM")).coerceAtLeast(0)) {
            updateDarken(JSONObject(darken.toString()).put("triggerMode", triggers[it]))
        }
        slider(content, "Dim overlay intensity", (darken.optDouble("dimFactor", 0.4) * 100).roundToInt(), 10, 90) {
            updateDarken(JSONObject(darken.toString()).put("dimFactor", it / 100f))
        }
        slider(content, "Dark-image threshold for new imports", (darken.optDouble("luminanceThreshold", 0.35) * 100).roundToInt(), 0, 100) {
            updateDarken(JSONObject(darken.toString()).put("luminanceThreshold", it / 100f))
        }
        content.addView(toggle("Avoid dimming images detected as dark", darken.optBoolean("autoDetectDark", true)) {
            updateDarken(JSONObject(darken.toString()).put("autoDetectDark", it))
        })
        if (darken.optString("triggerMode") == "SCHEDULED") {
            slider(content, "Night starts (hour)", darken.optInt("scheduleStartHour", 20), 0, 23) {
                updateDarken(JSONObject(darken.toString()).put("scheduleStartHour", it))
            }
            slider(content, "Night starts (minute)", darken.optInt("scheduleStartMinute", 0), 0, 59) {
                updateDarken(JSONObject(darken.toString()).put("scheduleStartMinute", it))
            }
            slider(content, "Night ends (hour)", darken.optInt("scheduleEndHour", 7), 0, 23) {
                updateDarken(JSONObject(darken.toString()).put("scheduleEndHour", it))
            }
            slider(content, "Night ends (minute)", darken.optInt("scheduleEndMinute", 0), 0, 59) {
                updateDarken(JSONObject(darken.toString()).put("scheduleEndMinute", it))
            }
        }
    }

    private fun updateDarken(value: JSONObject) = setSetting("darken", value)

    private fun renderLibrary() {
        section("Media library", "Imported originals are stored on-device. Each preset page keeps its own crop, fill, and parallax settings.")
        content.addView(button("Import image") { imagePicker.launch(arrayOf("image/*")) })
        if (originals.length() == 0) {
            content.addView(label("Your library is empty.", 15f))
        }
        for (index in 0 until originals.length()) {
            val media = originals.getJSONObject(index)
            val card = columnCard()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            thumbnail(media)?.let { row.addView(it, LinearLayout.LayoutParams(dp(72), dp(72))) }
            val information = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            information.addView(label("${media.optInt("width")} × ${media.optInt("height")}", 15f, true))
            information.addView(label(if (media.optBoolean("isDarkDetected")) "Detected dark" else "Original", 12f))
            row.addView(information, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
            card.addView(
                actionRow(
                    button("Duplicate") {
                        runCommand(command("duplicateMedia", "id" to media.optString("id")))
                    },
                    button("Delete") {
                        confirm("Delete this original? Assigned pages will be cleared.") {
                            runCommand(command("removeMedia", "id" to media.optString("id")))
                        }
                    },
                ),
            )
            content.addView(card)
        }
    }

    private fun renderSettings() {
        section("Calibration", "Match wallpaper offsets to the number and order of pages in your launcher.")
        pageCountPicker()
        homePagePicker()
        val calibration = appState.optJSONObject("calibration") ?: JSONObject()
        val offsets = calibration.optJSONArray("customOffsets") ?: JSONArray()
        content.addView(button("Reset offsets to uniform spacing") {
            val count = calibration.optInt("pageCount", 3)
            val uniform = JSONArray()
            for (index in 0 until count) uniform.put(index.toFloat() / (count - 1).coerceAtLeast(1))
            runCommand(command("setOffsets", "offsets" to uniform))
        })
        for (index in 0 until offsets.length()) {
            slider(content, "Page ${index + 1} anchor", (offsets.optDouble(index) * 1000).roundToInt(), 0, 1000) { raw ->
                val changed = JSONArray()
                for (offsetIndex in 0 until offsets.length()) {
                    changed.put(if (offsetIndex == index) raw / 1000f else offsets.optDouble(offsetIndex))
                }
                runCommand(command("setOffsets", "offsets" to changed))
            }
        }

        section("Debug and diagnostics", "Record launcher callbacks and successfully submitted wallpaper frames.")
        addSettingSwitch("Show live launcher offset overlay", "showOffsetDebugOverlay")
        addSettingSwitch("Show frame sequence indicator", "showFrameDebugIndicator")
        content.addView(button(if (WallpaperDiagnosticsRecorder.isRecording()) "Stop and save recording" else "Start recording") {
            if (WallpaperDiagnosticsRecorder.isRecording()) {
                val result = WallpaperDiagnosticsRecorder.stop(this)
                showMessage("Recording saved. ${result.orEmpty()}")
            } else {
                WallpaperDiagnosticsRecorder.start()
                showMessage("Recording started. Swipe between launcher pages, then stop it here.")
            }
            renderScreen()
        })
        content.addView(label(WallpaperDiagnosticsRecorder.summary(this), 13f))
    }

    private fun renderBackup() {
        section("Backup and restore", "The archive includes app settings, presets, offsets, original images, and rendered page files.")
        val folder = appState.optString("backupFolderPath")
        content.addView(label(if (folder.isBlank() || folder == "null") "No backup folder selected" else "Selected folder: $folder", 13f))
        content.addView(button("Select backup folder") { backupFolderPicker.launch(null) })
        content.addView(toggle("Automatic backup after changes (at most once a day)", appState.optBoolean("autoBackupEnabled")) {
            if (it && folder.isBlank()) {
                enableAutoBackupAfterFolderSelection = true
                backupFolderPicker.launch(null)
            } else {
                setSetting("autoBackupEnabled", it)
            }
        })
        actionRow(
            button("Export backup ZIP") { backupExporter.launch("paged-wallpaper-backup.zip") },
            button("Restore backup ZIP") { backupImporter.launch(arrayOf("application/zip", "application/octet-stream")) },
        )
    }

    private fun pageCountPicker() {
        val count = appState.optJSONObject("calibration")?.optInt("pageCount", 3) ?: 3
        val buttons = (2..7).map { pages ->
            button("$pages pages${if (count == pages) " ✓" else ""}") {
                runCommand(command("setPageCount", "count" to pages))
            }
        }
        content.addView(actionRow(*buttons.toTypedArray()))
    }

    private fun homePagePicker() {
        val calibration = appState.optJSONObject("calibration") ?: JSONObject()
        val count = calibration.optInt("pageCount", 3)
        val homeIndex = calibration.optInt("homePageIndex", 1)
        content.addView(label("Main home screen", 14f, true))
        content.addView(
            actionRow(
                *(0 until count).map { index ->
                    button(if (homeIndex == index) "${index + 1} · Home ✓" else "${index + 1}") {
                        runCommand(command("setHomePage", "index" to index))
                    }
                }.toTypedArray(),
            ),
        )
    }

    private fun openPageMediaPicker(pageIndex: Int) {
        selectedPageForImage = pageIndex
        val choices = (0 until originals.length()).map { index ->
            val media = originals.getJSONObject(index)
            "${media.optInt("width")} × ${media.optInt("height")}${if (media.optBoolean("isDarkDetected")) " · dark" else ""}"
        }.toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle("Choose for page ${pageIndex + 1}")
            .setItems(choices) { _, selected ->
                selectedPageForImage = null
                val media = originals.getJSONObject(selected)
                runCommand(command("assignMedia", "pageIndex" to pageIndex, "mediaId" to media.optString("id")))
            }
            .setNegativeButton("Cancel") { _, _ -> selectedPageForImage = null }
            .setNeutralButton("Import image") { _, _ -> imagePicker.launch(arrayOf("image/*")) }
            .setOnCancelListener { selectedPageForImage = null }
        if (choices.isEmpty()) builder.setMessage("The media library is empty. Import an image to continue.")
        builder.show()
    }

    private fun editPage(page: JSONObject) {
        val pageIndex = page.optInt("pageIndex")
        var fillMode = page.optString("cropFillMode", "BLUR")
        var panX = (page.optDouble("cropPanX") * 100).roundToInt()
        var panY = (page.optDouble("cropPanY") * 100).roundToInt()
        var scale = (page.optDouble("cropScale", 1.0) * 100).roundToInt()
        var blur = (page.optDouble("blurAmount", 0.65) * 100).roundToInt()
        var parallax = (page.optDouble("parallaxAmount") * 100).roundToInt()
        val dark = Switch(this).apply {
            text = "This page is already dark"
            isChecked = page.optBoolean("isAlreadyDark")
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        panel.addView(label("Fill mode", 13f, true))
        spinner(panel, listOf("Crop to fill", "Blurred fill", "Frosted fill"), listOf("CROP", "BLUR", "GLASS").indexOf(fillMode).coerceAtLeast(0)) {
            fillMode = listOf("CROP", "BLUR", "GLASS")[it]
        }
        fun editSlider(title: String, value: Int, min: Int, max: Int, update: (Int) -> Unit) {
            slider(panel, title, value, min, max, update)
        }
        editSlider("Horizontal pan", panX, -100, 100) { panX = it }
        editSlider("Vertical pan", panY, -100, 100) { panY = it }
        editSlider("Zoom", scale, 100, 300) { scale = it }
        editSlider("Background blur", blur, 0, 100) { blur = it }
        editSlider("Parallax travel", parallax, 0, 100) { parallax = it }
        panel.addView(dark)
        AlertDialog.Builder(this)
            .setTitle("Edit page ${pageIndex + 1}")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val changed = JSONObject(page.toString())
                    .put("cropFillMode", fillMode)
                    .put("cropPanX", panX / 100f)
                    .put("cropPanY", panY / 100f)
                    .put("cropScale", scale / 100f)
                    .put("blurAmount", blur / 100f)
                    .put("parallaxAmount", parallax / 100f)
                    .put("isAlreadyDark", dark.isChecked)
                runCommand(command("updatePage", "pageIndex" to pageIndex, "page" to changed))
            }
            .show()
    }

    private fun addSettingSwitch(title: String, key: String) {
        content.addView(toggle(title, appState.optBoolean(key)) { setSetting(key, it) })
    }

    private fun setSetting(key: String, value: Any) {
        runCommand(command("setSetting", "key" to key, "value" to value))
    }

    private fun runCommand(command: JSONObject): Boolean {
        try {
            readSnapshot(NativeCore.apply(command.toString()))
            sendBroadcast(Intent(PagedWallpaperService.ACTION_CONFIGURATION_CHANGED).setPackage(packageName))
            renderScreen()
            maybeAutoBackup()
            return true
        } catch (error: IllegalStateException) {
            showMessage("Could not save setting: ${error.message}")
            return false
        }
    }

    private fun importImage(uri: Uri) {
        val pendingPage = selectedPageForImage
        selectedPageForImage = null
        Thread {
            val id = UUID.randomUUID().toString()
            var destination: File? = null
            try {
                val mime = contentResolver.getType(uri).orEmpty()
                val extension = when (mime.lowercase(Locale.ROOT)) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "jpg"
                }
                val fileName = "$id.$extension"
                val imageFile = File(File(filesDir, "originals"), fileName)
                destination = imageFile
                imageFile.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    imageFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("The selected image could not be opened.")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IOException("The selected file is not a supported image.")
                }
                val sampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 300).coerceAtLeast(1)
                val sample = BitmapFactory.decodeFile(
                    imageFile.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize },
                ) ?: throw IOException("Could not decode the selected image.")
                val luminance = calculateLuminance(sample)
                sample.recycle()
                val threshold = appState.optJSONObject("darken")?.optDouble("luminanceThreshold", 0.35) ?: 0.35
                val media = JSONObject()
                    .put("id", id)
                    .put("fileName", fileName)
                    .put("dateAdded", System.currentTimeMillis())
                    .put("width", bounds.outWidth)
                    .put("height", bounds.outHeight)
                    .put("averageLuminance", luminance)
                    .put("isDarkDetected", luminance < threshold)
                runOnUiThread {
                    if (!runCommand(command("addMedia", "media" to media))) {
                        discardImportedFile(imageFile)
                        return@runOnUiThread
                    }
                    if (pendingPage != null) {
                        runCommand(command("assignMedia", "pageIndex" to pendingPage, "mediaId" to id))
                    }
                    currentTab = if (pendingPage == null) Tab.LIBRARY else Tab.STUDIO
                    renderScreen()
                }
            } catch (error: IOException) {
                discardImportedFile(destination)
                runOnUiThread { showMessage("Image import failed: ${error.message}") }
            } catch (error: SecurityException) {
                discardImportedFile(destination)
                runOnUiThread { showMessage("Could not read the selected image: ${error.message}") }
            }
        }.start()
    }

    private fun discardImportedFile(file: File?) {
        if (file != null && file.exists() && !file.delete()) {
            runOnUiThread { showMessage("The rejected image file could not be removed from app storage.") }
        }
    }

    private fun calculateLuminance(bitmap: android.graphics.Bitmap): Double {
        val stepX = (bitmap.width / 21).coerceAtLeast(1)
        val stepY = (bitmap.height / 21).coerceAtLeast(1)
        var total = 0.0
        var samples = 0
        var y = stepY
        while (y < bitmap.height) {
            var x = stepX
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                total += 0.2126 * Color.red(color) / 255.0 +
                    0.7152 * Color.green(color) / 255.0 +
                    0.0722 * Color.blue(color) / 255.0
                samples++
                x += stepX
            }
            y += stepY
        }
        return if (samples == 0) 0.5 else total / samples
    }

    private fun openWallpaperPicker() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, PagedWallpaperService::class.java),
            )
        }
        try {
            startActivity(intent)
        } catch (error: android.content.ActivityNotFoundException) {
            showMessage("No live wallpaper picker is available on this device.")
        }
    }

    private fun writeBackup(uri: Uri) {
        Thread {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    WallpaperBackup.export(this, output)
                } ?: throw IOException("Could not open the selected backup file.")
                runOnUiThread { showMessage("Backup exported.") }
            } catch (error: IOException) {
                runOnUiThread { showMessage("Backup export failed: ${error.message}") }
            } catch (error: SecurityException) {
                runOnUiThread { showMessage("Could not write the selected backup: ${error.message}") }
            }
        }.start()
    }

    private fun restoreBackup(uri: Uri) {
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    WallpaperBackup.restore(this, input)
                } ?: throw IOException("Could not open the selected backup.")
                val restored = NativeCore.reload()
                runOnUiThread {
                    readSnapshot(restored)
                    sendBroadcast(Intent(PagedWallpaperService.ACTION_CONFIGURATION_CHANGED).setPackage(packageName))
                    renderScreen()
                    showMessage("Configuration and wallpaper media restored.")
                }
            } catch (error: IOException) {
                runOnUiThread { showMessage("Restore failed: ${error.message}") }
            } catch (error: IllegalStateException) {
                runOnUiThread { showMessage("Restore failed: ${error.message}") }
            } catch (error: SecurityException) {
                runOnUiThread { showMessage("Could not read the selected backup: ${error.message}") }
            }
        }.start()
    }

    private fun saveBackupFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            setSetting("backupFolderPath", uri.toString())
            if (enableAutoBackupAfterFolderSelection) {
                enableAutoBackupAfterFolderSelection = false
                setSetting("autoBackupEnabled", true)
            }
            showMessage("Backup folder saved.")
        } catch (error: SecurityException) {
            enableAutoBackupAfterFolderSelection = false
            showMessage("Could not keep access to that folder: ${error.message}")
        }
    }

    private fun maybeAutoBackup() {
        if (!appState.optBoolean("autoBackupEnabled") || autoBackupInProgress) return
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lastBackup = preferences.getLong(AUTO_BACKUP_TIME, 0L)
        if (System.currentTimeMillis() - lastBackup < AUTO_BACKUP_INTERVAL_MS) return
        val folder = appState.optString("backupFolderPath")
        if (folder.isBlank() || folder == "null") return
        autoBackupInProgress = true
        Thread {
            var failure: IOException? = null
            try {
                WallpaperBackup.exportToTree(this, Uri.parse(folder))
            } catch (error: IOException) {
                failure = error
            }
            runOnUiThread {
                autoBackupInProgress = false
                if (failure == null) {
                    preferences.edit().putLong(AUTO_BACKUP_TIME, System.currentTimeMillis()).apply()
                } else {
                    showMessage("Automatic backup failed: ${failure?.message}")
                }
            }
        }.start()
    }

    private fun updateBoundaryPage(index: Int): JSONObject =
        appState.optJSONArray("pages")?.optJSONObject(index) ?: JSONObject()

    private fun mediaFor(id: String): JSONObject? {
        if (id.isBlank() || id == "null") return null
        for (index in 0 until originals.length()) {
            val media = originals.optJSONObject(index) ?: continue
            if (media.optString("id") == id) return media
        }
        return null
    }

    private fun thumbnail(media: JSONObject): ImageView? {
        val file = File(File(filesDir, "originals"), media.optString("fileName"))
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 240).coerceAtLeast(1)
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun activePresetName(): String {
        val presets = appState.optJSONArray("presets") ?: return "Preset"
        for (index in 0 until presets.length()) {
            val preset = presets.optJSONObject(index) ?: continue
            if (preset.optString("id") == appState.optString("activePresetId")) return preset.optString("name", "Preset")
        }
        return "Preset"
    }

    private fun command(action: String, vararg fields: Pair<String, Any?>): JSONObject =
        JSONObject().put("action", action).apply {
            fields.forEach { (key, value) -> put(key, value) }
        }

    private fun section(title: String, description: String) {
        content.addView(label(title, 20f, true).apply { setPadding(0, dp(16), 0, dp(3)) })
        content.addView(label(description, 13f).apply { setPadding(0, 0, 0, dp(8)) })
    }

    private fun columnCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }

    private fun actionRow(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEach { view ->
                addView(view, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
        }

    private fun label(text: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(28, 33, 43))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            setOnClickListener { action() }
        }

    private fun toggle(title: String, checked: Boolean, change: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = title
            isChecked = checked
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, value -> change(value) }
        }

    private fun spinner(
        parent: LinearLayout,
        values: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ): Spinner {
        val view = Spinner(this)
        view.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        view.setSelection(selectedIndex.coerceIn(0, (values.size - 1).coerceAtLeast(0)))
        var skipInitial = true
        view.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (skipInitial) {
                    skipInitial = false
                } else {
                    onSelected(position)
                }
            }
        }
        parent.addView(view)
        return view
    }

    private fun slider(
        parent: LinearLayout,
        title: String,
        value: Int,
        min: Int,
        max: Int,
        changed: (Int) -> Unit,
    ) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val caption = label("$title: $value", 13f)
        line.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
        }
        parent.addView(line)
        parent.addView(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                caption.text = "$title: ${progress + min}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                changed(seek.progress + min)
            }
        })
    }

    private fun prompt(title: String, initial: String, action: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(22), dp(10), dp(22), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> action(input.text.toString()) }
            .show()
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> action() }
            .show()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private enum class Tab(val title: String) {
        STUDIO("Studio"),
        EFFECTS("Effects"),
        LIBRARY("Library"),
        SETTINGS("Settings"),
        BACKUP("Backup"),
    }

    companion object {
        private const val PREFS = "paged_wallpaper_ui"
        private const val AUTO_BACKUP_TIME = "auto_backup_time"
        private const val AUTO_BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
