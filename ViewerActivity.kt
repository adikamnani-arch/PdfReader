package com.personal.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.max
import kotlin.math.min

class ViewerActivity : Activity() {

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var curPath: String? = null
    private var curUri: Uri? = null
    private var curName = "PDF"

    private lateinit var topBar: LinearLayout
    private lateinit var infoBox: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var bmBtn: Button
    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var info: TextView
    private lateinit var seek: SeekBar
    private lateinit var bar: LinearLayout
    private lateinit var modeBtn: Button
    private val topBtns = ArrayList<Button>()
    private val snap = PagerSnapHelper()
    private var snapOn = false

    private var scale = 1f
    private var pending = 1f
    private var thm = 0 // 0 White, 1 Sepia, 2 Dark, 3 Night
    private var crop = false
    private var mode = 0 // 0 scroll, 1 single page (fit), 2 two page
    private var perRow = 1
    private var readMode = false
    private var currentKey = ""
    private var docBoxCache: FloatArray? = null
    private val pageBoxCache = HashMap<Int, FloatArray>()

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector
    private val prefs: SharedPreferences by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val modeNames = arrayOf("Scroll", "Page", "2-Page")
    private val themeNames = arrayOf("White", "Sepia", "Dark", "Night")
    private val listBg = arrayOf("#E4E4EA", "#E8DFC8", "#121212", "#000000")
    private val panelBg = arrayOf("#FFFFFF", "#F4ECD8", "#1E1E1E", "#000000")
    private val textCol = arrayOf("#1B1B1F", "#3B2F20", "#E6E6E6", "#C9A27A")

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thm = prefs.getInt("theme", 0).coerceIn(0, 3)
        crop = prefs.getBoolean("crop", false)
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
        applySystem()
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
        val dpth = d.path
        if (d.scheme == "file" && dpth != null) return openDoc(dpth, null)
        return openDoc(null, d)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeDoc()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWidth()
        list.adapter?.notifyDataSetChanged()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- UI

    private fun flatBtn(t: String, click: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 18f
        b.minWidth = dp(44)
        b.minimumWidth = dp(44)
        b.setBackgroundColor(Color.TRANSPARENT)
        b.setOnClickListener { click() }
        topBtns.add(b)
        return b
    }

    private fun barBtn(t: String, click: () -> Unit): Button {
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

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(4), dp(2), dp(4), dp(2))
        topBar.addView(flatBtn("\u2190") { finish() })
        titleTv = TextView(this)
        titleTv.textSize = 16f
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bmBtn = flatBtn("\u2606") { toggleBookmark() }
        topBar.addView(bmBtn)
        topBar.addView(flatBtn("Aa") { showSettings() })
        root.addView(topBar)

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

        infoBox = LinearLayout(this)
        infoBox.orientation = LinearLayout.VERTICAL
        info = TextView(this)
        info.setPadding(dp(16), dp(6), dp(16), dp(2))
        infoBox.addView(info)
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) goTo(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seek.visibility = View.GONE
        infoBox.addView(seek)
        root.addView(infoBox)

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        modeBtn = barBtn(modeNames[mode]) { cycleMode() }
        bar.addView(modeBtn)
        bar.addView(barBtn("-") { setScale(scale - 0.5f) })
        bar.addView(barBtn("+") { setScale(scale + 0.5f) })
        bar.addView(barBtn("Fit") { setScale(1f) })
        bar.addView(barBtn("Theme") { cycleTheme() })
        bar.addView(barBtn("Go") { goToPageDialog() })
        root.addView(bar)

        setContentView(root)
        applyTheme()
    }

    private fun applyTheme() {
        val pb = Color.parseColor(panelBg[thm])
        val tc = Color.parseColor(textCol[thm])
        topBar.setBackgroundColor(pb)
        infoBox.setBackgroundColor(pb)
        bar.setBackgroundColor(pb)
        titleTv.setTextColor(tc)
        info.setTextColor(tc)
        for (b in topBtns) b.setTextColor(tc)
        list.setBackgroundColor(Color.parseColor(listBg[thm]))
    }

    private fun cycleTheme() {
        thm = (thm + 1) % 4
        prefs.edit().putInt("theme", thm).apply()
        applyTheme()
        list.adapter?.notifyDataSetChanged()
        Toast.makeText(this, themeNames[thm], Toast.LENGTH_SHORT).show()
    }

    private fun cf(vararg parts: FloatArray): ColorFilter {
        val cm = ColorMatrix(parts[0])
        for (i in 1 until parts.size) cm.postConcat(ColorMatrix(parts[i]))
        return ColorMatrixColorFilter(cm)
    }

    private fun scaleM(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    private val invertM = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )

    private fun pageFilter(): ColorFilter? = when (thm) {
        1 -> cf(scaleM(0.957f, 0.925f, 0.847f))
        2 -> cf(invertM, scaleM(0.9f, 0.9f, 0.9f))
        3 -> cf(invertM, scaleM(0.95f, 0.78f, 0.55f))
        else -> null
    }

    // system settings: keep screen on, full screen, brightness
    private fun applySystem() {
        if (prefs.getBoolean("keepOn", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (prefs.getBoolean("full", false)) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
        val b = prefs.getFloat("bright", -1f)
        val lp = window.attributes
        lp.screenBrightness = if (b < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b
        window.attributes = lp
    }

    private fun applyReadMode() {
        bar.visibility = if (readMode) View.GONE else View.VISIBLE
        seek.visibility = if (readMode) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- settings dialog

    private fun showSettings() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), dp(8))
        sv.addView(col)

        fun label(t: String) {
            val v = TextView(this)
            v.text = t
            v.textSize = 12f
            v.setTextColor(Color.GRAY)
            v.setPadding(0, dp(14), 0, dp(4))
            col.addView(v)
        }

        fun toggle(t: String, on: Boolean, cb: (Boolean) -> Unit) {
            val s = Switch(this)
            s.text = t
            s.isChecked = on
            s.setPadding(0, dp(6), 0, dp(6))
            s.setOnCheckedChangeListener { _, b -> cb(b) }
            col.addView(s)
        }

        fun radios(opts: Array<String>, sel: Int, cb: (Int) -> Unit) {
            val g = RadioGroup(this)
            g.orientation = RadioGroup.HORIZONTAL
            for (o in opts) {
                val rb = RadioButton(this)
                rb.text = o
                rb.textSize = 13f
                rb.id = View.generateViewId()
                g.addView(rb)
            }
            (g.getChildAt(sel) as RadioButton).isChecked = true
            g.setOnCheckedChangeListener { grp, id ->
                cb(grp.indexOfChild(grp.findViewById(id)))
            }
            col.addView(g)
        }

        toggle("Reading mode (book jaisa)", readMode) { on ->
            readMode = on
            prefs.edit().putInt("rm_" + currentKey.hashCode(), if (on) 1 else 0).apply()
            applyReadMode()
            updateInfo()
        }

        label("VIEW")
        radios(modeNames, mode) { i ->
            mode = i
            prefs.edit().putInt("mode", mode).apply()
            applyMode()
        }

        label("THEME")
        radios(themeNames, thm) { i ->
            thm = i
            prefs.edit().putInt("theme", thm).apply()
            applyTheme()
            list.adapter?.notifyDataSetChanged()
        }

        label("BRIGHTNESS")
        val sb = SeekBar(this)
        sb.max = 100
        val cur = prefs.getFloat("bright", -1f)
        sb.progress = if (cur < 0f) 60 else (cur * 100).toInt()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.edit().putFloat("bright", max(p, 5) / 100f).apply()
                    applySystem()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(sb)

        label("PAGE")
        toggle("Crop margins", crop) { on ->
            crop = on
            prefs.edit().putBoolean("crop", on).apply()
            list.adapter?.notifyDataSetChanged()
        }
        toggle("Keep screen on", prefs.getBoolean("keepOn", false)) { on ->
            prefs.edit().putBoolean("keepOn", on).apply()
            applySystem()
        }
        toggle("Full screen", prefs.getBoolean("full", false)) { on ->
            prefs.edit().putBoolean("full", on).apply()
            applySystem()
        }

        val nBm = Store.bookmarks(this, currentKey).size
        val bmB = Button(this)
        bmB.text = "Bookmarks (" + nBm + ")"
        bmB.isAllCaps = false
        bmB.setOnClickListener { showBookmarks() }
        col.addView(bmB)
        val prB = Button(this)
        prB.text = "Print"
        prB.isAllCaps = false
        prB.setOnClickListener { Convert.print(this, curPath, curUri, curName) }
        col.addView(prB)

        AlertDialog.Builder(this)
            .setTitle("Reading settings")
            .setView(sv)
            .setPositiveButton("Done", null)
            .show()
    }

    // ---------------------------------------------------------------- bookmarks, progress, navigation

    private fun toggleBookmark() {
        if (renderer == null) return
        val on = Store.toggleBookmark(this, currentKey, currentPage())
        updateBm()
        Toast.makeText(this, if (on) "Bookmark laga diya" else "Bookmark hata diya", Toast.LENGTH_SHORT).show()
    }

    private fun updateBm() {
        val on = Store.bookmarks(this, currentKey).contains(currentPage())
        bmBtn.text = if (on) "\u2605" else "\u2606"
    }

    private fun showBookmarks() {
        val l = Store.bookmarks(this, currentKey)
        if (l.isEmpty()) {
            Toast.makeText(this, "Abhi koi bookmark nahi hai. Upar star dabake lagao", Toast.LENGTH_LONG).show()
            return
        }
        val items = l.map { "Page " + (it + 1) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(items) { _, i -> goTo(l[i]) }
            .setNeutralButton("Sab hatao") { _, _ ->
                Store.clearBookmarks(this, currentKey)
                updateBm()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun currentPage(): Int = lm.findFirstVisibleItemPosition().coerceAtLeast(0) * perRow

    private fun goTo(page: Int) {
        lm.scrollToPositionWithOffset(page / perRow, 0)
    }

    private fun goToPageDialog() {
        val r = renderer ?: return
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "1 - " + r.pageCount
        AlertDialog.Builder(this)
            .setTitle("Page number")
            .setView(et)
            .setPositiveButton("Go") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n != null && n in 1..r.pageCount) goTo(n - 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateInfo() {
        val r = renderer ?: return
        val p = currentPage()
        val pct = ((p + 1) * 100) / r.pageCount
        info.text = "Page " + (p + 1) + " / " + r.pageCount + "  \u2022  " + pct + "% read"
        if (seek.progress != p) seek.progress = p
        updateBm()
    }

    // ---------------------------------------------------------------- zoom and view modes

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
            if (!snapOn) {
                snap.attachToRecyclerView(list)
                snapOn = true
            }
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

    // ---------------------------------------------------------------- open and close

    private fun closeDoc() {
        try { renderer?.close() } catch (e: Exception) { }
        try { pfd?.close() } catch (e: Exception) { }
        renderer = null
        pfd = null
    }

    private fun queryName(uri: Uri): String {
        var n = "PDF"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) n = c.getString(i)
            }
        } catch (e: Exception) { }
        return n
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
            curPath = path
            curUri = uri
            curName = if (path != null) File(path).name else queryName(uri!!)
            currentKey = path ?: uri.toString()
            docBoxCache = null
            pageBoxCache.clear()
            if (path != null) Store.addRecent(this, path)
            titleTv.text = curName

            val saved = prefs.getInt("rm_" + currentKey.hashCode(), -1)
            readMode = if (saved >= 0) saved == 1 else r.pageCount >= prefs.getInt("readLimit", 30)
            if (saved < 0 && readMode) {
                Toast.makeText(this, "Reading mode on. Badalne ke liye Aa dabao", Toast.LENGTH_LONG).show()
            }
            seek.max = max(r.pageCount - 1, 1)
            applyReadMode()
            applyMode()
            val last = prefs.getInt("page_" + currentKey.hashCode(), 0)
            goTo(last)
            updateInfo()
            return true
        } catch (e: Exception) {
            Toast.makeText(this, "PDF nahi khul saki (password ya kharab file?)", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun saveProgress() {
        if (renderer == null || currentKey.isEmpty()) return
        prefs.edit().putInt("page_" + currentKey.hashCode(), currentPage()).apply()
    }

    // ---------------------------------------------------------------- margin crop

    private fun pageBox(r: PdfRenderer, idx: Int): FloatArray {
        pageBoxCache[idx]?.let { return it }
        var box = floatArrayOf(0f, 0f, 1f, 1f)
        try {
            val page = r.openPage(idx)
            val w = 100
            val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            b.recycle()
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = px[y * w + x]
                    val lum = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                    if (lum < 235) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX > minX && maxY > minY) {
                val pad = 0.03f
                box = floatArrayOf(
                    (minX.toFloat() / w - pad).coerceAtLeast(0f),
                    (minY.toFloat() / h - pad).coerceAtLeast(0f),
                    ((maxX + 1).toFloat() / w + pad).coerceAtMost(1f),
                    ((maxY + 1).toFloat() / h + pad).coerceAtMost(1f)
                )
            }
        } catch (e: Exception) { }
        pageBoxCache[idx] = box
        return box
    }

    // one common crop box for the whole document, from a few sample pages
    private fun docBox(r: PdfRenderer): FloatArray {
        val c = docBoxCache
        if (c != null) return c
        val n = r.pageCount
        val step = max(1, n / 8)
        var l = 1f
        var t = 1f
        var rr = 0f
        var b = 0f
        var i = 0
        while (i < n) {
            val bx = pageBox(r, i)
            l = min(l, bx[0])
            t = min(t, bx[1])
            rr = max(rr, bx[2])
            b = max(b, bx[3])
            i += step
        }
        val res = if (rr > l && b > t) floatArrayOf(l, t, rr, b) else floatArrayOf(0f, 0f, 1f, 1f)
        docBoxCache = res
        return res
    }

    // ---------------------------------------------------------------- page list

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
            val filter = pageFilter()
            for (i in 0 until per) {
                val idx = position * per + i
                val iv = holder.imgs[i]
                if (idx >= r.pageCount) {
                    iv.setImageDrawable(null)
                    continue
                }
                try {
                    synchronized(r) {
                        val box = if (crop) docBox(r) else null
                        val bl = box?.get(0) ?: 0f
                        val bt = box?.get(1) ?: 0f
                        val br = box?.get(2) ?: 1f
                        val bb = box?.get(3) ?: 1f
                        val page = r.openPage(idx)
                        val pw = page.width.toFloat()
                        val ph = page.height.toFloat()
                        val cw = (br - bl) * pw
                        val ch = (bb - bt) * ph
                        var w: Float
                        var h: Float
                        if (fit) {
                            val f = min(cellW / cw, vh / ch)
                            w = cw * f
                            h = ch * f
                        } else {
                            w = cellW.toFloat()
                            h = w * ch / cw
                        }
                        var fw = w / (br - bl)
                        if (fw > 3600f) {
                            val k = 3600f / fw
                            w *= k
                            h *= k
                            fw = 3600f
                        }
                        val fh = h / (bb - bt)
                        val full = Bitmap.createBitmap(
                            fw.toInt().coerceAtLeast(1), fh.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
                        )
                        full.eraseColor(Color.WHITE)
                        page.render(full, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        val bmp: Bitmap
                        if (box == null) {
                            bmp = full
                        } else {
                            val x = (bl * full.width).toInt().coerceIn(0, full.width - 1)
                            val y = (bt * full.height).toInt().coerceIn(0, full.height - 1)
                            val cw2 = w.toInt().coerceIn(1, full.width - x)
                            val ch2 = h.toInt().coerceIn(1, full.height - y)
                            bmp = Bitmap.createBitmap(full, x, y, cw2, ch2)
                            full.recycle()
                        }
                        iv.setImageBitmap(bmp)
                    }
                } catch (e: OutOfMemoryError) {
                    iv.setImageDrawable(null)
                }
                iv.colorFilter = filter
            }
        }

        override fun onViewRecycled(holder: VH) {
            for (iv in holder.imgs) iv.setImageDrawable(null)
        }
    }
}
