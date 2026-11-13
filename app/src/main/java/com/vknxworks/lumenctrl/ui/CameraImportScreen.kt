package com.vknxworks.lumenctrl.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.data.CamPhoto
import com.vknxworks.lumenctrl.ui.ctrl.CardShape
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.CtrlIconButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlType
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import com.vknxworks.lumenctrl.ui.ctrl.StatusDot
import java.text.DateFormat
import java.util.Date

/** Import from a camera connected over USB (PTP/MTP). */
@Composable
fun CameraImportScreen(vm: LumenViewModel) {
    val c = Ctrl.colors
    val context = LocalContext.current
    BackHandler { vm.leaveCameraImport() }

    // Follow plug/unplug while this screen is open.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = vm.refreshCameras()
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val photos = vm.camPhotos
    val newCount = photos.count { !it.alreadyImported }
    val selected = vm.camSelected

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 132.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CtrlIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", vm::leaveCameraImport)
                        Spacer(Modifier.weight(1f))
                        CtrlIconButton(Icons.Outlined.Refresh, "Look for cameras again", vm::refreshCameras, style = CtrlButtonStyle.Ghost)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonoLabel("USB · PTP / MTP")
                        Text("Camera", style = CtrlType.display, color = c.text)
                        Text(
                            when {
                                vm.camConnected != null -> "${photos.size} photos · $newCount new"
                                vm.camDevices.isNotEmpty() -> "${vm.camDevices.size} device(s) found"
                                else -> "No camera connected"
                            },
                            style = CtrlType.value, color = c.muted,
                        )
                    }
                }
            }

            if (vm.camConnected == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { DeviceList(vm) }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(c.accent)
                        MonoLabel(vm.camConnected ?: "", color = c.text, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        CtrlChip("New", selected == photos.filter { !it.alreadyImported }.map { it.handle }.toSet() && newCount > 0,
                            onClick = { vm.camSelected = photos.filter { !it.alreadyImported }.map { it.handle }.toSet() })
                        CtrlChip("All", selected.size == photos.size && photos.isNotEmpty(), onClick = { vm.camSelected = photos.map { it.handle }.toSet() })
                        CtrlChip("None", selected.isEmpty(), onClick = { vm.camSelected = emptySet() })
                    }
                }
                items(photos, key = { it.handle }) { p -> CamThumb(vm, p, p.handle in selected) }
            }
        }

        Surface(
            shape = CardShape, color = c.navBg, border = BorderStroke(1.dp, c.borderStrong),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp).fillMaxWidth().height(64.dp),
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CtrlButton(
                    if (selected.isEmpty()) "Import" else "Import ${selected.size}",
                    onClick = vm::importFromCamera, icon = Icons.Outlined.Download,
                    style = if (selected.isEmpty()) CtrlButtonStyle.Bordered else CtrlButtonStyle.Accent,
                    modifier = Modifier.height(48.dp),
                )
                Spacer(Modifier.weight(1f))
                MonoLabel("Copied to private library", Modifier.padding(end = 12.dp))
            }
        }
    }
}

@Composable
private fun DeviceList(vm: LumenViewModel) {
    val c = Ctrl.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        vm.camStatus?.let { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.startsWith("Waiting") || s.startsWith("Connecting") || s.startsWith("Reading")) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                }
                MonoLabel(s, color = c.text)
            }
        }
        if (vm.camDevices.isEmpty()) {
            Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Usb, null, tint = c.accentText, modifier = Modifier.size(28.dp))
                    Text("Connect your camera", style = CtrlType.title, color = c.text)
                    Text(
                        "Use a USB-C cable or an OTG adapter, switch the camera on, and set its USB mode to PTP, MTP or \"PC connection\". " +
                            "LumenCtrl opens automatically when one is plugged in. SD card readers show up in Import instead.",
                        color = c.muted,
                    )
                }
            }
        }
        vm.camDevices.forEach { d ->
            Surface(
                shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border),
                onClick = { vm.connectCamera(d) }, modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.PhotoCamera, null, tint = c.text, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB camera" }, style = CtrlType.body, color = c.text)
                        MonoLabel("Tap to open the card")
                    }
                    CtrlButton("Open", onClick = { vm.connectCamera(d) }, style = CtrlButtonStyle.Accent)
                }
            }
        }
    }
}

@Composable
private fun CamThumb(vm: LumenViewModel, p: CamPhoto, selected: Boolean) {
    val c = Ctrl.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(c.surface)
            .border(if (selected) 3.dp else 1.dp, if (selected) c.accent else c.border, shape)
            .clickable(role = Role.Checkbox, onClickLabel = if (selected) "Deselect" else "Select") {
                vm.camSelected = if (selected) vm.camSelected - p.handle else vm.camSelected + p.handle
            },
    ) {
        val bmp = vm.camThumbs[p.handle]
        if (bmp != null) Image(bmp.asImageBitmap(), p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else MonoLabel(p.name.substringAfterLast('.'), Modifier.align(Alignment.Center))
        Row(
            Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(c.bg.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (p.raw) Text("RAW", style = CtrlType.label, color = c.accentText)
            if (p.alreadyImported) Text("IN LIBRARY", style = CtrlType.label, color = c.muted)
            else if (p.takenAt > 0) Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(p.takenAt)), style = CtrlType.label, color = c.text)
        }
        if (selected) {
            Icon(
                Icons.Outlined.CheckCircle, "Selected", tint = c.onAccent,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(c.accent, RoundedCornerShape(12.dp)).padding(2.dp).size(18.dp),
            )
        }
    }
}
