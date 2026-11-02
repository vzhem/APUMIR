package com.vladimir.messenger.data.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.system.Os
import android.util.Log
import com.vladimir.messenger.util.FileTooLargeException
import com.vladimir.messenger.util.copyWithLimit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Local-only background of the owner's profile screen.
 *
 * The selected picture is copied into private app storage, downscaled and
 * recompressed as a small JPEG. The original content URI is not retained and
 * no persistable read permission is needed after import. This is deliberately
 * separate from avatars, chat wallpapers, group wallpapers, profile mirror and
 * profile backup: it never leaves the device.
 */
object ProfileBackgroundStore {
    private const val TAG = "ProfileBackground"
    private const val PREFS = "p2p_prefs"
    private const val KEY = "profile_background_path"
    private const val DIR = "profile-background"
    private const val SOURCE_DIR = "profile-background-source"

    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    fun init(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val valid = stored?.let { validatedFile(context, it) }
        if (valid == null && !stored.isNullOrBlank()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        }
        _path.value = valid?.absolutePath
        cleanupManagedFiles(context, keepName = valid?.name)
    }

    /** Import a gallery/photo-picker image and atomically publish the new path. */
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val sourceDir = File(app.cacheDir, SOURCE_DIR).apply {
            check(mkdirs() || isDirectory) { "Cannot create profile background source directory" }
        }
        val source = File.createTempFile("source-", ".img", sourceDir)
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output ->
                    if (!copyWithLimit(input, output, ProfileBackgroundPolicy.MAX_SOURCE_BYTES)) {
                        throw FileTooLargeException(ProfileBackgroundPolicy.MAX_SOURCE_BYTES, ProfileBackgroundPolicy.MAX_SOURCE_BYTES + 1)
                    }
                }
            } ?: error("Cannot open profile background image")
            check(source.length() > 0L) { "Empty profile background image" }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            check(ProfileBackgroundPolicy.validSourceDimensions(bounds.outWidth, bounds.outHeight)) {
                "Unsupported profile background dimensions"
            }
            val decoded = BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = ProfileBackgroundPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                },
            ) ?: error("Cannot decode profile background image")
            val oriented = orient(decoded, source)
            try {
                val jpeg = encode(oriented)
                publish(app, jpeg)
            } finally {
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()
            }
        } finally {
            source.delete()
        }
    }

    /** Return to the standard APU background and remove only managed files. */
    fun clear(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        _path.value = null
        cleanupManagedFiles(app, keepName = null)
    }

    /** Bounded decode for display; callers must not do this on the main thread. */
    suspend fun decode(path: String?): Bitmap? = withContext(Dispatchers.IO) {
        val clean = path?.takeIf { it.isNotBlank() } ?: return@withContext null
        val file = validatedFile(null, clean) ?: return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (!ProfileBackgroundPolicy.validSourceDimensions(bounds.outWidth, bounds.outHeight)) return@withContext null
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = ProfileBackgroundPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                },
            )
        } catch (failure: Exception) {
            Log.w(TAG, "Cannot decode profile background", failure)
            null
        }
    }

    private fun publish(context: Context, jpeg: ByteArray): String {
        val dir = managedDir(context)
        val name = "background-${UUID.randomUUID()}.jpg"
        check(ProfileBackgroundPolicy.isImageFileName(name))
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(jpeg)
                output.fd.sync()
            }
            Os.rename(temp.absolutePath, target.absolutePath)
            val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, target.absolutePath).commit()
            if (!committed) {
                target.delete()
                error("Cannot save profile background preference")
            }
            _path.value = target.absolutePath
            cleanupManagedFiles(context, keepName = name)
            return target.absolutePath
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        for (side in ProfileBackgroundPolicy.SIDES) {
            val (width, height) = ProfileBackgroundPolicy.scaledSize(bitmap.width, bitmap.height, side)
            val scaled = if (width == bitmap.width && height == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, width, height, true)
            try {
                for (quality in ProfileBackgroundPolicy.QUALITIES) {
                    val out = ByteArrayOutputStream()
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, out))
                    if (out.size() <= ProfileBackgroundPolicy.MAX_IMAGE_BYTES) return out.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        error("Profile background cannot be compressed to the storage limit")
    }

    private fun orient(bitmap: Bitmap, source: File): Bitmap {
        val orientation = try {
            ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (failure: Exception) {
            Log.w(TAG, "Cannot read profile background orientation", failure)
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun managedDir(context: Context): File {
        val dir = File(context.filesDir, DIR)
        check(!Files.isSymbolicLink(dir.toPath())) { "Profile background directory is a symlink" }
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create profile background directory" }
        return dir
    }

    private fun validatedFile(context: Context?, path: String): File? {
        val file = File(path)
        val name = file.name
        if (!ProfileBackgroundPolicy.isImageFileName(name)) return null
        if (context != null) {
            val dir = File(context.filesDir, DIR)
            if (file.parentFile?.canonicalPath != dir.canonicalPath) return null
        } else if (!path.contains("/$DIR/")) {
            return null
        }
        return try {
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile) return null
            if (file.length() !in 1L..ProfileBackgroundPolicy.MAX_IMAGE_BYTES.toLong()) return null
            file
        } catch (failure: Exception) {
            Log.w(TAG, "Cannot validate profile background file", failure)
            null
        }
    }

    private fun cleanupManagedFiles(context: Context, keepName: String?) {
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return
        dir.listFiles()?.take(64)?.forEach { file ->
            if (file.name == keepName || !ProfileBackgroundPolicy.isManagedFileName(file.name)) return@forEach
            try {
                if (!Files.isSymbolicLink(file.toPath()) && !file.delete()) {
                    Log.w(TAG, "Cannot remove stale profile background file: ${file.name}")
                }
            } catch (failure: Exception) {
                Log.w(TAG, "Cannot inspect stale profile background file: ${file.name}", failure)
            }
        }
    }
}
