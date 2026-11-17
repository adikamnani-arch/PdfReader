package com.personal.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
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
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity() {

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var info: TextView
    private var scale = 1f
    private var pending = 1f
    private var dark = false
    private var currentKey = ""
    private lateinit var scaleDetector: ScaleGestureDetector
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val reqOpen = 1

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
        buildUi()
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pending = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean { pending *= d.scaleFactor; return true }
            override fun onScaleEnd(d: ScaleGestureDetector) { setScale(scale * pending) }
        })
        if (!handleIntent(intent)) {
            prefs.getString("lastUri", null)?.let { open(Uri.parse(it), true) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?): Boolean {
        val data = i?.data
        if (i?.action == Intent.ACTION_VIEW && data != null) {
            return open(data, false)
        }
        return false
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
        })
        hsv.addView(list)
        root.addView(hsv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        info = TextView(this)
        info.setPadding(16, 8, 16, 8)
        info.text = "PDF kholne ke liye Open dabao"
        root.addView(info)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(btn("Open") { pick() })
        bar.addView(btn("-") { setScale(scale - 0.5f) })
        bar.addView(btn("+") { setScale(scale + 0.5f) })
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

    private fun setScale(s: Float) {
        scale = s.coerceIn(1f, 2.5f)
        val pos = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val lp = list.layoutParams
        lp.width = (resources.displayMetrics.widthPixels * scale).toInt()
        list.layoutParams = lp
        list.adapter?.notifyDataSetChanged()
        lm.scrollToPositionWithOffset(pos, 0)
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, reqOpen)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == reqOpen && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            open(uri, false)
        }
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
                    lm.scrollToPositionWithOffset(n - 1, 0)
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

    private fun open(uri: Uri, quiet: Boolean): Boolean {
        try {
            val d = contentResolver.openFileDescriptor(uri, "r") ?: return false
            val r = PdfRenderer(d)
            saveProgress()
            list.adapter = null
            closeDoc()
            pfd = d
            renderer = r
            currentKey = uri.toString()
            prefs.edit().putString("lastUri", currentKey).apply()
            list.adapter = PageAdapter()
            val last = prefs.getInt("page_" + currentKey.hashCode(), 0)
            lm.scrollToPositionWithOffset(last, 0)
            updateInfo()
            return true
        } catch (e: Exception) {
            if (!quiet) Toast.makeText(this, "PDF nahi khul saki (password ya kharab file?)", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun updateInfo() {
        val r = renderer ?: return
        val p = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        info.text = "Page " + (p + 1) + " / " + r.pageCount
    }

    private fun saveProgress() {
        if (renderer == null || currentKey.isEmpty()) return
        val p = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        prefs.edit().putInt("page_" + currentKey.hashCode(), p).apply()
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
        return super.dispatchTouchEvent(ev)
    }

    inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)

        override fun getItemCount(): Int = renderer?.pageCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context)
            iv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            iv.adjustViewBounds = true
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = renderer ?: return
            val w = (resources.displayMetrics.widthPixels * scale).toInt()
            synchronized(r) {
                val page = r.openPage(position)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                holder.iv.setImageBitmap(bmp)
            }
            holder.iv.colorFilter = if (dark) invert else null
        }

        override fun onViewRecycled(holder: VH) {
            holder.iv.setImageDrawable(null)
        }
    }
}
