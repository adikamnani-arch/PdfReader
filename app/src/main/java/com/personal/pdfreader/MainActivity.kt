package com.personal.pdfreader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private lateinit var list: RecyclerView
    private lateinit var msg: TextView
    private lateinit var permBtn: Button
    private lateinit var search: EditText
    private lateinit var sortBtn: Button
    private val tabBtns = ArrayList<Button>()
    private var all: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var favSet: Set<String> = emptySet()
    private var tab = 0
    private var sortMode = 0
    private var scanning = false
    private val sortNames = arrayOf("Date", "Name", "Size")
    private val adapter = FileAdapter()
    private val reqPick = 2

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            permBtn.visibility = View.GONE
            if (all.isEmpty() && !scanning) scan() else refresh()
        } else {
            permBtn.visibility = View.VISIBLE
            msg.visibility = View.VISIBLE
            msg.text = "Phone ki PDF files dikhane ke liye \"All files access\" ki permission chahiye. Ye app internet use nahi karti."
            all = emptyList()
            refresh()
        }
    }

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
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(16), dp(8), dp(8), 0)
        val title = TextView(this)
        title.text = "PDF Reader"
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        top.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(smallBtn("Open") { pick() })
        sortBtn = smallBtn("Sort: Date") { cycleSort() }
        top.addView(sortBtn)
        top.addView(smallBtn("Refresh") { if (hasAccess()) scan() })
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

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("Home", "Recent", "Favorites", "Bin")
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

        setContentView(root)
    }

    private fun hasAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
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
                if (depth == 0 && n == "Android") continue
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
        for (i in tabBtns.indices) {
            tabBtns[i].alpha = if (i == tab) 1f else 0.5f
            tabBtns[i].setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        if (!hasAccess()) return
        if (scanning && tab == 0) {
            msg.visibility = View.VISIBLE
            msg.text = "PDF files dhundh raha hu..."
        } else if (shown.isEmpty()) {
            msg.visibility = View.VISIBLE
            msg.text = when (tab) {
                1 -> "Abhi koi recent file nahi hai"
                2 -> "Koi favorite nahi hai. File ke 3-dot menu se favorite karo"
                3 -> "Bin khaali hai"
                else -> "Koi PDF nahi mili"
            }
        } else {
            msg.visibility = View.GONE
        }
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        startActivityForResult(i, reqPick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == reqPick && resultCode == RESULT_OK && uri != null) {
            val i = Intent(this, ViewerActivity::class.java)
            i.action = Intent.ACTION_VIEW
            i.data = uri
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        }
    }

    private fun openItem(item: Item) {
        val i = Intent(this, ViewerActivity::class.java)
        i.putExtra("path", item.path)
        startActivity(i)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmt(b: Long): String = when {
        b >= 1048576 -> String.format(Locale.US, "%.2f MB", b / 1048576.0)
        b >= 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> b.toString() + " B"
    }

    private fun showMenu(anchor: View, item: Item) {
        val pm = PopupMenu(this, anchor)
        if (tab == 3) {
            pm.menu.add(0, 6, 0, "Restore")
            pm.menu.add(0, 7, 1, "Delete forever")
        } else {
            pm.menu.add(0, 1, 0, if (favSet.contains(item.path)) "Remove favorite" else "Add to favorites")
            pm.menu.add(0, 2, 1, "Share")
            pm.menu.add(0, 3, 2, "Rename")
            pm.menu.add(0, 4, 3, "Details")
            pm.menu.add(0, 5, 4, "Delete")
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
                        scan()
                    } else toast("Restore nahi ho payi")
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
            val star = if (tab != 3 && favSet.contains(item.path)) "\u2605 " else ""
            val d = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).format(Date(item.mod))
            h.meta.text = star + fmt(item.size) + "  \u2022  " + d
            h.itemView.setOnClickListener {
                if (tab == 3) toast("Restore ya delete ke liye 3-dot dabao") else openItem(item)
            }
            h.more.setOnClickListener { showMenu(h.more, item) }
        }
    }
}
