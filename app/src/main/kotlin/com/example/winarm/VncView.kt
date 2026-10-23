package com.example.winarm

import android.content.Context
import android.graphics.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.io.*
import java.net.ConnectException
import java.net.Socket
import kotlin.math.abs

/** VNC (RFB 3.8, raw encoding) client แบบย่อ */
class VncView(c: Context) : View(c) {
    var rightClick = false
    var fps = 30
    var touchpad = false
    private var cx = 0; private var cy = 0; private var lx = 0f; private var ly = 0f
    private var t0 = 0L; private var moved = 0f
    @Volatile private var bmp: Bitmap? = null
    @Volatile private var sock: Socket? = null
    private var out: OutputStream? = null
    private var fw = 0; private var fh = 0
    private var scale = 1f; private var ox = 0f; private var oy = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init { isFocusable = true; isFocusableInTouchMode = true }

    fun connect() {
        Thread {
            repeat(60) {
                try { session(); return@Thread }
                catch (e: ConnectException) { Thread.sleep(1000) }
                catch (e: Exception) { return@Thread }
            }
        }.start()
    }
    fun disconnect() { try { sock?.close() } catch (_: Exception) {}; sock = null; bmp = null; postInvalidate() }

    private fun DataInputStream.skipN(n: Int) { if (n > 0) readFully(ByteArray(n)) }
    fun snapshot(): Bitmap? = bmp?.copy(Bitmap.Config.ARGB_8888, false)
    private fun point(m: Int, x: Int, y: Int) = send { writeByte(5); writeByte(m); writeShort(x); writeShort(y) }
    private fun send(f: DataOutputStream.() -> Unit) = synchronized(this) {
        try {
            val bo = ByteArrayOutputStream(); DataOutputStream(bo).f()
            out?.write(bo.toByteArray()); out?.flush()
        } catch (_: Exception) {}
    }

    private fun session() {
        val s = Socket("127.0.0.1", 5900); sock = s
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        out = s.getOutputStream()
        inp.skipN(12); out!!.write("RFB 003.008\n".toByteArray()); out!!.flush()
        val n = inp.readUnsignedByte(); if (n == 0) throw IOException("no security")
        inp.skipN(n); out!!.write(1); out!!.flush()
        if (inp.readInt() != 0) throw IOException("auth failed")
        out!!.write(1); out!!.flush() // shared
        fw = inp.readUnsignedShort(); fh = inp.readUnsignedShort()
        inp.skipN(16); inp.skipN(inp.readInt())
        send { // SetPixelFormat: 32bpp, little-endian, RGB shifts 16/8/0
            writeByte(0); write(ByteArray(3)); writeByte(32); writeByte(24); writeByte(0); writeByte(1)
            writeShort(255); writeShort(255); writeShort(255); writeByte(16); writeByte(8); writeByte(0); write(ByteArray(3))
        }
        send { writeByte(2); writeByte(0); writeShort(1); writeInt(0) } // raw only
        bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        fun req(inc: Int) = send { writeByte(3); writeByte(inc); writeShort(0); writeShort(0); writeShort(fw); writeShort(fh) }
        req(0)
        while (true) {
            when (inp.readUnsignedByte()) {
                0 -> {
                    inp.skipN(1); val rects = inp.readUnsignedShort()
                    repeat(rects) {
                        val x = inp.readUnsignedShort(); val y = inp.readUnsignedShort()
                        val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
                        if (inp.readInt() != 0) throw IOException("encoding")
                        val buf = ByteArray(w * h * 4); inp.readFully(buf)
                        val px = IntArray(w * h) { i ->
                            val o = i * 4
                            (0xFF shl 24) or ((buf[o + 2].toInt() and 255) shl 16) or
                                ((buf[o + 1].toInt() and 255) shl 8) or (buf[o].toInt() and 255)
                        }
                        bmp?.setPixels(px, 0, w, x, y, w, h)
                    }
                    postInvalidate(); if (fps in 1..59) Thread.sleep(1000L / fps); req(1)
                }
                1 -> { inp.skipN(1); inp.readUnsignedShort(); inp.skipN(inp.readUnsignedShort() * 6) }
                2 -> {}
                3 -> { inp.skipN(3); inp.skipN(inp.readInt()) }
                else -> throw IOException("unknown msg")
            }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val b = bmp ?: return
        scale = minOf(width.toFloat() / fw, height.toFloat() / fh)
        ox = (width - fw * scale) / 2; oy = (height - fh * scale) / 2
        val m = Matrix().apply { postScale(scale, scale); postTranslate(ox, oy) }
        c.drawBitmap(b, m, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (fw == 0) return true
        if (touchpad) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; t0 = e.eventTime; moved = 0f }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lx; val dy = e.y - ly; lx = e.x; ly = e.y; moved += abs(dx) + abs(dy)
                    cx = (cx + dx / scale * 1.5f).toInt().coerceIn(0, fw - 1)
                    cy = (cy + dy / scale * 1.5f).toInt().coerceIn(0, fh - 1)
                    point(0, cx, cy)
                }
                MotionEvent.ACTION_UP -> if (e.eventTime - t0 < 250 && moved < 30f) {
                    point(if (rightClick) 4 else 1, cx, cy); point(0, cx, cy)
                }
            }
            return true
        }
        val x = ((e.x - ox) / scale).toInt().coerceIn(0, fw - 1)
        val y = ((e.y - oy) / scale).toInt().coerceIn(0, fh - 1)
        val mask = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (rightClick) 4 else 1
            else -> 0
        }
        point(mask, x, y)
        return true
    }

    fun key(down: Boolean, sym: Int) = send { writeByte(4); writeByte(if (down) 1 else 0); writeShort(0); writeInt(sym) }

    private fun sym(e: KeyEvent): Int = when (e.keyCode) {
        KeyEvent.KEYCODE_ENTER -> 0xff0d; KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_TAB -> 0xff09; KeyEvent.KEYCODE_ESCAPE -> 0xff1b
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51; KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53; KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1; KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
        KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9; KeyEvent.KEYCODE_META_LEFT -> 0xffeb
        else -> e.unicodeChar.takeIf { it > 0 } ?: 0
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean { val s = sym(e); if (s == 0) return super.onKeyDown(k, e); key(true, s); return true }
    override fun onKeyUp(k: Int, e: KeyEvent): Boolean { val s = sym(e); if (s == 0) return super.onKeyUp(k, e); key(false, s); return true }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(a: EditorInfo): InputConnection {
        a.inputType = InputType.TYPE_NULL
        return BaseInputConnection(this, false)
    }
}
