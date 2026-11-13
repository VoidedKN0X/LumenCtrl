package com.vknxworks.lumenctrl.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** A photo on the connected camera's card. */
data class CamPhoto(
    val handle: Int,
    val name: String,
    val bytes: Long,
    val takenAt: Long,
    val raw: Boolean,
    val alreadyImported: Boolean,
)

/**
 * USB camera import over PTP/MTP (Lumen's version of Lightroom's PtpActivity).
 * Uses only Android's built-in USB host and android.mtp; no network, no extra library.
 * All MTP calls go through one IO thread, because MtpDevice isn't thread-safe.
 */
class CameraImport(private val context: Context) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mtpThread = Dispatchers.IO.limitedParallelism(1)
    private var device: MtpDevice? = null

    /** Connected devices that look like cameras (PTP still-image class, or MTP). */
    fun cameras(): List<UsbDevice> = usb.deviceList.values.filter { d ->
        d.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
            (0 until d.interfaceCount).any { i ->
                val itf = d.getInterface(i)
                itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                    (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.endpointCount == 3)
            }
    }

    fun label(d: UsbDevice): String =
        listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB camera" }

    fun hasPermission(d: UsbDevice) = usb.hasPermission(d)

    /** Shows Android's "Allow LumenCtrl to access…" dialog and waits for the answer. */
    suspend fun requestPermission(d: UsbDevice): Boolean {
        if (usb.hasPermission(d)) return true
        val action = "${context.packageName}.USB_PERMISSION"
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // The system fills in extras, so the PendingIntent must be mutable; it is explicit to this app.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            usb.requestPermission(d, pi)
        }
    }

    suspend fun open(d: UsbDevice): Boolean = withContext(mtpThread) {
        close()
        val conn = usb.openDevice(d) ?: return@withContext false
        val mtp = MtpDevice(d)
        if (!mtp.open(conn)) { conn.close(); return@withContext false }
        device = mtp
        true
    }

    /** Lists photos on all storages, newest first. [isImported] checks name + size against the library. */
    suspend fun listPhotos(isImported: (String, Long) -> Boolean): List<CamPhoto> = withContext(mtpThread) {
        val mtp = device ?: return@withContext emptyList()
        val out = mutableListOf<CamPhoto>()
        for (storage in mtp.storageIds ?: IntArray(0)) {
            // Parent 0 = every object on this storage (PTP lists recursively).
            for (h in mtp.getObjectHandles(storage, 0, 0) ?: IntArray(0)) {
                val info = mtp.getObjectInfo(h) ?: continue
                if (info.format == MtpConstants.FORMAT_ASSOCIATION) continue
                val name = info.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in PHOTO_EXT && info.format != MtpConstants.FORMAT_EXIF_JPEG) continue
                val size = info.compressedSizeLong
                out += CamPhoto(h, name, size, info.dateCreated, ext in RAW_EXT, isImported(name, size))
            }
        }
        out.sortedByDescending { it.takenAt }
    }

    /** The camera's embedded thumbnail (small JPEG), if it has one. */
    suspend fun thumbnail(handle: Int): Bitmap? = withContext(mtpThread) {
        val bytes = device?.getThumbnail(handle) ?: return@withContext null
        // The thumbnail comes from the USB device: check its size first and decode it small, so a bogus one
        // can't use up the memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_MAX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Copies one photo off the camera into [dest]. */
    suspend fun download(handle: Int, dest: File): Boolean = withContext(mtpThread) {
        dest.parentFile?.mkdirs()
        device?.importFile(handle, dest.path) == true
    }

    fun close() {
        runCatching { device?.close() }
        device = null
    }

    companion object {
        private const val THUMB_MAX = 512
        val RAW_EXT = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef", "srw", "x3f", "3fr", "iiq")
        val PHOTO_EXT = setOf("jpg", "jpeg", "heic", "heif", "hif", "png", "tif", "tiff", "webp") + RAW_EXT
    }
}
