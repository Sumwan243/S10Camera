package com.samsung.android.scan3d.processing

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/**
 * Computational Photography Engine (Google Camera HDR+ Pipeline).
 *
 * Implements:
 * 1. Multi-frame Burst Stacking (temporal averaging), memory-bounded.
 * 2. Asymptotic Highlight Compression (Knee Roll-Off).
 * 3. Shadow Detail Extraction.
 * 4. Gentle colour (saturation) lift.
 * 5. Dedicated Storage Folder: DCIM/S10ProCamera.
 */
object ImageProcessor {

    private const val TAG = "ImageProcessor"
    private const val ALBUM_NAME = "S10ProCamera"
    private const val MAX_STACK_FRAMES = 8
    private val TANH_ONE = tanh(1.0).toFloat()

    data class MediaItem(val uri: Uri, val thumbnail: Bitmap)

    /**
     * Loads the newest photo from DCIM/S10ProCamera (or falls back to latest device photo).
     */
    suspend fun loadLatestPhoto(context: Context): MediaItem? = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            // 1. Try specifically from our dedicated folder first
            val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("%$ALBUM_NAME%")

            var result: MediaItem? = queryMediaItem(context, projection, selection, selectionArgs, sortOrder)

            // 2. If nothing yet in S10ProCamera, fallback to latest device photo
            if (result == null) {
                result = queryMediaItem(context, projection, null, null, sortOrder)
            }

            return@withContext result
        } catch (t: Throwable) {
            Log.e(TAG, "Failed loading latest media item", t)
            null
        }
    }

    private fun queryMediaItem(
        context: Context,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String
    ): MediaItem? {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                val thumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        context.contentResolver.loadThumbnail(contentUri, Size(160, 160), null)
                    } catch (_: Throwable) {
                        decodeSampledBitmap(context, contentUri, 160, 160)
                    }
                } else {
                    decodeSampledBitmap(context, contentUri, 160, 160)
                }

                if (thumb != null) {
                    return MediaItem(contentUri, thumb)
                }
            }
        }
        return null
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)

                var inSampleSize = 1
                if (opts.outHeight > reqHeight || opts.outWidth > reqWidth) {
                    val halfH = opts.outHeight / 2
                    val halfW = opts.outWidth / 2
                    while (halfH / inSampleSize >= reqHeight && halfW / inSampleSize >= reqWidth) {
                        inSampleSize *= 2
                    }
                }

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Executes the computational HDR+ pipeline.
     */
    suspend fun processComputationalPhoto(
        context: Context,
        rawFrames: List<ByteArray>,
        isPortrait: Boolean = false,
        isNight: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Pair<Uri?, Bitmap?> = withContext(Dispatchers.Default) {
        if (rawFrames.isEmpty()) return@withContext Pair(null, null)

        onProgress(0.10f)

        // BitmapFactory ignores EXIF orientation, so read it up front and rotate explicitly.
        val rotation = readExifRotationDegrees(rawFrames.first())

        // 1+2+3. Stack the burst and tone-map in one memory-bounded pass at full sensor resolution.
        var current: Bitmap = stackAndToneMap(rawFrames, isNight, onProgress)
            ?: return@withContext Pair(null, null)

        onProgress(0.70f)

        // 4. Make the picture upright BEFORE any geometry-dependent effect.
        if (rotation != 0) {
            val rotated = rotate(current, rotation)
            if (rotated !== current) current.recycle()
            current = rotated
        }

        // 5. Portrait mode defocus (if active)
        if (isPortrait) {
            val portrait = applyPortraitBokeh(current)
            current.recycle()
            current = portrait
        }

        onProgress(0.85f)

        // 6. Colour lift
        val finalBitmap = applyMicroContrast(current)
        current.recycle()

        onProgress(0.92f)

        // 7. Encode with 98% high-fidelity JPEG compression
        val stream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 98, stream)
        val finalBytes = stream.toByteArray()

        // 8. Save explicitly into dedicated album: DCIM/S10ProCamera
        val prefix = if (isNight) "NIGHT_" else if (isPortrait) "PORTRAIT_" else "GCAM_"
        val uri = saveToDedicatedFolder(context, finalBytes, prefix)
        val thumbnail = makeSquareThumbnail(finalBitmap, 160)
        finalBitmap.recycle()

        onProgress(1.0f)
        Pair(uri, thumbnail)
    }

    private fun readExifRotationDegrees(jpeg: ByteArray): Int {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(jpeg))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Centre-crops to a square first so the 4:3 photo is not squashed into the round gallery button. */
    private fun makeSquareThumbnail(source: Bitmap, sizePx: Int): Bitmap {
        val side = min(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, x, y, side, side)
        val thumb = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
        if (square !== source && square !== thumb) square.recycle()
        return thumb
    }

    /**
     * Averages the burst frames and applies the tone curve, working row-by-row.
     *
     * Peak Java-heap use is three ShortArrays (~73 MB at 12 MP) instead of the five full-frame IntArrays
     * (~245 MB) the original implementation needed, which overflowed the app heap and crashed the app.
     * Bitmap pixel data lives in native memory and is released as soon as each frame is accumulated.
     */
    private fun stackAndToneMap(
        frames: List<ByteArray>,
        isNight: Boolean,
        onProgress: (Float) -> Unit
    ): Bitmap? {
        val first = BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size) ?: return null
        val width = first.width
        val height = first.height
        val total = width * height
        val frameCount = min(frames.size, MAX_STACK_FRAMES)

        // Max sum = 8 * 255 = 2040, which fits in a Short.
        val accR = ShortArray(total)
        val accG = ShortArray(total)
        val accB = ShortArray(total)
        val row = IntArray(width)
        var used = 0

        fun accumulate(bmp: Bitmap) {
            for (y in 0 until height) {
                bmp.getPixels(row, 0, width, 0, y, width, 1)
                val base = y * width
                for (x in 0 until width) {
                    val c = row[x]
                    val i = base + x
                    accR[i] = (accR[i] + ((c shr 16) and 0xFF)).toShort()
                    accG[i] = (accG[i] + ((c shr 8) and 0xFF)).toShort()
                    accB[i] = (accB[i] + (c and 0xFF)).toShort()
                }
            }
            used++
        }

        accumulate(first)
        first.recycle()

        for (i in 1 until frameCount) {
            val bmp = BitmapFactory.decodeByteArray(frames[i], 0, frames[i].size)
            if (bmp != null) {
                if (bmp.width == width && bmp.height == height) accumulate(bmp)
                bmp.recycle()
            }
            onProgress(0.10f + 0.50f * (i + 1) / frameCount)
        }

        val shadowBoost = if (isNight) 0.18f else 0.09f
        val kneeThreshold = 0.65f // Highlights above 65% are softly compressed

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val divisor = used * 255f
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val i = base + x
                row[x] = toneMapPixel(
                    accR[i] / divisor,
                    accG[i] / divisor,
                    accB[i] / divisor,
                    shadowBoost,
                    kneeThreshold
                )
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        return out
    }

    /**
     * Computational HDR Tone Mapping for one pixel (channels in 0..1):
     * - Quadratic shadow lifting that falls off to nothing by the mid-tones.
     * - Asymptotic knee compression on highlights.
     */
    private fun toneMapPixel(r0: Float, g0: Float, b0: Float, shadowBoost: Float, knee: Float): Int {
        // Rec.709 luminance
        val luma = 0.2126f * r0 + 0.7152f * g0 + 0.0722f * b0

        val shadowWeight = (1.0f - luma).coerceIn(0f, 1f).pow(2.2f)
        val lift = shadowWeight * shadowBoost

        val r = compressHighlight((r0 + lift).coerceIn(0f, 1.2f), knee)
        val g = compressHighlight((g0 + lift).coerceIn(0f, 1.2f), knee)
        val b = compressHighlight((b0 + lift).coerceIn(0f, 1.2f), knee)

        val rInt = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gInt = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bInt = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
    }

    /**
     * Soft shoulder above [threshold]. The curve is normalised so an input of 1.0 maps to exactly 1.0:
     * the previous version topped out at ~0.92, so pure white came out as grey (RGB ~234).
     */
    private fun compressHighlight(value: Float, threshold: Float): Float {
        if (value <= threshold) return value
        val headroom = 1.0f - threshold
        val excess = ((value - threshold) / headroom).coerceAtMost(1.0f)
        val compressed = tanh(excess.toDouble()).toFloat() / TANH_ONE
        return (threshold + headroom * compressed).coerceIn(0.0f, 1.0f)
    }

    /**
     * Subtle colour richness calibration (saturation lift). Despite the name there is no sharpening here.
     */
    private fun applyMicroContrast(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val cm = ColorMatrix().apply {
            setSaturation(1.05f)
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun applyPortraitBokeh(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height

        val scale = 0.25f
        val small = Bitmap.createScaledBitmap(source, (width * scale).toInt(), (height * scale).toInt(), true)
        val blurred = Bitmap.createScaledBitmap(small, width, height, true)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(blurred, 0f, 0f, null)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val radius = min(width, height) * 0.44f
            shader = RadialGradient(
                width / 2f, height * 0.46f, radius,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        val sharpSubject = source.copy(Bitmap.Config.ARGB_8888, true)
        val sharpCanvas = Canvas(sharpSubject)
        sharpCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        canvas.drawBitmap(sharpSubject, 0f, 0f, null)

        small.recycle()
        if (blurred !== small) blurred.recycle()
        sharpSubject.recycle()
        return output
    }

    /**
     * Saves picture to DCIM/S10ProCamera through MediaStore (single write, no duplicate copies).
     */
    private suspend fun saveToDedicatedFolder(context: Context, imageBytes: ByteArray, prefix: String): Uri? =
        withContext(Dispatchers.IO) {
            // Milliseconds avoid two quick shots in the same second getting the same name.
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val filename = "${prefix}${timeStamp}.jpg"
            val relativeSubDir = "${Environment.DIRECTORY_DCIM}/$ALBUM_NAME"

            var legacyPath: String? = null
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativeSubDir)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    // Pre-Q ignores RELATIVE_PATH, so point the row at the real file location.
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ALBUM_NAME)
                    dir.mkdirs()
                    legacyPath = File(dir, filename).absolutePath
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, legacyPath)
                }
            }

            val resolver = context.contentResolver
            val uri = try {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (t: Throwable) {
                Log.e(TAG, "MediaStore insert failed", t)
                null
            }
            if (uri == null) return@withContext null

            try {
                val stream: OutputStream = resolver.openOutputStream(uri)
                    ?: throw IOException("openOutputStream returned null")
                stream.use { out ->
                    out.write(imageBytes)
                    out.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                } else {
                    legacyPath?.let {
                        MediaScannerConnection.scanFile(context, arrayOf(it), arrayOf("image/jpeg"), null)
                    }
                }

                Log.d(TAG, "Successfully saved photo to $relativeSubDir/$filename")
                uri
            } catch (t: Throwable) {
                Log.e(TAG, "Failed writing image bytes", t)
                // Don't leave an empty / pending entry behind in the gallery.
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                null
            }
        }
}
