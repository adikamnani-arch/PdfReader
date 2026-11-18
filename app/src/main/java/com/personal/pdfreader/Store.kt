package com.personal.pdfreader

import android.content.Context
import java.io.File

data class Item(val path: String, val name: String, val size: Long, val mod: Long)

object Store {
    private fun lib(c: Context) = c.getSharedPreferences("lib", Context.MODE_PRIVATE)
    private fun trashPrefs(c: Context) = c.getSharedPreferences("trash", Context.MODE_PRIVATE)

    fun favs(c: Context): MutableSet<String> =
        HashSet(lib(c).getStringSet("favs", emptySet()) ?: emptySet())

    private fun saveFavs(c: Context, s: Set<String>) {
        lib(c).edit().putStringSet("favs", HashSet(s)).apply()
    }

    fun toggleFav(c: Context, path: String): Boolean {
        val s = favs(c)
        val now: Boolean
        if (s.contains(path)) { s.remove(path); now = false } else { s.add(path); now = true }
        saveFavs(c, s)
        return now
    }

    fun recents(c: Context): MutableList<String> {
        val s = lib(c).getString("recents", "") ?: ""
        return if (s.isEmpty()) ArrayList() else ArrayList(s.split("\n"))
    }

    private fun saveRecents(c: Context, l: List<String>) {
        lib(c).edit().putString("recents", l.joinToString("\n")).apply()
    }

    fun addRecent(c: Context, path: String) {
        val l = recents(c)
        l.remove(path)
        l.add(0, path)
        while (l.size > 30) l.removeAt(l.size - 1)
        saveRecents(c, l)
    }

    fun forget(c: Context, path: String) {
        val l = recents(c)
        l.remove(path)
        saveRecents(c, l)
        val f = favs(c)
        if (f.remove(path)) saveFavs(c, f)
    }

    fun renamePath(c: Context, old: String, new: String) {
        val l = recents(c)
        val i = l.indexOf(old)
        if (i >= 0) { l[i] = new; saveRecents(c, l) }
        val f = favs(c)
        if (f.remove(old)) { f.add(new); saveFavs(c, f) }
    }

    fun trashDir(c: Context): File {
        val d = File(c.filesDir, "trash")
        d.mkdirs()
        return d
    }

    fun moveFile(src: File, dst: File) {
        if (!src.renameTo(dst)) {
            src.copyTo(dst, true)
            src.delete()
        }
    }

    fun toTrash(c: Context, file: File) {
        val name = System.currentTimeMillis().toString() + "_" + file.name
        val dst = File(trashDir(c), name)
        moveFile(file, dst)
        trashPrefs(c).edit().putString(name, file.absolutePath).apply()
        forget(c, file.absolutePath)
    }

    fun trashItems(c: Context): List<Item> {
        val m = trashPrefs(c).all
        val files = trashDir(c).listFiles() ?: return emptyList()
        val out = ArrayList<Item>()
        for (f in files) {
            if (!f.isFile) continue
            val orig = (m[f.name] as? String) ?: f.name
            out.add(Item(f.absolutePath, File(orig).name, f.length(), f.lastModified()))
        }
        return out
    }

    fun restore(c: Context, trashFile: File): File? {
        val origPath = trashPrefs(c).getString(trashFile.name, null) ?: return null
        var dest = File(origPath)
        dest.parentFile?.mkdirs()
        if (dest.exists()) {
            dest = File(dest.parentFile, dest.nameWithoutExtension + "_restored.pdf")
        }
        moveFile(trashFile, dest)
        trashPrefs(c).edit().remove(trashFile.name).apply()
        return dest
    }

    fun deleteForever(c: Context, trashFile: File) {
        trashFile.delete()
        trashPrefs(c).edit().remove(trashFile.name).apply()
    }
}
