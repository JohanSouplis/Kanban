package com.johansouplis.kanban.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.johansouplis.kanban.domain.Attachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max

const val MAX_ATTACHMENTS = 10

/** Firestore caps a document at 1 MiB, and the bytes are stored in one. */
private const val MAX_FILE_BYTES = 900_000
private const val MAX_PHOTO_BYTES = 700_000
private const val MAX_PHOTO_SIDE = 1600

sealed interface Import {
    class Done(val attachment: Attachment, val bytes: ByteArray) : Import

    data class Refused(val message: String) : Import
}

/** Reads a picked file. Photos are shrunk and recompressed to fit; other files must already fit. */
fun importAttachment(context: Context, uri: Uri): Import {
    val resolver = context.contentResolver
    val (name, declaredSize) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0)
            val size = if (cursor.isNull(1)) null else cursor.getLong(1)
            name to size
        } ?: (null to null)
    val fileName = name ?: uri.lastPathSegment ?: "fichier"
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    if (mimeType.startsWith("image/") && mimeType != "image/gif") {
        compressPhoto(context, uri)?.let { bytes ->
            val jpegName = fileName.substringBeforeLast('.').ifEmpty { fileName } + ".jpg"
            return Import.Done(Attachment(UUID.randomUUID().toString(), jpegName, "image/jpeg", bytes.size), bytes)
        }
    }
    val tooBig = Import.Refused("« $fileName » dépasse 900 Ko")
    if (declaredSize != null && declaredSize > MAX_FILE_BYTES) return tooBig
    val bytes = runCatching {
        resolver.openInputStream(uri)?.use { it.readAtMost(MAX_FILE_BYTES + 1) }
    }.getOrNull() ?: return Import.Refused("« $fileName » est illisible")
    if (bytes.size > MAX_FILE_BYTES) return tooBig
    return Import.Done(Attachment(UUID.randomUUID().toString(), fileName, mimeType, bytes.size), bytes)
}

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** Null when the image cannot be decoded or stays too big, so it is then treated as a plain file. */
private fun compressPhoto(context: Context, uri: Uri): ByteArray? {
    val bitmap = runCatching { decodeScaled(context, uri) }.getOrNull() ?: return null
    val opaque = if (bitmap.hasAlpha()) {
        Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        }
    } else {
        bitmap
    }
    var quality = 85
    while (true) {
        val bytes = ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        if (bytes.size <= MAX_PHOTO_BYTES) return bytes
        if (quality <= 35) return null
        quality -= 10
    }
}

private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = MAX_PHOTO_SIDE.toFloat() / max(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, MAX_PHOTO_SIDE) })
    } ?: return null
    val scale = MAX_PHOTO_SIDE.toFloat() / max(decoded.width, decoded.height)
    return if (scale < 1) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
}

/** A small preview, decoded straight at about [side] pixels. */
fun decodeThumbnail(bytes: ByteArray, side: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, side) })
}

private fun sampleSize(bounds: BitmapFactory.Options, side: Int): Int {
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    return sample
}

/** A file the camera app can write the photo to. */
fun newCameraUri(context: Context): Uri {
    val name = "Photo ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"))}.jpg"
    val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, name)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

fun deleteCameraFile(context: Context, uri: Uri) {
    File(File(context.cacheDir, "camera"), uri.lastPathSegment ?: return).delete()
}

/** Hands the file to an app that can show it. Returns why it could not. */
fun openAttachment(context: Context, attachment: Attachment, bytes: ByteArray): String? {
    val file = File(File(context.cacheDir, "attachments/${attachment.id}").apply { mkdirs() }, attachment.name.replace('/', '_'))
    file.writeBytes(bytes)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", file), attachment.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "Aucune appli pour ouvrir ce fichier"
    }
}
