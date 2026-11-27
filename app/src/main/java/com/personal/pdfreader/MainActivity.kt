package com.personal.pdfreader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class Tile(val label: String, val icon: Int, val color: String, val ready: Boolean, val action: () -> Unit)

class MainActivity : Activity() {

    private lateinit var list: RecyclerView
    private lateinit var msg: TextView
    private lateinit var permBtn: Button
    private lateinit var search: EditText
    private lateinit var sortBtn: Button
    private lateinit var toolsView: ScrollView
    private lateinit var fab: TextView
    private val tabBtns = ArrayList<Button>()
    private var all: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var favSet: Set<String> = emptySet()
    private var tab = 0 // 0 Home, 1 Recent, 2 Favorites, 3 Tools, 4 Bin
    private var sortMode = 0
    private var scanning = false
    private var lastScan = 0L
    private val sortNames = arrayOf("Date", "Name", "Size")
    private val adapter = FileAdapter()
    private val prefs: SharedPreferences by lazy { getSharedPreferences("reader", MODE_PRIVATE) }

    private val reqImport = 2
    private val reqImgs = 3
    private val reqPdf2Img = 4
    private val reqPrint = 5
    private val reqShare = 6

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            permBtn.visibility = View.GONE
            refresh()
            if (!scanning && (all.isEmpty() || System.currentTimeMillis() - lastScan > 20000)) scan()
        } else {
            permBtn.visibility = View.VISIBLE
            msg.visibility = View.VISIBLE
            msg.text = "Phone ki PDF files dikhane ke liye \"All files access\" ki permission chahiye. Ye app internet use nahi karti."
            all = emptyList()
            refresh()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun smallBtn(t: String, click: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 12f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setOnClickListener { click() }
        return b
    }

    private fun buildUi() {
        val frame = FrameLayout(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F6F6F8"))
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(16), dp(8), dp(8), 0)
        val title = TextView(this)
        title.text = "PDF Reader"
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        top.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(smallBtn("Import") {
            if (ensureAccess()) pickPdf(reqImport)
        })
        sortBtn = smallBtn("Sort: Date") { cycleSort() }
        top.addView(sortBtn)
        val more = smallBtn("\u22EE") { }
        more.setOnClickListener { showTopMenu(more) }
        top.addView(more)
        root.addView(top)

        search = EditText(this)
        search.hint = "Search PDF..."
        search.isSingleLine = true
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.setMargins(dp(12), 0, dp(12), 0)
        root.addView(search, slp)

        permBtn = smallBtn("Permission do") { askAccess() }
        permBtn.visibility = View.GONE
        root.addView(permBtn)

        msg = TextView(this)
        msg.gravity = Gravity.CENTER
        msg.setPadding(dp(20), dp(20), dp(20), dp(20))
        msg.visibility = View.GONE
        root.addView(msg)

        list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        toolsView = buildTools()
        toolsView.visibility = View.GONE
        root.addView(toolsView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Color.WHITE)
        val names = arrayOf("Home", "Recent", "Favorites", "Tools")
        for (i in names.indices) {
            val b = Button(this)
            b.text = names[i]
            b.isAllCaps = false
            b.textSize = 13f
            b.setOnClickListener { tab = i; refresh() }
            b.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            tabBtns.add(b)
            bar.addView(b)
        }
        root.addView(bar)

        fab = TextView(this)
        fab.text = "+"
        fab.textSize = 30f
        fab.setTextColor(Color.WHITE)
        fab.gravity = Gravity.CENTER
        val fbg = GradientDrawable()
        fbg.shape = GradientDrawable.OVAL
        fbg.setColor(Color.parseColor("#D32F2F"))
        fab.background = fbg
        fab.elevation = dp(6).toFloat()
        fab.contentDescription = "Create PDF from images"
        fab.setOnClickListener { pickImages() }
        val flp = FrameLayout.LayoutParams(dp(56), dp(56))
        flp.gravity = Gravity.BOTTOM or Gravity.END
        flp.setMargins(0, 0, dp(20), dp(84))
        frame.addView(fab, flp)

        setContentView(frame)
    }

    private fun showTopMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Refresh")
        pm.menu.add(0, 2, 1, "Reading mode settings")
        pm.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> if (hasAccess()) scan()
                2 -> readingLimitDialog()
            }
            true
        }
        pm.show()
    }

    private fun readingLimitDialog() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(prefs.getInt("readLimit", 30).toString())
        AlertDialog.Builder(this)
            .setTitle("Reading mode kitne page ke baad?")
            .setMessage("Itne ya zyada page ki PDF Reading mode mein khulegi.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n != null && n >= 2) prefs.edit().putInt("readLimit", n).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- Tools

    private fun soon(step: Int) {
        toast("Ye Step " + step + " mein aayega")
    }

    private fun buildTools(): ScrollView {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(4), dp(12), dp(16))
        sv.addView(col)

        section(col, "Convert", listOf(
            Tile("PDF to Image", R.drawable.t_pdf2img, "#2F6FED", true) { if (ensureAccess()) pickPdf(reqPdf2Img) },
            Tile("Image to PDF", R.drawable.t_img2pdf, "#E8741F", true) { pickImages() },
            Tile("Scan to PDF", R.drawable.t_scan, "#12A3A8", false) { soon(4) }
        ))
        section(col, "Edit & Organize", listOf(
            Tile("Merge", R.drawable.t_merge, "#7C4DFF", false) { soon(3) },
            Tile("Split", R.drawable.t_split, "#2E9E5B", false) { soon(3) },
            Tile("Reorder pages", R.drawable.t_reorder, "#E8741F", false) { soon(3) },
            Tile("Compress", R.drawable.t_compress, "#2F6FED", false) { soon(3) },
            Tile("Watermark", R.drawable.t_watermark, "#E0407A", false) { soon(3) }
        ))
        section(col, "Manage & Protect", listOf(
            Tile("Set password", R.drawable.t_lock, "#2E9E5B", false) { soon(3) },
            Tile("Remove password", R.drawable.t_unlock, "#7C4DFF", false) { soon(3) },
            Tile("Print", R.drawable.t_print, "#5B3FD1", true) { pickPdf(reqPrint) },
            Tile("Share", R.drawable.t_share, "#12A3A8", true) { pickPdf(reqShare) },
            Tile("Recycle bin", R.drawable.t_bin, "#D32F2F", true) { tab = 4; refresh() }
        ))
        return sv
    }

    private fun section(col: LinearLayout, title: String, tiles: List<Tile>) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(8), dp(12), dp(8), dp(4))
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dp(16).toFloat()
        card.background = bg
        val h = TextView(this)
        h.text = title
        h.textSize = 15f
        h.setTypeface(null, Typeface.BOLD)
        h.setPadding(dp(6), 0, 0, dp(10))
        card.addView(h)
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until 4) {
                if (i + j < tiles.size) {
                    row.addView(tileView(tiles[i + j]), LinearLayout.LayoutParams(0, WRAP, 1f))
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
            card.addView(row)
            i += 4
        }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(8), 0, 0)
        col.addView(card, lp)
    }

    private fun tileView(t: Tile): View {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER_HORIZONTAL
        v.setPadding(dp(2), 0, dp(2), dp(10))
        val iv = ImageView(this)
        iv.setImageResource(t.icon)
        iv.setPadding(dp(12), dp(12), dp(12), dp(12))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor(t.color))
        bg.cornerRadius = dp(14).toFloat()
        iv.background = bg
        v.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
        val tv = TextView(this)
        tv.text = t.label
        tv.textSize = 12f
        tv.gravity = Gravity.CENTER
        tv.maxLines = 2
        tv.setPadding(0, dp(6), 0, 0)
        v.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        if (!t.ready) {
            val s = TextView(this)
            s.text = "Soon"
            s.textSize = 10f
            s.setTextColor(Color.GRAY)
            s.gravity = Gravity.CENTER
            v.addView(s)
            v.alpha = 0.5f
        }
        v.setOnClickListener { t.action() }
        return v
    }

    // ---------------------------------------------------------------- permission and scan

    private fun hasAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureAccess(): Boolean {
        if (hasAccess()) return true
        toast("Pehle permission do")
        askAccess()
        return false
    }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + packageName)))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 10
            )
        }
    }

    private fun scan() {
        if (scanning) return
        scanning = true
        refresh()
        Thread {
            val out = ArrayList<Item>()
            try { walk(Environment.getExternalStorageDirectory(), 0, out) } catch (e: Exception) { }
            runOnUiThread {
                all = out
                scanning = false
                lastScan = System.currentTimeMillis()
                refresh()
            }
        }.start()
    }

    private fun walk(dir: File, depth: Int, out: MutableList<Item>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && n == "Android") {
                    val media = File(f, "media")
                    if (media.isDirectory) walk(media, 1, out)
                    continue
                }
                if (depth < 10) walk(f, depth + 1, out)
            } else if (n.endsWith(".pdf", true)) {
                out.add(Item(f.absolutePath, n, f.length(), f.lastModified()))
            }
        }
    }

    private fun cycleSort() {
        sortMode = (sortMode + 1) % 3
        sortBtn.text = "Sort: " + sortNames[sortMode]
        refresh()
    }

    private fun toItems(paths: Collection<String>): List<Item> {
        val out = ArrayList<Item>()
        for (p in paths) {
            val f = File(p)
            if (f.exists()) out.add(Item(f.absolutePath, f.name, f.length(), f.lastModified()))
        }
        return out
    }

    private fun refresh() {
        favSet = Store.favs(this)
        for (i in tabBtns.indices) {
            val on = if (i == 3) tab >= 3 else i == tab
            tabBtns[i].alpha = if (on) 1f else 0.5f
            tabBtns[i].setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        val tools = tab == 3
        toolsView.visibility = if (tools) View.VISIBLE else View.GONE
        list.visibility = if (tools) View.GONE else View.VISIBLE
        search.visibility = if (tools) View.GONE else View.VISIBLE
        fab.visibility = if (tab == 4) View.GONE else View.VISIBLE
        if (tools) {
            msg.visibility = View.GONE
            return
        }
        val base: List<Item> = when (tab) {
            0 -> all
            1 -> toItems(Store.recents(this))
            2 -> toItems(favSet)
            else -> Store.trashItems(this)
        }
        val q = search.text.toString().trim()
        var l: List<Item> = if (q.isEmpty()) base else base.filter { it.name.contains(q, true) }
        if (tab != 1) {
            l = when (sortMode) {
                0 -> l.sortedByDescending { it.mod }
                1 -> l.sortedBy { it.name.lowercase() }
                else -> l.sortedByDescending { it.size }
            }
        }
        shown = l
        adapter.notifyDataSetChanged()
        if (tab != 4 && !hasAccess()) return
        if (shown.isEmpty() && scanning && tab == 0) {
            msg.visibility = View.VISIBLE
            msg.text = "PDF files dhundh raha hu..."
        } else if (shown.isEmpty()) {
            msg.visibility = View.VISIBLE
            msg.text = when (tab) {
                1 -> "Abhi koi recent file nahi hai"
                2 -> "Koi favorite nahi hai. File ke 3-dot menu se favorite karo"
                4 -> "Bin khaali hai"
                else -> "Koi PDF nahi mili"
            }
        } else {
            msg.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- pickers and background jobs

    private fun pickPdf(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        startActivityForResult(i, code)
    }

    private fun pickImages() {
        if (!ensureAccess()) return
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(Intent.createChooser(i, "Photos chuno"), reqImgs)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data
        when (requestCode) {
            reqImgs -> {
                val picked = ArrayList<Uri>()
                val cd = data.clipData
                if (cd != null) {
                    for (i in 0 until cd.itemCount) picked.add(cd.getItemAt(i).uri)
                } else if (uri != null) {
                    picked.add(uri)
                }
                if (picked.isNotEmpty()) {
                    val prev = Convert.loadPreview(this, picked[0], 450)
                    convertDialog("PDF kaisi banani hai?", prev, true, false) { f, pm, rot, _ ->
                        imagesToPdf(picked, f, pm, rot)
                    }
                }
            }
            reqImport -> if (uri != null) importPdf(uri)
            reqPdf2Img -> if (uri != null) askPdfToImage(null, uri, displayName(uri).removeSuffix(".pdf"))
            reqPrint -> if (uri != null) Convert.print(this, null, uri, displayName(uri))
            reqShare -> if (uri != null) shareUri(uri)
        }
    }

    private fun <T> runBg(message: String, job: () -> T, done: (T?) -> Unit) {
        val dlg = AlertDialog.Builder(this).setMessage(message).setCancelable(false).create()
        dlg.show()
        Thread {
            var res: T? = null
            try {
                res = job()
            } catch (e: Throwable) { }
            runOnUiThread {
                dlg.dismiss()
                done(res)
            }
        }.start()
    }

    // one dialog for "Image to PDF" and "PDF to Image": preview + filter (+ page size / format)
    private fun convertDialog(
        title: String,
        preview: Bitmap?,
        withPage: Boolean,
        withFormat: Boolean,
        onOk: (Int, Int, Boolean, Int) -> Unit
    ) {
        var filter = 0
        var pageMode = 0
        var rotate = false
        var format = 0

        val sv = android.widget.ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), dp(8))
        sv.addView(col)

        var pv: ImageView? = null
        if (preview != null) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setBackgroundColor(Color.parseColor("#E4E4EA"))
            col.addView(iv, LinearLayout.LayoutParams(MATCH, dp(220)))
            pv = iv
        }
        fun showPreview() {
            val p = preview
            val iv = pv
            if (p != null && iv != null) iv.setImageBitmap(Convert.applyFilter(p, filter))
        }
        fun label(t: String) {
            val v = TextView(this)
            v.text = t
            v.textSize = 12f
            v.setTextColor(Color.GRAY)
            v.setPadding(0, dp(14), 0, dp(4))
            col.addView(v)
        }
        fun group(opts: Array<String>, horizontal: Boolean, cb: (Int) -> Unit): RadioGroup {
            val g = RadioGroup(this)
            g.orientation = if (horizontal) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
            for (o in opts) {
                val rb = RadioButton(this)
                rb.text = o
                rb.textSize = 13f
                rb.id = View.generateViewId()
                g.addView(rb)
            }
            (g.getChildAt(0) as RadioButton).isChecked = true
            g.setOnCheckedChangeListener { grp, id -> cb(grp.indexOfChild(grp.findViewById(id))) }
            return g
        }

        label("FILTER")
        val hs = HorizontalScrollView(this)
        hs.addView(group(Convert.filterNames, true) { i ->
            filter = i
            showPreview()
        })
        col.addView(hs)

        if (withPage) {
            label("PAGE")
            col.addView(group(arrayOf("A4 Portrait (seedha page)", "A4 Landscape (aada page)", "Photo jitna bada page"), false) { i ->
                pageMode = i
            })
            val cb = CheckBox(this)
            cb.text = "Photo ko page ke hisaab se ghuma do (page bhar jaye)"
            cb.setOnCheckedChangeListener { _, b -> rotate = b }
            col.addView(cb)
        }
        if (withFormat) {
            label("FORMAT")
            col.addView(group(arrayOf("JPG (chhoti file)", "PNG (best quality)"), true) { i ->
                format = i
            })
        }
        showPreview()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("Banao") { _, _ -> onOk(filter, pageMode, rotate, format) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun imagesToPdf(uris: List<Uri>, filter: Int, pageMode: Int, rotate: Boolean) {
        runBg("PDF ban rahi hai...", { Convert.imagesToPdf(this, uris, filter, pageMode, rotate) }) { f ->
            if (f != null) {
                toast("Ban gayi: Documents/PDF Reader/" + f.name)
                all = all + Item(f.absolutePath, f.name, f.length(), f.lastModified())
                refresh()
                openPath(f.absolutePath)
            } else {
                toast("PDF nahi ban payi")
            }
        }
    }

    private fun askPdfToImage(path: String?, uri: Uri?, baseName: String) {
        val prev: Bitmap? = try {
            Convert.pagePreview(this, path, uri, 450)
        } catch (e: Exception) {
            null
        }
        convertDialog("Images kaisi banani hain?", prev, false, true) { f, _, _, fmt ->
            pdfToImages(path, uri, baseName, f, fmt)
        }
    }

    private fun pdfToImages(path: String?, uri: Uri?, baseName: String, filter: Int, format: Int) {
        runBg("Images ban rahi hain...", { Convert.pdfToImages(this, path, uri, baseName, filter, format) }) { folder ->
            if (folder != null) {
                val n = folder.listFiles()?.size ?: 0
                AlertDialog.Builder(this)
                    .setTitle("Ho gaya")
                    .setMessage(n.toString() + " images Pictures/PDF Reader/" + folder.name + " mein save ho gayi. Gallery mein bhi dikhengi.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                toast("Images nahi ban payi (password wali ya kharab PDF?)")
            }
        }
    }

    private fun importPdf(uri: Uri) {
        runBg("Import ho raha hai...", {
            val name = displayName(uri)
            var f = File(Convert.outDir(), name)
            if (f.exists()) f = File(Convert.outDir(), f.nameWithoutExtension + "_" + Convert.stamp() + ".pdf")
            contentResolver.openInputStream(uri)!!.use { i ->
                FileOutputStream(f).use { o -> i.copyTo(o) }
            }
            f
        }) { f ->
            if (f != null) {
                all = all + Item(f.absolutePath, f.name, f.length(), f.lastModified())
                refresh()
                openPath(f.absolutePath)
            } else {
                toast("Import nahi ho paya")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var n = "document.pdf"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) n = c.getString(i)
            }
        } catch (e: Exception) { }
        if (!n.endsWith(".pdf", true)) n += ".pdf"
        return n
    }

    private fun shareUri(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "application/pdf"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share PDF"))
    }

    private fun openPath(path: String) {
        val i = Intent(this, ViewerActivity::class.java)
        i.putExtra("path", path)
        startActivity(i)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmt(b: Long): String = when {
        b >= 1048576 -> String.format(Locale.US, "%.2f MB", b / 1048576.0)
        b >= 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> b.toString() + " B"
    }

    // ---------------------------------------------------------------- file menu

    private fun showMenu(anchor: View, item: Item) {
        val pm = PopupMenu(this, anchor)
        if (tab == 4) {
            pm.menu.add(0, 6, 0, "Restore")
            pm.menu.add(0, 7, 1, "Delete forever")
        } else {
            pm.menu.add(0, 1, 0, if (favSet.contains(item.path)) "Remove favorite" else "Add to favorites")
            pm.menu.add(0, 2, 1, "Share")
            pm.menu.add(0, 8, 2, "Print")
            pm.menu.add(0, 9, 3, "Convert to images")
            pm.menu.add(0, 3, 4, "Rename")
            pm.menu.add(0, 4, 5, "Details")
            pm.menu.add(0, 5, 6, "Delete")
        }
        pm.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> { Store.toggleFav(this, item.path); refresh() }
                2 -> share(item)
                3 -> rename(item)
                4 -> details(item)
                5 -> confirmDelete(item)
                6 -> {
                    val r = Store.restore(this, File(item.path))
                    if (r != null) {
                        toast("Restore ho gayi: " + r.name)
                        all = all + Item(r.absolutePath, r.name, r.length(), r.lastModified())
                    } else {
                        toast("Restore nahi ho payi")
                    }
                    refresh()
                }
                7 -> AlertDialog.Builder(this)
                    .setTitle("Hamesha ke liye delete?")
                    .setMessage(item.name)
                    .setPositiveButton("Delete") { _, _ ->
                        Store.deleteForever(this, File(item.path))
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                8 -> Convert.print(this, item.path, null, item.name)
                9 -> if (ensureAccess()) askPdfToImage(item.path, null, File(item.path).nameWithoutExtension)
            }
            true
        }
        pm.show()
    }

    private fun share(item: Item) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", File(item.path))
            val i = Intent(Intent.ACTION_SEND)
            i.type = "application/pdf"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share PDF"))
        } catch (e: Exception) {
            toast("Share nahi ho paya")
        }
    }

    private fun rename(item: Item) {
        val et = EditText(this)
        et.setText(File(item.path).nameWithoutExtension)
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                var n = et.text.toString().trim().replace("/", "")
                if (n.isNotEmpty()) {
                    if (!n.endsWith(".pdf", true)) n += ".pdf"
                    val f = File(item.path)
                    val dst = File(f.parentFile, n)
                    if (dst.exists()) {
                        toast("Is naam ki file pehle se hai")
                    } else if (f.renameTo(dst)) {
                        Store.renamePath(this, f.absolutePath, dst.absolutePath)
                        all = all.map { x ->
                            if (x.path == item.path) Item(dst.absolutePath, dst.name, x.size, x.mod) else x
                        }
                        refresh()
                    } else {
                        toast("Rename nahi ho paya")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun details(item: Item) {
        val d = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.getDefault()).format(Date(item.mod))
        AlertDialog.Builder(this)
            .setTitle("Details")
            .setMessage("Naam: " + item.name + "\n\nSize: " + fmt(item.size) + "\n\nModified: " + d + "\n\nJagah: " + item.path)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setTitle("Bin mein bhejna hai?")
            .setMessage(item.name + "\n\nBin se wapas la sakte ho.")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    Store.toTrash(this, File(item.path))
                    all = all.filter { x -> x.path != item.path }
                    toast("Bin mein bhej di")
                } catch (e: Exception) {
                    toast("Delete nahi ho paya")
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- list adapter

    inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        inner class VH(v: View, val name: TextView, val meta: TextView, val more: TextView) :
            RecyclerView.ViewHolder(v)

        override fun getItemCount(): Int = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(10), dp(4), dp(10))
            row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)

            val badge = TextView(parent.context)
            badge.text = "PDF"
            badge.setTextColor(Color.WHITE)
            badge.setBackgroundColor(Color.parseColor("#D32F2F"))
            badge.gravity = Gravity.CENTER
            badge.textSize = 11f
            badge.setTypeface(null, Typeface.BOLD)
            row.addView(badge, LinearLayout.LayoutParams(dp(44), dp(52)))

            val col = LinearLayout(parent.context)
            col.orientation = LinearLayout.VERTICAL
            val clp = LinearLayout.LayoutParams(0, WRAP, 1f)
            clp.setMargins(dp(12), 0, dp(4), 0)
            val name = TextView(parent.context)
            name.textSize = 16f
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            val meta = TextView(parent.context)
            meta.textSize = 12f
            meta.setTextColor(Color.GRAY)
            col.addView(name)
            col.addView(meta)
            row.addView(col, clp)

            val more = TextView(parent.context)
            more.text = "\u22EE"
            more.textSize = 24f
            more.setPadding(dp(14), dp(8), dp(14), dp(8))
            row.addView(more)
            return VH(row, name, meta, more)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = shown[position]
            h.name.text = item.name
            val star = if (tab != 4 && favSet.contains(item.path)) "\u2605 " else ""
            val d = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).format(Date(item.mod))
            h.meta.text = star + fmt(item.size) + "  \u2022  " + d
            h.itemView.setOnClickListener {
                if (tab == 4) toast("Restore ya delete ke liye 3-dot dabao") else openPath(item.path)
            }
            h.more.setOnClickListener { showMenu(h.more, item) }
        }
    }
}
