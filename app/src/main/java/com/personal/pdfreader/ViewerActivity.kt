package com.personal.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.min

class ViewerActivity : Activity() {

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var info: TextView
    private lateinit var modeBtn: Button
    private val snap = PagerSnapHelper()
    private var snapOn = false
    private var scale = 1f
    private var pending = 1f
    private var dark = false
    private var mode = 0 // 0 = scroll, 1 = single page (fit), 2 = two page
    private var perRow = 1
    private var currentKey = ""
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val modeNames = arrayOf("Scroll", "Page", "2-Page")

    private val invert = ColorMatrixColorFilter(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = prefs.getBoolean("dark", false)
        mode = prefs.getInt("mode", 0).coerceIn(0, 2)
        buildUi()
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pending = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean { pending *= d.scaleFactor; return true }
            override fun onScaleEnd(d: ScaleGestureDetector) { setScale(scale * pending) }
        })
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                setScale(if (scale > 1.1f) 1f else 2f)
                return true
            }
        })
        if (!handleIntent(intent)) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?): Boolean {
        if (i == null) return false
        val p = i.getStringExtra("path")
        if (p != null) return openDoc(p, null)
        val d = i.data ?: return false
        val dp = d.path
        if (d.scheme == "file" && dp != null) return openDoc(dp, null)
        return openDoc(null, d)
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val hsv = HorizontalScrollView(this)
        lm = LinearLayoutManager(this)
        list = RecyclerView(this)
        list.layoutManager = lm
        list.layoutParams = ViewGroup.LayoutParams(resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateInfo() }
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) { updateInfo() }
        })
        hsv.addView(list)
        root.addView(hsv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        info = TextView(this)
        info.setPadding(16, 8, 16, 8)
        root.addView(info)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        modeBtn = btn(modeNames[mode]) { cycleMode() }
        bar.addView(modeBtn)
        bar.addView(btn("-") { setScale(scale - 0.5f) })
        bar.addView(btn("+") { setScale(scale + 0.5f) })
        bar.addView(btn("Fit") { setScale(1f) })
        bar.addView(btn("Dark") { toggleDark() })
        bar.addView(btn("Go") { goToPage() })
        root.addView(bar)

        setContentView(root)
        applyBackground()
    }

    private fun btn(t: String, click: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 13f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(0, 0, 0, 0)
        b.setOnClickListener { click() }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return b
    }

    private fun applyBackground() {
        list.setBackgroundColor(if (dark) Color.BLACK else Color.LTGRAY)
    }

    private fun toggleDark() {
        dark = !dark
        prefs.edit().putBoolean("dark", dark).apply()
        applyBackground()
        list.adapter?.notifyDataSetChanged()
    }

    private fun currentPage(): Int = lm.findFirstVisibleItemPosition().coerceAtLeast(0) * perRow

    private fun applyWidth() {
        val lp = list.layoutParams
        lp.width = (resources.displayMetrics.widthPixels * scale).toInt()
        list.layoutParams = lp
    }

    private fun setScale(s: Float) {
        if (mode == 1) return
        val page = currentPage()
        scale = s.coerceIn(1f, 2.5f)
        applyWidth()
        list.adapter?.notifyDataSetChanged()
        lm.scrollToPositionWithOffset(page / perRow, 0)
    }

    private fun cycleMode() {
        mode = (mode + 1) % 3
        prefs.edit().putInt("mode", mode).apply()
        applyMode()
    }

    private fun applyMode() {
        val page = currentPage()
        perRow = if (mode == 2) 2 else 1
        val fit = mode == 1
        if (fit) {
            scale = 1f
            if (!snapOn) { snap.attachToRecyclerView(list); snapOn = true }
        } else if (snapOn) {
            snap.attachToRecyclerView(null)
            snapOn = false
        }
        applyWidth()
        modeBtn.text = modeNames[mode]
        if (renderer != null) {
            list.adapter = PageAdapter(perRow, fit)
            lm.scrollToPositionWithOffset(page / perRow, 0)
            updateInfo()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWidth()
        list.adapter?.notifyDataSetChanged()
    }

    private fun goToPage() {
        val r = renderer ?: return
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "1 - " + r.pageCount
        AlertDialog.Builder(this)
            .setTitle("Page number")
            .setView(et)
            .setPositiveButton("Go") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n != null && n in 1..r.pageCount) {
                    lm.scrollToPositionWithOffset((n - 1) / perRow, 0)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun closeDoc() {
        try { renderer?.close() } catch (e: Exception) { }
        try { pfd?.close() } catch (e: Exception) { }
        renderer = null
        pfd = null
    }

    private fun openDoc(path: String?, uri: Uri?): Boolean {
        try {
            val d: ParcelFileDescriptor? = if (path != null) {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                contentResolver.openFileDescriptor(uri!!, "r")
            }
            if (d == null) return false
            val r: PdfRenderer
            try {
                r = PdfRenderer(d)
            } catch (e: Exception) {
                d.close()
                throw e
            }
            saveProgress()
            list.adapter = null
            closeDoc()
            pfd = d
            renderer = r
            currentKey = path ?: uri.toString()
            if (path != null) Store.addRecent(this, path)
            applyMode()
            val last = prefs.getInt("page_" + currentKey.hashCode(), 0)
            lm.scrollToPositionWithOffset(last / perRow, 0)
            updateInfo()
            return true
        } catch (e: Exception) {
            Toast.makeText(this, "PDF nahi khul saki (password ya kharab file?)", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun updateInfo() {
        val r = renderer ?: return
        info.text = "Page " + (currentPage() + 1) + " / " + r.pageCount
    }

    private fun saveProgress() {
        if (renderer == null || currentKey.isEmpty()) return
        prefs.edit().putInt("page_" + currentKey.hashCode(), currentPage()).apply()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeDoc()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    inner class PageAdapter(private val per: Int, private val fit: Boolean) :
        RecyclerView.Adapter<PageAdapter.VH>() {

        inner class VH(v: LinearLayout, val imgs: List<ImageView>) : RecyclerView.ViewHolder(v)

        override fun getItemCount(): Int {
            val n = renderer?.pageCount ?: 0
            return (n + per - 1) / per
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context)
            row.orientation = LinearLayout.HORIZONTAL
            val h = if (fit) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            val imgs = ArrayList<ImageView>()
            for (i in 0 until per) {
                val iv = ImageView(parent.context)
                iv.layoutParams = LinearLayout.LayoutParams(0, h, 1f)
                if (fit) iv.scaleType = ImageView.ScaleType.FIT_CENTER else iv.adjustViewBounds = true
                row.addView(iv)
                imgs.add(iv)
            }
            return VH(row, imgs)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = renderer ?: return
            val cellW = (resources.displayMetrics.widthPixels * scale / per).toInt().coerceAtLeast(1)
            val vh = if (list.height > 0) list.height else (resources.displayMetrics.heightPixels * 0.8f).toInt()
            for (i in 0 until per) {
                val idx = position * per + i
                val iv = holder.imgs[i]
                if (idx >= r.pageCount) {
                    iv.setImageDrawable(null)
                    continue
                }
                try {
                    synchronized(r) {
                        val page = r.openPage(idx)
                        val pw = page.width
                        val ph = page.height
                        val w: Int
                        val h: Int
                        if (fit) {
                            val f = min(cellW.toFloat() / pw, vh.toFloat() / ph)
                            w = (pw * f).toInt().coerceAtLeast(1)
                            h = (ph * f).toInt().coerceAtLeast(1)
                        } else {
                            w = cellW
                            h = (cellW.toFloat() * ph / pw).toInt().coerceAtLeast(1)
                        }
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        iv.setImageBitmap(bmp)
                    }
                } catch (e: OutOfMemoryError) {
                    iv.setImageDrawable(null)
                }
                iv.colorFilter = if (dark) invert else null
            }
        }

        override fun onViewRecycled(holder: VH) {
            for (iv in holder.imgs) iv.setImageDrawable(null)
        }
    }
}
