package rs.ruffle

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import java.io.File
import java.io.IOException

class PlayerActivity : GameActivity() {
    @Suppress("unused")
    // Used by Rust
    private val swfBytes: ByteArray?
        get() {
            val uri = intent.data ?: return null
            return try {
                when (uri.scheme) {
                    "file" -> {
                        val path = uri.path ?: return null
                        File(path).readBytes()
                    }
                    null, "content", "android.resource", "asset" ->
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    else -> null
                }
            } catch (e: IOException) {
                Log.e("ruffle", "Could not read ${uri}", e)
                null
            }
        }

    @Suppress("unused")
    // Used by Rust
    private val swfUri: String?
        get() {
            return intent.dataString
        }

    @Suppress("unused")
    // Used by Rust
    private val traceOutput: String?
        get() {
            return intent.getStringExtra("traceOutput")
        }

    @Suppress("unused")
    // Used by Rust
    private fun navigateToUrl(url: String?) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private var loc = IntArray(2)

    @Suppress("unused")
    // Handle of an EventLoopProxy over in rust-land
    private val eventLoopHandle: Long = 0

    @Suppress("unused")
    // Used by Rust
    private val locInWindow: IntArray
        get() {
            mSurfaceView.getLocationInWindow(loc)
            return loc
        }

    @Suppress("unused")
    // Used by Rust
    private val surfaceWidth: Int
        get() = mSurfaceView.width

    @Suppress("unused")
    // Used by Rust
    private val surfaceHeight: Int
        get() = mSurfaceView.height

    private external fun keydown(keyTag: String)
    private external fun keyup(keyTag: String)
    private external fun requestContextMenu()
    private external fun runContextMenuCallback(index: Int)
    private external fun clearContextMenu()

    @Suppress("unused")
    // Used by Rust
    private fun showContextMenu(items: Array<String>) {
        runOnUiThread {
            val popup = PopupMenu(this, findViewById(R.id.button_cm))
            val menu = popup.menu
            if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
                menu.setGroupDividerEnabled(true)
            }
            var group = 1
            for (i in items.indices) {
                val elements = items[i].split(" ".toRegex(), limit = 4).toTypedArray()
                val enabled = elements[0].toBoolean()
                val separatorBefore = elements[1].toBoolean()
                val checked = elements[2].toBoolean()
                val caption = elements[3]
                if (separatorBefore) group += 1
                val item = menu.add(group, i, Menu.NONE, caption)
                item.setEnabled(enabled)
                if (checked) {
                    item.setCheckable(true)
                    item.setChecked(true)
                }
            }
            val exitItemId: Int = items.size
            menu.add(group, exitItemId, Menu.NONE, "Exit")
            popup.setOnMenuItemClickListener { item: MenuItem ->
                if (item.itemId == exitItemId) {
                    finish()
                } else {
                    runContextMenuCallback(item.itemId)
                }
                true
            }
            popup.setOnDismissListener { clearContextMenu() }
            popup.show()
        }
    }

    @Suppress("unused")
    // Used by Rust
    private fun getAndroidDataStorageDir(): String {
        // TODO It can also be placed in an external storage path in the future to share archived content
        val storageDirPath = "${filesDir.absolutePath}/ruffle/shared_objects"
        val storageDir = File(storageDirPath)
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return storageDirPath
    }

    private var gamepad: GamepadOverlay? = null
    private var padConfigVisible = false

    /** Stable name of the currently loaded SWF, used to key per-game settings. */
    private val gameKey: String
        get() {
            val uri = intent.data ?: return "default"
            return uri.lastPathSegment?.substringAfterLast('/')?.ifEmpty { null } ?: "default"
        }

    private fun restorePadConfig(overlay: GamepadOverlay) {
        val config = PadConfigStore.load(this, gameKey)
        if (config != null) {
            overlay.setPads(config.pads)
            overlay.opacity = config.opacity
            overlay.scale = config.scale
            padConfigVisible = config.visible
        }
    }

    private fun savePadConfig() {
        val overlay = gamepad ?: return
        PadConfigStore.save(
            this,
            gameKey,
            PadConfigStore.Config(
                overlay.padsSnapshot(),
                overlay.opacity,
                overlay.scale,
                overlay.visibility == View.VISIBLE,
            ),
        )
    }

    private fun showPadSettings(overlay: GamepadOverlay) {
        val density = resources.displayMetrics.density
        val oldOpacity = overlay.opacity
        val oldScale = overlay.scale

        fun caption(text: String) = TextView(this).apply {
            setText(text)
            textSize = 14f
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), 0)
            addView(caption("不透明度"))
            addView(
                SeekBar(this@PlayerActivity).apply {
                    max = 85
                    progress = ((oldOpacity - 0.15f) * 100f).toInt().coerceIn(0, 85)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                            overlay.opacity = 0.15f + value / 100f
                        }

                        override fun onStartTrackingTouch(bar: SeekBar?) {}
                        override fun onStopTrackingTouch(bar: SeekBar?) {}
                    })
                },
            )
            addView(caption("按键大小"))
            addView(
                SeekBar(this@PlayerActivity).apply {
                    max = 140
                    progress = ((oldScale - 0.6f) * 100f).toInt().coerceIn(0, 140)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                            overlay.scale = 0.6f + value / 100f
                        }

                        override fun onStartTrackingTouch(bar: SeekBar?) {}
                        override fun onStopTrackingTouch(bar: SeekBar?) {}
                    })
                },
            )
        }

        AlertDialog.Builder(this)
            .setTitle("手柄外观")
            .setView(content)
            .setPositiveButton("确定") { _, _ -> savePadConfig() }
            .setNegativeButton("取消") { _, _ ->
                overlay.opacity = oldOpacity
                overlay.scale = oldScale
            }
            .show()
    }

    private fun showTagPicker(pad: GamepadOverlay.Pad) {
        val tags = TAG_CHOICES
        val symbols = mapOf(
            "LEFT" to "◀", "RIGHT" to "▶", "UP" to "▲", "DOWN" to "▼",
            "ENTER" to "⏎", "ESC" to "ESC", "SPACE" to "␣", "TAB" to "TAB",
            "SHIFT" to "⇧", "CTRL" to "CTRL", "ALT" to "ALT", "BACKSPACE" to "⌫",
        )
        val labels = tags.map { symbols[it] ?: it }
        AlertDialog.Builder(this)
            .setTitle("选择按键")
            .setItems(labels.toTypedArray()) { _, which ->
                pad.tag = tags[which]
                pad.label = labels[which]
                gamepad?.setPads(gamepad!!.padsSnapshot())
                savePadConfig()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onCreateSurfaceView() {
        val inflater = layoutInflater

        @SuppressLint("InflateParams")
        val layout = inflater.inflate(R.layout.keyboard, null) as ConstraintLayout

        contentViewId = View.generateViewId()
        layout.id = contentViewId
        setContentView(layout)
        mSurfaceView = InputEnabledSurfaceView(this)

        mSurfaceView.contentDescription = "Ruffle Player"

        val placeholder = findViewById<View>(R.id.placeholder)
        val pars = placeholder.layoutParams as ConstraintLayout.LayoutParams
        val parent = placeholder.parent as ViewGroup
        val index = parent.indexOfChild(placeholder)
        parent.removeView(placeholder)
        parent.addView(mSurfaceView, index)
        mSurfaceView.setLayoutParams(pars)
        val keys = gatherAllDescendantsOfType<Button>(
            layout.findViewById(R.id.keyboard),
            Button::class.java
        )
        for (b in keys) {
            b.setOnTouchListener { view: View, motionEvent: MotionEvent ->
                val tag = view.tag as String
                if (motionEvent.action == MotionEvent.ACTION_DOWN) keydown(tag)
                if (motionEvent.action == MotionEvent.ACTION_UP) keyup(tag)
                view.performClick()
                false
            }
        }
        layout.findViewById<View>(R.id.button_kb).setOnClickListener {
            val keyboard = layout.findViewById<View>(R.id.keyboard)
            if (keyboard.visibility == View.VISIBLE) {
                keyboard.visibility = View.GONE
            } else {
                keyboard.visibility = View.VISIBLE
            }
        }
        layout.findViewById<View>(R.id.button_cm)
            .setOnClickListener { requestContextMenu() }

        val overlay = GamepadOverlay(
            this,
            onKeyDown = { keydown(it) },
            onKeyUp = { keyup(it) },
            onChanged = { savePadConfig() },
            onPickTag = { pad -> showTagPicker(pad) },
        )
        gamepad = overlay
        val overlayParams = ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        // Below the toolbar so the toolbar stays clickable and on top.
        layout.addView(overlay, layout.indexOfChild(layout.findViewById(R.id.toolbar)), overlayParams)
        restorePadConfig(overlay)
        overlay.visibility = if (padConfigVisible) View.VISIBLE else View.GONE

        layout.findViewById<View>(R.id.button_pad).apply {
            setOnClickListener {
                val next = if (overlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                overlay.visibility = next
                if (next == View.VISIBLE) overlay.editMode = false
                savePadConfig()
            }
            setOnLongClickListener {
                showPadSettings(overlay)
                true
            }
        }
        layout.findViewById<View>(R.id.button_edit).setOnClickListener {
            if (overlay.visibility != View.VISIBLE) {
                overlay.visibility = View.VISIBLE
            }
            overlay.editMode = !overlay.editMode
            Toast.makeText(
                this,
                if (overlay.editMode) "拖动圆点调整位置，双击圆点改键位" else "已退出编辑",
                Toast.LENGTH_SHORT,
            ).show()
        }

        layout.requestLayout()
        layout.requestFocus()
        mSurfaceView.holder.addCallback(this)
        ViewCompat.setOnApplyWindowInsetsListener(mSurfaceView, this)
        keepControlsAboveSystemBars(layout)
    }

    /**
     * The activity renders edge-to-edge (see setDecorFitsSystemWindows), so the stage
     * extends behind the navigation bar. The floating controls must not: on devices
     * without hardware keys the bar covers the bottom ~48dp, which would hide the
     * toolbar buttons and put gamepad pads under a window that eats their taps.
     *
     * Insets are only available once the layout is attached, so listen for them instead
     * of measuring here; every dispatch (rotation included) refreshes the margins.
     */
    private fun keepControlsAboveSystemBars(layout: ConstraintLayout) {
        val density = resources.displayMetrics.density
        val toolbarBase = (4 * density).toInt()
        val keyboardBase = (56 * density).toInt()

        fun bottomMargin(view: View?, base: Int, inset: Int) {
            val lp = view?.layoutParams as? ConstraintLayout.LayoutParams ?: return
            val margin = base + inset
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin
                view.layoutParams = lp
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(layout) { _, insets ->
            // Immersive mode reports zero bar insets even though WindowManager still
            // reserves the bar strip (content insets 0,48,0,96 on the SHIELD K1).
            // Fall back to the framework's bar dimension so the controls never end
            // up under the bar on ROMs that keep it drawn.
            val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                .takeIf { it > 0 } ?: systemNavigationBarHeight()
            bottomMargin(findViewById(R.id.toolbar), toolbarBase, nav)
            bottomMargin(findViewById(R.id.keyboard), keyboardBase, nav)
            bottomMargin(gamepad, 0, nav)
            Log.i("ruffle", "system bar inset bottom=$nav, layout=${layout.height}")
            insets
        }
        ViewCompat.requestApplyInsets(layout)
    }

    /** Height of the on-screen navigation bar, 0 when the device has hardware keys. */
    private fun systemNavigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Layouts are driven by the user; nothing is forced here on rotation.
        findViewById<View>(R.id.topLevel)?.requestLayout()
    }

    private fun hideSystemUI() {
        // This will put the game behind any cutouts and waterfalls on devices which have
        // them, so the corresponding insets will be non-zero.
        if (Build.VERSION.SDK_INT >= VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // From API 30 onwards, this is the recommended way to hide the system UI, rather than
        // using View.setSystemUiVisibility.
        val decorView = window.decorView
        val controller = WindowInsetsControllerCompat(
            window,
            decorView
        )
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.hide(WindowInsetsCompat.Type.displayCutout())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be requested before any decor view exists: setDecorFitsSystemWindows
        // and hideSystemUI both touch window.decorView, and Android then refuses
        // requestFeature() ("must be called before adding content").
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        nativeInit { message ->
            Log.e("ruffle", "Handling panic: $message")
            startActivity(
                Intent(this, PanicActivity::class.java).apply {
                    putExtra("message", message)
                }
            )
        }
        // When true, the app will fit inside any system UI windows.
        // When false, we render behind any system UI windows.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // You can set IME fields here or in native code using GameActivity_setImeEditorInfoFields.
        // We set the fields in native_engine.cpp.
        // super.setImeEditorInfoFields(InputType.TYPE_CLASS_TEXT,
        //     IME_ACTION_NONE, IME_FLAG_NO_FULLSCREEN );
        super.onCreate(savedInstanceState)
        // The surface view only exists after GameActivity has set up its content.
        hideSystemUI()
        supportActionBar?.hide()
    }

    // Used by Rust
    @Suppress("unused")
    val isGooglePlayGames: Boolean
        get() {
            val pm = packageManager
            return pm.hasSystemFeature("com.google.android.play.feature.HPE_EXPERIENCE")
        }

    companion object {
        init {
            // load the native activity
            System.loadLibrary("ruffle_android")
        }

        /** Tags understood by src/keycodes.rs, offered by the pad key picker. */
        private val TAG_CHOICES: List<String> = buildList {
            addAll(
                listOf(
                    "ENTER", "ESC", "SPACE", "TAB", "SHIFT", "CTRL", "ALT", "BACKSPACE",
                    "LEFT", "UP", "DOWN", "RIGHT",
                    "HOME", "END", "PAGEUP", "PAGEDOWN", "INSERT", "DELETE",
                )
            )
            addAll(('0'..'9').map(Char::toString))
            addAll(('A'..'Z').map(Char::toString))
        }

        @JvmStatic
        private external fun nativeInit(crashCallback: CrashCallback)

        private fun <T> gatherAllDescendantsOfType(v: View, t: Class<*>): List<T> {
            val result: MutableList<T> = ArrayList()
            @Suppress("UNCHECKED_CAST")
            if (t.isInstance(v)) result.add(v as T)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    result.addAll(gatherAllDescendantsOfType(v.getChildAt(i), t))
                }
            }
            return result
        }
    }

    fun interface CrashCallback {
        fun onCrash(message: String)
    }
}
