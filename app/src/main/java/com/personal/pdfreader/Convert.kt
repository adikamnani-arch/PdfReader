package com.personal.pdfreader

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

object Convert {

    val filterNames = arrayOf("Original", "Clear", "Magic colour", "Grayscale", "Black & White")

    private fun matrixFor(f: Int): ColorMatrix {
        return when (f) {
            1 -> {
                val g = 1.25f
                val t = (0.5f - 0.5f * g) * 255f + 8f
                ColorMatrix(floatArrayOf(
                    g, 0f, 0f, 0f, t,
                    0f, g, 0f, 0f, t,
                    0f, 0f, g, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            2 -> {
                val g = 1.6f
                val t = -55f
                ColorMatrix(floatArrayOf(
                    g, 0f, 0f, 0f, t,
                    0f, g, 0f, 0f, t,
                    0f, 0f, g, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            3 -> {
                val m = ColorMatrix()
                m.setSaturation(0f)
                m
            }
            else -> {
                val k = 8f
                val t = 128f - k * 128f
                ColorMatrix(floatArrayOf(
                    k * 0.299f, k * 0.587f, k * 0.114f, 0f, t,
                    k * 0.299f, k * 0.587f, k * 0.114f, 0f, t,
                    k * 0.299f, k * 0.587f, k * 0.114f, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
        }
    }

    // returns a new bitmap for filters 1..4, the same bitmap for 0 (Original)
    fun applyFilter(src: Bitmap, f: Int): Bitmap {
        if (f == 0) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(matrixFor(f))
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }

    fun loadPreview(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = loadBitmap(ctx, uri, maxSide)

    fun pagePreview(ctx: Context, path: String?, uri: Uri?, w: Int): Bitmap {
        val pfd = openPfd(ctx, path, uri)
        val r = PdfRenderer(pfd)
        val page = r.openPage(0)
        val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        r.close()
        pfd.close()
        return bmp
    }

    fun outDir(): File {
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PDF Reader")
        d.mkdirs()
        return d
    }

    fun picDir(): File {
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PDF Reader")
        d.mkdirs()
        return d
    }

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (o.outWidth / s > maxSide * 2 || o.outHeight / s > maxSide * 2) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            var b: Bitmap = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: return null
            var rot = 0
            try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    val ex = ExifInterface(it)
                    val ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    rot = when (ori) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (e: Exception) { }
            if (rot != 0) {
                val m = Matrix()
                m.postRotate(rot.toFloat())
                val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                b.recycle()
                b = r
            }
            return b
        } catch (e: Exception) {
            return null
        }
    }

    // pageMode: 0 = A4 portrait, 1 = A4 landscape, 2 = page as big as the photo
    fun imagesToPdf(ctx: Context, uris: List<Uri>, filter: Int, pageMode: Int, rotateFill: Boolean): File {
        val doc = PdfDocument()
        var n = 0
        for (u in uris) {
            var bmp: Bitmap = loadBitmap(ctx, u, 2000) ?: continue
            n++
            if (filter != 0) {
                val fb = applyFilter(bmp, filter)
                bmp.recycle()
                bmp = fb
            }
            val pw: Int
            val ph: Int
            if (pageMode == 2) {
                pw = 595
                ph = (595f * bmp.height / bmp.width).toInt().coerceAtLeast(1)
            } else if (pageMode == 1) {
                pw = 842
                ph = 595
            } else {
                pw = 595
                ph = 842
            }
            if (pageMode != 2 && rotateFill) {
                val imgLandscape = bmp.width > bmp.height
                val pageLandscape = pw > ph
                if (imgLandscape != pageLandscape) {
                    val m = Matrix()
                    m.postRotate(90f)
                    val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    bmp.recycle()
                    bmp = r
                }
            }
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)
            val margin = if (pageMode == 2) 0f else 20f
            val s = min((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
            val dw = bmp.width * s
            val dh = bmp.height * s
            val left = (pw - dw) / 2f
            val top = (ph - dh) / 2f
            c.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
            bmp.recycle()
        }
        if (n == 0) {
            doc.close()
            throw IllegalStateException("no images")
        }
        val f = File(outDir(), "Images_" + stamp() + ".pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun openPfd(ctx: Context, path: String?, uri: Uri?): ParcelFileDescriptor {
        return if (path != null) {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            ctx.contentResolver.openFileDescriptor(uri!!, "r")!!
        }
    }

    // returns the folder that holds the images
    fun pdfToImages(ctx: Context, path: String?, uri: Uri?, baseName: String, filter: Int, format: Int): File {
        val pfd = openPfd(ctx, path, uri)
        val r = PdfRenderer(pfd)
        val out = File(picDir(), baseName + "_" + stamp())
        out.mkdirs()
        val files = ArrayList<String>()
        for (i in 0 until r.pageCount) {
            val page = r.openPage(i)
            val w = 1600
            val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            var bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            page.close()
            if (filter != 0) {
                val fb = applyFilter(bmp, filter)
                bmp.recycle()
                bmp = fb
            }
            val ext = if (format == 1) "png" else "jpg"
            val cf = if (format == 1) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val f = File(out, "page_" + (i + 1) + "." + ext)
            FileOutputStream(f).use { bmp.compress(cf, 92, it) }
            bmp.recycle()
            files.add(f.absolutePath)
        }
        r.close()
        pfd.close()
        MediaScannerConnection.scanFile(ctx, files.toTypedArray(), null, null)
        return out
    }

    fun print(act: Activity, path: String?, uri: Uri?, name: String) {
        val pm = act.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: PrintDocumentAdapter.LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal != null && cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: PrintDocumentAdapter.WriteResultCallback
            ) {
                try {
                    val input: InputStream = if (path != null) {
                        FileInputStream(path)
                    } else {
                        act.contentResolver.openInputStream(uri!!)!!
                    }
                    input.use { i ->
                        FileOutputStream(destination.fileDescriptor).use { o -> i.copyTo(o) }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        pm.print(name, adapter, null)
    }
}
