package com.genshinpad

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class ControllerView(c: Context, private val send: (Int, Int, Int, Int, Int) -> Unit) : View(c) {
    var status = "Starting..."
    var onConnectTap: () -> Unit = {}

    private class Btn(val label: String, val bit: Int, var fx: Float, var fy: Float, var fr: Float) { var down = false }
    private val catalog = listOf("A" to 0, "B" to 1, "X" to 2, "Y" to 3, "LB" to 4, "RB" to 5,
        "LT" to 6, "RT" to 7, "View" to 8, "Menu" to 9, "L3" to 10, "R3" to 11)
    private val colors = intArrayOf(Color.rgb(48, 209, 88), Color.rgb(255, 69, 58), Color.rgb(10, 132, 255),
        Color.rgb(255, 214, 10), Color.rgb(142, 142, 147), Color.rgb(142, 142, 147), Color.rgb(191, 90, 242),
        Color.rgb(10, 132, 255), Color.rgb(142, 142, 147), Color.rgb(142, 142, 147), Color.rgb(142, 142, 147), Color.rgb(142, 142, 147))
    private val tools = listOf("Done", "Add", "Smaller", "Bigger", "Delete", "Reset")

    private val prefs = c.getSharedPreferences("pad", Context.MODE_PRIVATE)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var u = 1f
    private var btns = mutableListOf<Btn>()
    private var editing = false
    private var sel: Btn? = null
    private var dragId = -1
    private val byId = HashMap<Int, Btn>()
    private var stickId = -1; private var camId = -1
    private var ox = 0f; private var oy = 0f; private var sx = 0f; private var sy = 0f
    private var lastX = 0f; private var lastY = 0f; private var accX = 0f; private var accY = 0f
    private var lx = 0; private var ly = 0; private var rx = 0; private var ry = 0
    private var lastSent = listOf(-1, 0, 0, 0, 0)
    private val connectRect = RectF()
    private val editRect = RectF()

    private fun label(bit: Int) = catalog.first { it.second == bit }.first
    private fun px(b: Btn) = b.fx * width
    private fun py(b: Btn) = b.fy * height
    private fun pr(b: Btn) = b.fr * u

    private fun defaults(w: Int, h: Int): MutableList<Btn> {
        fun b(bit: Int, x: Float, y: Float, r: Float) = Btn(label(bit), bit, x * u / w, y / 100f, r)
        return mutableListOf(
            b(7, w / u - 30, 68f, 15f), b(0, w / u - 12, 40f, 9f), b(1, w / u - 10, 88f, 8f),
            b(4, w / u - 56, 86f, 9f), b(5, w / u - 56, 56f, 9f),
            b(9, w / u / 2 + 8, 8f, 5f), b(8, w / u / 2 - 8, 8f, 5f))
    }

    private fun save() {
        prefs.edit().putString("layout", btns.joinToString(";") { "${it.bit},${it.fx},${it.fy},${it.fr}" }).apply()
    }

    private fun load(): MutableList<Btn>? = try {
        prefs.getString("layout", null)?.split(";")?.filter { it.isNotEmpty() }?.map {
            val s = it.split(","); Btn(label(s[0].toInt()), s[0].toInt(), s[1].toFloat(), s[2].toFloat(), s[3].toFloat())
        }?.toMutableList()
    } catch (e: Exception) { null }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        u = h / 100f
        if (btns.isEmpty()) btns = load() ?: defaults(w, h)
        connectRect.set(w / 2f - 20 * u, h - 14 * u, w / 2f + 20 * u, h - 4 * u)
        editRect.set(4 * u, 3 * u, 20 * u, 11 * u)
    }

    private fun toolRect(i: Int): RectF {
        val w = 15 * u; val gap = 1.5f * u; val total = tools.size * w + (tools.size - 1) * gap
        val l = (width - total) / 2f + i * (w + gap)
        return RectF(l, height - 12 * u, l + w, height - 3 * u)
    }

    private val tick = object : Runnable {
        override fun run() {
            rx = (accX * 6f).toInt().coerceIn(-127, 127); ry = (accY * 6f).toInt().coerceIn(-127, 127)
            accX = 0f; accY = 0f
            push(); postDelayed(this, 8)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun push() {
        var m = 0; btns.forEach { if (it.down) m = m or (1 shl it.bit) }
        val s = listOf(m, lx, ly, rx, ry)
        if (s != lastSent) { lastSent = s; send(m, lx, ly, rx, ry) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) up(e.getPointerId(i))
        }
        push(); invalidate(); return true
    }

    private fun hit(x: Float, y: Float) = btns.lastOrNull { hypot(x - px(it), y - py(it)) <= pr(it) * 1.15f }

    private fun down(id: Int, x: Float, y: Float) {
        if (editing) { editDown(id, x, y); return }
        if (editRect.contains(x, y)) { editing = true; return }
        if (connectRect.contains(x, y)) { onConnectTap(); return }
        val b = hit(x, y)
        if (b != null) { b.down = true; byId[id] = b; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); return }
        if (x < width * 0.4f && stickId == -1) { stickId = id; ox = x; oy = y; sx = x; sy = y }
        else if (camId == -1) { camId = id; lastX = x; lastY = y }
    }

    private fun editDown(id: Int, x: Float, y: Float) {
        for (i in tools.indices) if (toolRect(i).contains(x, y)) {
            when (tools[i]) {
                "Done" -> { editing = false; sel = null; save() }
                "Add" -> {
                    val free = catalog.filter { f -> btns.none { it.bit == f.second } }
                    if (free.isNotEmpty()) AlertDialog.Builder(context).setTitle("Add button")
                        .setItems(free.map { it.first }.toTypedArray()) { _, k ->
                            val nb = Btn(free[k].first, free[k].second, 0.5f, 0.5f, 9f); btns.add(nb); sel = nb; save(); invalidate()
                        }.show()
                }
                "Smaller" -> sel?.let { it.fr = (it.fr - 1f).coerceAtLeast(4f) }
                "Bigger" -> sel?.let { it.fr = (it.fr + 1f).coerceAtMost(25f) }
                "Delete" -> sel?.let { btns.remove(it); sel = null }
                "Reset" -> { btns = defaults(width, height); sel = null }
            }
            save(); return
        }
        val b = hit(x, y); sel = b
        if (b != null) dragId = id
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (editing) {
            if (id == dragId) sel?.let { it.fx = (x / width).coerceIn(0.02f, 0.98f); it.fy = (y / height).coerceIn(0.05f, 0.85f) }
            return
        }
        if (id == stickId) {
            var dx = x - ox; var dy = y - oy; val max = 11 * u; val l = hypot(dx, dy)
            if (l > max) { dx *= max / l; dy *= max / l }
            sx = ox + dx; sy = oy + dy
            lx = (dx / max * 127).toInt(); ly = (dy / max * 127).toInt()
        } else if (id == camId) { accX += x - lastX; accY += y - lastY; lastX = x; lastY = y }
    }

    private fun up(id: Int) {
        if (id == dragId) { dragId = -1; save() }
        byId.remove(id)?.down = false
        if (id == stickId) { stickId = -1; lx = 0; ly = 0 }
        if (id == camId) camId = -1
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(18, 18, 22))
        p.textAlign = Paint.Align.CENTER
        if (!editing) {
            p.style = Paint.Style.FILL; p.color = Color.argb(28, 255, 255, 255)
            val bx = if (stickId == -1) 28 * u else ox; val by = if (stickId == -1) height - 32 * u else oy
            c.drawCircle(bx, by, 14 * u, p)
            p.color = Color.argb(110, 255, 255, 255)
            c.drawCircle(if (stickId == -1) bx else sx, if (stickId == -1) by else sy, 6 * u, p)
        }
        for (b in btns) {
            val col = colors[b.bit]; val x = px(b); val y = py(b); val r = pr(b)
            p.style = Paint.Style.FILL
            p.color = if (b.down) col else Color.argb(60, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = u * (if (b === sel) 0.9f else 0.35f)
            p.color = if (b === sel) Color.WHITE else Color.argb(120, 255, 255, 255)
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = r * 0.55f
            c.drawText(b.label, x, y + r * 0.2f, p)
        }
        p.style = Paint.Style.FILL; p.textSize = 3.6f * u
        if (editing) {
            for (i in tools.indices) {
                val r = toolRect(i); p.color = Color.argb(60, 255, 255, 255); c.drawRoundRect(r, 3 * u, 3 * u, p)
                p.color = Color.WHITE; c.drawText(tools[i], r.centerX(), r.centerY() + 1.2f * u, p)
            }
            p.color = Color.argb(160, 255, 255, 255)
            c.drawText("Drag buttons to move. Tap one to select, then Smaller/Bigger/Delete.", width / 2f, height - 16 * u, p)
        } else {
            p.color = Color.argb(45, 255, 255, 255)
            c.drawRoundRect(connectRect, 5 * u, 5 * u, p); c.drawRoundRect(editRect, 4 * u, 4 * u, p)
            p.color = Color.WHITE
            c.drawText(status, connectRect.centerX(), connectRect.centerY() + 1.2f * u, p)
            c.drawText("Edit", editRect.centerX(), editRect.centerY() + 1.2f * u, p)
        }
    }
}
