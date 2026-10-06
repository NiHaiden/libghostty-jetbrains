package com.github.nihaiden.ghostty.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws box drawing (U+2500-U+257F), block elements (U+2580-U+259F) and the
 * Powerline separators (U+E0B0-U+E0B3) geometrically, like Ghostty does, so
 * that lines join seamlessly between cells regardless of the font.
 */
object BoxDrawing {

    fun handles(cp: Int): Boolean = cp in 0x2500..0x259F || cp in 0xE0B0..0xE0B3

    // Arm weights per box drawing char, as "URDL" digits:
    // 0 none, 1 light, 2 heavy, 3 double. Index = cp - 0x2500.
    private val ARMS: Array<String> = arrayOf(
        "0101", "0202", "1010", "2020", "0101", "0202", "1010", "2020", // 2500
        "0101", "0202", "1010", "2020", "0110", "0210", "0120", "0220", // 2508
        "0011", "0012", "0021", "0022", "1100", "1200", "2100", "2200", // 2510
        "1001", "1002", "2001", "2002", "1110", "1210", "2110", "1120", // 2518
        "2120", "2210", "1220", "2220", "1011", "1012", "2011", "1021", // 2520
        "2021", "2012", "1022", "2022", "0111", "0112", "0211", "0212", // 2528
        "0121", "0122", "0221", "0222", "1101", "1102", "1201", "1202", // 2530
        "2101", "2102", "2201", "2202", "1111", "1112", "1211", "1212", // 2538
        "2111", "1121", "2121", "2112", "2211", "1122", "1221", "2212", // 2540
        "1222", "2122", "2221", "2222", "0101", "0202", "1010", "2020", // 2548
        "0303", "3030", "0310", "0130", "0330", "0013", "0031", "0033", // 2550
        "1300", "3100", "3300", "1003", "3001", "3003", "1310", "3130", // 2558
        "3330", "1013", "3031", "3033", "0313", "0131", "0333", "1303", // 2560
        "3101", "3303", "1313", "3131", "3333", "0000", "0000", "0000", // 2568
        "0000", "0000", "0000", "0000", "0001", "1000", "0100", "0010", // 2570
        "0002", "2000", "0200", "0020", "0201", "1020", "0102", "2010", // 2578
    )

    /** Number of dashes for the dashed line chars. */
    private fun dashes(cp: Int): Int = when (cp) {
        0x2504, 0x2505, 0x2506, 0x2507 -> 3
        0x2508, 0x2509, 0x250A, 0x250B -> 4
        0x254C, 0x254D, 0x254E, 0x254F -> 2
        else -> 0
    }

    /**
     * Draws [cp] into the cell at (x, y) of size (w, h). Returns false if the
     * code point isn't handled (the caller should fall back to the font).
     */
    fun draw(g: Graphics2D, cp: Int, x: Int, y: Int, w: Int, h: Int, color: Color): Boolean {
        if (!handles(cp)) return false
        g.color = color
        val light = max(1, (min(w, h) / 9.0).roundToInt())
        when (cp) {
            in 0x2580..0x259F -> block(g, cp, x, y, w, h, color)
            in 0xE0B0..0xE0B3 -> powerline(g, cp, x, y, w, h, light)
            in 0x256D..0x2570 -> arc(g, cp, x, y, w, h, light)
            in 0x2571..0x2573 -> diagonal(g, cp, x, y, w, h, light)
            else -> {
                val dash = dashes(cp)
                if (dash > 0) dashed(g, cp, x, y, w, h, light, dash) else lines(g, ARMS[cp - 0x2500], x, y, w, h, light)
            }
        }
        return true
    }

    private fun lines(g: Graphics2D, arms: String, x: Int, y: Int, w: Int, h: Int, light: Int) {
        val up = arms[0] - '0'
        val right = arms[1] - '0'
        val down = arms[2] - '0'
        val left = arms[3] - '0'
        val heavy = light * 2
        val gap = max(1, light) // half distance between the strokes of a double line
        val cx = x + w / 2
        val cy = y + h / 2

        fun thick(weight: Int) = if (weight == 2) heavy else light

        // How far a perpendicular arm reaches past the center line, so joins are solid.
        fun reach(a: Int, b: Int): Int {
            val m = max(a, b)
            return when (m) {
                3 -> gap + light / 2 + light % 2
                2 -> heavy / 2
                1 -> light / 2
                else -> 0
            }
        }

        // Horizontal arms.
        for ((weight, toRight) in listOf(right to true, left to false)) {
            if (weight == 0) continue
            if (weight == 3) {
                for (side in intArrayOf(-1, 1)) {
                    val perp = if (side < 0) up else down
                    val ly = cy + side * gap - light / 2
                    val inner = when (perp) {
                        0 -> -(gap + light / 2) // run through to the far stroke
                        3 -> gap + light / 2 // stop at the near stroke
                        else -> 0
                    }
                    if (toRight) g.fillRect(cx + inner, ly, x + w - (cx + inner), light)
                    else g.fillRect(x, ly, cx - inner - x, light)
                }
            } else {
                val t = thick(weight)
                val ext = reach(up, down)
                val ly = cy - t / 2
                if (toRight) g.fillRect(cx - ext, ly, x + w - (cx - ext), t)
                else g.fillRect(x, ly, cx + ext - x + (if (ext == 0) t - t / 2 else 0), t)
            }
        }
        // Vertical arms.
        for ((weight, toBottom) in listOf(down to true, up to false)) {
            if (weight == 0) continue
            if (weight == 3) {
                for (side in intArrayOf(-1, 1)) {
                    val perp = if (side < 0) left else right
                    val lx = cx + side * gap - light / 2
                    val inner = when (perp) {
                        0 -> -(gap + light / 2)
                        3 -> gap + light / 2
                        else -> 0
                    }
                    if (toBottom) g.fillRect(lx, cy + inner, light, y + h - (cy + inner))
                    else g.fillRect(lx, y, light, cy - inner - y)
                }
            } else {
                val t = thick(weight)
                val ext = reach(left, right)
                val lx = cx - t / 2
                if (toBottom) g.fillRect(lx, cy - ext, t, y + h - (cy - ext))
                else g.fillRect(lx, y, t, cy + ext - y + (if (ext == 0) t - t / 2 else 0))
            }
        }
    }

    private fun dashed(g: Graphics2D, cp: Int, x: Int, y: Int, w: Int, h: Int, light: Int, n: Int) {
        val heavy = (cp - 0x2500) % 2 == 1 || cp == 0x254D || cp == 0x254F
        val t = if (heavy) light * 2 else light
        val vertical = cp in intArrayOf(0x2506, 0x2507, 0x250A, 0x250B, 0x254E, 0x254F)
        val len = if (vertical) h else w
        val seg = len.toDouble() / n
        for (i in 0 until n) {
            val start = (i * seg).roundToInt()
            val end = ((i + 1) * seg - seg / 3).roundToInt()
            if (vertical) g.fillRect(x + w / 2 - t / 2, y + start, t, max(1, end - start))
            else g.fillRect(x + start, y + h / 2 - t / 2, max(1, end - start), t)
        }
    }

    private fun arc(g: Graphics2D, cp: Int, x: Int, y: Int, w: Int, h: Int, light: Int) {
        val cx = x + w / 2.0
        val cy = y + h / 2.0
        val r = min(w, h) / 2.0
        val p = GeneralPath()
        when (cp) {
            0x256D -> { p.moveTo(cx, y + h.toDouble()); p.lineTo(cx, cy + r); p.quadTo(cx, cy, cx + r, cy); p.lineTo(x + w.toDouble(), cy) }
            0x256E -> { p.moveTo(cx, y + h.toDouble()); p.lineTo(cx, cy + r); p.quadTo(cx, cy, cx - r, cy); p.lineTo(x.toDouble(), cy) }
            0x256F -> { p.moveTo(cx, y.toDouble()); p.lineTo(cx, cy - r); p.quadTo(cx, cy, cx - r, cy); p.lineTo(x.toDouble(), cy) }
            0x2570 -> { p.moveTo(cx, y.toDouble()); p.lineTo(cx, cy - r); p.quadTo(cx, cy, cx + r, cy); p.lineTo(x + w.toDouble(), cy) }
        }
        stroke(g, light) { g.draw(p) }
    }

    private fun diagonal(g: Graphics2D, cp: Int, x: Int, y: Int, w: Int, h: Int, light: Int) = stroke(g, light) {
        val l = x.toDouble()
        val r = (x + w).toDouble()
        val t = y.toDouble()
        val b = (y + h).toDouble()
        if (cp == 0x2571 || cp == 0x2573) g.draw(Line2D.Double(r, t, l, b))
        if (cp == 0x2572 || cp == 0x2573) g.draw(Line2D.Double(l, t, r, b))
    }

    private inline fun stroke(g: Graphics2D, width: Int, block: () -> Unit) {
        val oldStroke = g.stroke
        val oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        g.stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        block()
        g.stroke = oldStroke
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa)
    }

    private fun block(g: Graphics2D, cp: Int, x: Int, y: Int, w: Int, h: Int, color: Color) {
        fun lower(eighths: Int) {
            val hh = (h * eighths / 8.0).roundToInt()
            g.fillRect(x, y + h - hh, w, hh)
        }
        fun left(eighths: Int) = g.fillRect(x, y, (w * eighths / 8.0).roundToInt(), h)
        fun quads(mask: Int) {
            val hw = w / 2
            val hh = h / 2
            if (mask and 1 != 0) g.fillRect(x, y, hw, hh)
            if (mask and 2 != 0) g.fillRect(x + hw, y, w - hw, hh)
            if (mask and 4 != 0) g.fillRect(x, y + hh, hw, h - hh)
            if (mask and 8 != 0) g.fillRect(x + hw, y + hh, w - hw, h - hh)
        }
        fun shade(alpha: Int) {
            g.color = Color(color.red, color.green, color.blue, alpha)
            g.fillRect(x, y, w, h)
            g.color = color
        }
        when (cp) {
            0x2580 -> g.fillRect(x, y, w, h / 2)
            in 0x2581..0x2588 -> lower(cp - 0x2580)
            in 0x2589..0x258F -> left(8 - (cp - 0x2588))
            0x2590 -> g.fillRect(x + w / 2, y, w - w / 2, h)
            0x2591 -> shade(64)
            0x2592 -> shade(128)
            0x2593 -> shade(192)
            0x2594 -> g.fillRect(x, y, w, max(1, (h / 8.0).roundToInt()))
            0x2595 -> {
                val ww = max(1, (w / 8.0).roundToInt())
                g.fillRect(x + w - ww, y, ww, h)
            }
            0x2596 -> quads(4)
            0x2597 -> quads(8)
            0x2598 -> quads(1)
            0x2599 -> quads(1 or 4 or 8)
            0x259A -> quads(1 or 8)
            0x259B -> quads(1 or 2 or 4)
            0x259C -> quads(1 or 2 or 8)
            0x259D -> quads(2)
            0x259E -> quads(2 or 4)
            0x259F -> quads(2 or 4 or 8)
        }
    }

    private fun powerline(g: Graphics2D, cp: Int, x: Int, y: Int, w: Int, h: Int, light: Int) {
        val l = x.toDouble()
        val r = (x + w).toDouble()
        val t = y.toDouble()
        val b = (y + h).toDouble()
        val m = y + h / 2.0
        val p = GeneralPath()
        when (cp) {
            0xE0B0, 0xE0B1 -> { p.moveTo(l, t); p.lineTo(r, m); p.lineTo(l, b) }
            0xE0B2, 0xE0B3 -> { p.moveTo(r, t); p.lineTo(l, m); p.lineTo(r, b) }
        }
        if (cp == 0xE0B0 || cp == 0xE0B2) {
            p.closePath()
            val oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.fill(p)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa)
        } else {
            stroke(g, light) { g.draw(p) }
        }
    }
}
