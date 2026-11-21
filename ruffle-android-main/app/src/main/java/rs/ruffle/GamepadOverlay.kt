package rs.ruffle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.min

/**
 * Translucent on-screen gamepad floating above the Ruffle stage.
 *
 * Touches on a pad become virtual key events through [onKeyDown]/[onKeyUp]; touches
 * anywhere else fall through to the surface view underneath, so mouse-only games keep
 * working while the overlay is visible.
 */
class GamepadOverlay(
    context: Context,
    private val onKeyDown: (String) -> Unit,
    private val onKeyUp: (String) -> Unit,
    private val onChanged: () -> Unit,
    private val onPickTag: (Pad) -> Unit,
) : ViewGroup(context) {

    class Pad(
        var tag: String,
        var label: String,
        var x: Float,
        var y: Float,
        var sizeDp: Int,
    ) {
        internal var view: PadView? = null
    }

    private val pads = ArrayList<Pad>()
    private val activePointers = HashMap<Int, Pad>()

    var opacity: Float = 1f
        set(value) {
            field = value.coerceIn(0.15f, 1f)
            pads.forEach { it.view?.alpha = field }
        }

    var scale: Float = 1f
        set(value) {
            field = value.coerceIn(0.6f, 2f)
            requestLayout()
        }

    var editMode: Boolean = false
        set(value) {
            field = value
            pads.forEach {
                it.view?.editMode = value
                it.view?.invalidate()
            }
            invalidate()
        }

    private val editStart = FloatArray(2)
    private val editOrigin = FloatArray(2)
    private var editPad: Pad? = null
    private var lastTapPad: Pad? = null
    private var lastTapTime = 0L

    init {
        setWillNotDraw(false)
        setPads(defaultPads())
    }

    private fun defaultPads() = listOf(
        Pad("LEFT", "◀", 0.06f, 0.70f, 56),
        Pad("UP", "▲", 0.16f, 0.58f, 56),
        Pad("DOWN", "▼", 0.16f, 0.82f, 56),
        Pad("RIGHT", "▶", 0.26f, 0.70f, 56),
        Pad("ENTER", "A", 0.90f, 0.72f, 60),
        Pad("ESC", "B", 0.80f, 0.86f, 54),
        Pad("SHIFT", "⇧", 0.68f, 0.86f, 48),
        Pad("CTRL", "C", 0.90f, 0.56f, 48),
        Pad("TAB", "TAB", 0.74f, 0.72f, 44),
    )

    fun setPads(list: List<Pad>) {
        pads.forEach { pad -> pad.view?.let { removeView(it) } }
        pads.clear()
        list.forEach { pad ->
            val view = PadView(context, pad)
            pad.view = view
            view.alpha = opacity
            view.editMode = editMode
            pads.add(pad)
            addView(view)
        }
        requestLayout()
    }

    fun padsSnapshot(): List<Pad> = pads.map { Pad(it.tag, it.label, it.x, it.y, it.sizeDp) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
        for (pad in pads) {
            val size = padSizePx(pad)
            pad.view?.measure(
                MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (pad in pads) {
            val view = pad.view ?: continue
            val size = padSizePx(pad)
            val half = size / 2
            val cx = (pad.x * width).toInt()
            val cy = (pad.y * height).toInt()
            val left = (cx - half).coerceIn(0, (width - size).coerceAtLeast(0))
            val top = (cy - half).coerceIn(0, (height - size).coerceAtLeast(0))
            view.layout(left, top, left + size, top + size)
        }
    }

    private fun padSizePx(pad: Pad): Int =
        (pad.sizeDp * scale * resources.displayMetrics.density).toInt()

    private fun padAt(x: Float, y: Float): Pad? = pads.firstOrNull { pad ->
        val view = pad.view ?: return@firstOrNull false
        x >= view.left - view.width / 6f && x <= view.right + view.width / 6f &&
            y >= view.top - view.height / 6f && y <= view.bottom + view.height / 6f
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (editMode) return handleEdit(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = ev.actionIndex
                val pad = padAt(ev.getX(index), ev.getY(index))
                    ?: return activePointers.isNotEmpty()
                activePointers[ev.getPointerId(index)] = pad
                pad.view?.active = true
                onKeyDown(pad.tag)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val pointerId = ev.getPointerId(i)
                    val pad = activePointers[pointerId] ?: continue
                    if (padAt(ev.getX(i), ev.getY(i)) !== pad) {
                        activePointers.remove(pointerId)
                        pad.view?.active = false
                        onKeyUp(pad.tag)
                    }
                }
                return activePointers.isNotEmpty()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                activePointers.remove(ev.getPointerId(ev.actionIndex))?.let { pad ->
                    pad.view?.active = false
                    onKeyUp(pad.tag)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
                return true
            }
        }
        return activePointers.isNotEmpty()
    }

    private fun releaseAll() {
        for (pad in activePointers.values) {
            pad.view?.active = false
            onKeyUp(pad.tag)
        }
        activePointers.clear()
    }

    private fun handleEdit(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pad = padAt(ev.x, ev.y) ?: return false
                editPad = pad
                editStart[0] = ev.x
                editStart[1] = ev.y
                editOrigin[0] = pad.x
                editOrigin[1] = pad.y
                val now = SystemClock.uptimeMillis()
                if (lastTapPad === pad && now - lastTapTime < 400) {
                    lastTapPad = null
                    onPickTag(pad)
                } else {
                    lastTapPad = pad
                    lastTapTime = now
                }
                pad.view?.active = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pad = editPad ?: return false
                if (width > 0 && height > 0) {
                    pad.x = (editOrigin[0] + (ev.x - editStart[0]) / width).coerceIn(0.04f, 0.96f)
                    pad.y = (editOrigin[1] + (ev.y - editStart[1]) / height).coerceIn(0.04f, 0.96f)
                    requestLayout()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                editPad?.let {
                    it.view?.active = false
                    onChanged()
                }
                editPad = null
                return true
            }
        }
        return editPad != null
    }

    override fun onDraw(canvas: Canvas) {
        if (!editMode) return
        val density = resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 70
            style = Paint.Style.STROKE
            strokeWidth = density * 2
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        canvas.drawRect(
            density * 4,
            density * 44,
            width - density * 4,
            height - density * 4,
            paint,
        )
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.textSize = density * 14
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "拖动调整位置 · 双击按键改键位",
            width / 2f,
            density * 32,
            paint,
        )
    }

    internal class PadView(context: Context, private val pad: Pad) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x99FFFFFF.toInt()
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        var active = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        var editMode = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun onDraw(canvas: Canvas) {
            val radius = min(width, height) / 2f - ring.strokeWidth
            val cx = width / 2f
            val cy = height / 2f
            fill.color = if (active) 0xCCFF9800.toInt() else 0x66000000
            canvas.drawCircle(cx, cy, radius, fill)
            ring.color = if (editMode) 0xCCFFC107.toInt() else 0x99FFFFFF.toInt()
            canvas.drawCircle(cx, cy, radius, ring)
            text.textSize = radius * 0.8f
            val metrics = text.fontMetrics
            canvas.drawText(pad.label, cx, cy - (metrics.ascent + metrics.descent) / 2f, text)
        }
    }
}
