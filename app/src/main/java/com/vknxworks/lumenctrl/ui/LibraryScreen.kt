package com.vknxworks.lumenctrl.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.HdrOn
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vknxworks.lumenctrl.LibFilter
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.Screen
import com.vknxworks.lumenctrl.SortOrder
import com.vknxworks.lumenctrl.data.Catalog
import com.vknxworks.lumenctrl.data.ImageLoader
import com.vknxworks.lumenctrl.ui.ctrl.CardShape
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.CtrlIconButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlMenu
import com.vknxworks.lumenctrl.ui.ctrl.CtrlMenuItem
import com.vknxworks.lumenctrl.ui.ctrl.CtrlTextField
import com.vknxworks.lumenctrl.ui.ctrl.CtrlType
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import com.vknxworks.lumenctrl.ui.ctrl.StatusDot
import com.vknxworks.lumenctrl.ui.ctrl.Wordmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val ThumbShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: LumenViewModel) {
    val c = Ctrl.colors
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)) { vm.import(it) }
    val launchPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    // Camera: the system camera app writes into our private cache through FileProvider.
    var captureFile by remember { mutableStateOf<File?>(null) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = captureFile; val u = captureUri
        if (ok && f != null && u != null) vm.importCapture(u, f) else f?.delete()
    }
    val launchCamera: () -> Unit = {
        val f = File(context.cacheDir, "capture/IMG_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val u = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        captureFile = f; captureUri = u
        runCatching { camera.launch(u) }.onFailure { vm.toast("No camera app available") }
    }

    var dialog by remember { mutableStateOf<String?>(null) } // "delete", "newAlbum", "renameAlbum"
    var showExport by remember { mutableStateOf(false) }
    val photos = vm.photos
    val selecting = vm.selection.isNotEmpty()

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 132.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (selecting) SelectionHeader(vm) else Header(vm, onNewAlbum = { dialog = "newAlbum" }, onRenameAlbum = { dialog = "renameAlbum" })
            }
            if (vm.allPhotos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(onImport = launchPicker) }
            } else if (photos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { MonoLabel("No photos match", Modifier.padding(vertical = 24.dp)) }
            }
            items(photos, key = { it.id }) { photo ->
                val selected = photo.id in vm.selection
                Thumbnail(
                    photo, vm.thumbVersion, selected,
                    Modifier
                        .aspectRatio(1f)
                        .clip(ThumbShape)
                        .combinedClickable(
                            onClickLabel = if (selecting) "Select" else "Edit photo",
                            onLongClickLabel = "Select",
                            onClick = { if (selecting) vm.toggleSelect(photo.id) else vm.open(photo) },
                            onLongClick = { vm.toggleSelect(photo.id) },
                        ),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                MonoLabel("Offline · telemetry-free · on-device GPU", Modifier.fillMaxWidth().padding(top = 24.dp))
            }
        }

        Surface(
            shape = CardShape, color = c.navBg, border = BorderStroke(1.dp, c.borderStrong),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp).fillMaxWidth().height(64.dp),
        ) {
            if (selecting) SelectionBar(vm, onExport = { showExport = true }, onDelete = { dialog = "delete" }, onNewAlbum = { dialog = "newAlbum" })
            else Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CtrlButton("Import", onClick = launchPicker, icon = Icons.Outlined.AddPhotoAlternate, style = CtrlButtonStyle.Accent, modifier = Modifier.height(48.dp))
                Spacer(Modifier.weight(1f))
                CtrlIconButton(Icons.Outlined.PhotoCamera, "Take a photo", launchCamera, style = CtrlButtonStyle.Ghost)
                CtrlIconButton(Icons.Outlined.Usb, "Import from USB camera", vm::openCameraImport, style = CtrlButtonStyle.Ghost)
                CtrlIconButton(Icons.Outlined.Slideshow, "Slideshow", { vm.startSlideshow(photos.map { it.id }) }, style = CtrlButtonStyle.Ghost, enabled = photos.isNotEmpty())
                CtrlIconButton(Icons.Outlined.Settings, "Settings", { vm.screen = Screen.SETTINGS }, style = CtrlButtonStyle.Ghost)
            }
        }
    }

    when (dialog) {
        "delete" -> ConfirmDialog(
            "Remove ${vm.selection.size} photo(s)?",
            "This removes them and their edits from Lumen. Copies in your gallery are not affected.",
            "Remove", onConfirm = { vm.deletePhotos(vm.selection) }, onDismiss = { dialog = null },
        )
        "newAlbum" -> TextDialog("New album", "", "Create", onConfirm = { vm.createAlbum(it, vm.selection) }, onDismiss = { dialog = null })
        "renameAlbum" -> {
            val a = vm.albums.firstOrNull { it.id == vm.albumFilter }
            if (a != null) TextDialog("Rename album", a.name, "Rename", onConfirm = { vm.renameAlbum(a.id, it) }, onDismiss = { dialog = null })
            else dialog = null
        }
    }
    if (showExport) {
        ExportSheet(
            initial = vm.settings.export, count = vm.selection.size,
            onDismiss = { showExport = false },
            onExport = { o, share ->
                vm.settings.updateExport(o)
                vm.exportPhotos(photos.filter { it.id in vm.selection }.map { it.id }, o, share)
                showExport = false
            },
        )
    }
}

@Composable
private fun Header(vm: LumenViewModel, onNewAlbum: () -> Unit, onRenameAlbum: () -> Unit) {
    val c = Ctrl.colors
    var sortMenu by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }
    val album = vm.albums.firstOrNull { it.id == vm.albumFilter }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark("Lumen")
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(c.accent)
                MonoLabel("Offline", color = c.text)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel(if (album != null) "Album" else "Library · originals untouched")
            Text(album?.name ?: "Photos", style = CtrlType.display, color = c.text, maxLines = 1)
            val edited = vm.allPhotos.count { it.edited }
            Text(
                if (album != null) "${vm.photos.size} photos" else "${vm.allPhotos.size} total · $edited edited",
                style = CtrlType.value, color = c.muted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CtrlTextField(vm.query, { vm.query = it }, "Search name, camera, date", Modifier.weight(1f), leading = Icons.Outlined.Search)
            Box {
                CtrlIconButton(Icons.Outlined.Sort, "Sort: ${vm.sort.label}", { sortMenu = true })
                CtrlMenu(sortMenu, { sortMenu = false }) {
                    SortOrder.entries.forEach { s ->
                        CtrlMenuItem(s.label + if (vm.sort == s) "  ✓" else "") { vm.sort = s; sortMenu = false }
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(LibFilter.entries) { f -> CtrlChip(f.label, vm.filter == f, onClick = { vm.filter = f }) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            item { CtrlChip("All photos", vm.albumFilter == null, icon = Icons.Outlined.Folder, onClick = { vm.albumFilter = null }) }
            items(vm.albums, key = { it.id }) { a ->
                Box {
                    CtrlChip(a.name, vm.albumFilter == a.id, onClick = {
                        if (vm.albumFilter == a.id) albumMenu = true else vm.albumFilter = a.id
                    })
                    if (vm.albumFilter == a.id) CtrlMenu(albumMenu, { albumMenu = false }) {
                        CtrlMenuItem("Rename album") { albumMenu = false; onRenameAlbum() }
                        CtrlMenuItem("Delete album") { albumMenu = false; vm.deleteAlbum(a.id) }
                    }
                }
            }
            item { CtrlChip("New", false, icon = Icons.Outlined.CreateNewFolder, onClick = onNewAlbum) }
        }
    }
}

@Composable
private fun SelectionHeader(vm: LumenViewModel) {
    val c = Ctrl.colors
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CtrlIconButton(Icons.Outlined.Close, "Clear selection", vm::clearSelection)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            MonoLabel("Selected")
            Text("${vm.selection.size}", style = CtrlType.title, color = c.text)
        }
        CtrlButton("Select all", onClick = vm::selectAll, icon = Icons.Outlined.SelectAll, style = CtrlButtonStyle.Ghost)
    }
}

@Composable
private fun SelectionBar(vm: LumenViewModel, onExport: () -> Unit, onDelete: () -> Unit, onNewAlbum: () -> Unit) {
    var reelIds by remember { mutableStateOf<List<String>?>(null) }
    reelIds?.let { ids ->
        com.vknxworks.lumenctrl.ui.editor.VideoExportSheet("Make reel · ${ids.size} clips", onDismiss = { reelIds = null }) { edge, share ->
            reelIds = null
            vm.exportVideos(ids, edge, share, reel = true)
        }
    }
    var rate by remember { mutableStateOf(false) }
    var flag by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf(false) }
    val sel = vm.selection
    val ghost = CtrlButtonStyle.Ghost
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box {
            CtrlIconButton(Icons.Outlined.Star, "Rate", { rate = true }, style = ghost)
            CtrlMenu(rate, { rate = false }) {
                (0..5).forEach { r -> CtrlMenuItem(if (r == 0) "No rating" else "★".repeat(r)) { vm.rate(sel, r); rate = false } }
            }
        }
        Box {
            CtrlIconButton(Icons.Outlined.Flag, "Flag", { flag = true }, style = ghost)
            CtrlMenu(flag, { flag = false }) {
                CtrlMenuItem("Pick", Icons.Outlined.CheckCircle) { vm.flag(sel, 1); flag = false }
                CtrlMenuItem("Reject", Icons.Outlined.Close) { vm.flag(sel, -1); flag = false }
                CtrlMenuItem("Clear flag") { vm.flag(sel, 0); flag = false }
            }
        }
        Box {
            CtrlIconButton(Icons.Outlined.Folder, "Add to album", { album = true }, style = ghost)
            CtrlMenu(album, { album = false }) {
                vm.albums.forEach { a -> CtrlMenuItem(a.name) { vm.addToAlbum(sel, a.id); album = false } }
                vm.albumFilter?.let { cur -> CtrlMenuItem("Remove from this album") { vm.removeFromAlbum(sel, cur); album = false } }
                CtrlMenuItem("New album…", Icons.Outlined.CreateNewFolder) { album = false; onNewAlbum() }
            }
        }
        CtrlIconButton(Icons.Outlined.ContentPaste, "Paste settings", vm::pasteToSelected, style = ghost, enabled = vm.clipboard != null)
        CtrlIconButton(Icons.Outlined.Download, "Export", onExport, style = ghost)
        Box {
            var create by remember { mutableStateOf(false) }
            CtrlIconButton(Icons.Outlined.Dashboard, "Create collage or HDR", { create = true }, style = ghost, enabled = sel.size >= 2)
            CtrlMenu(create, { create = false }) {
                val ordered = vm.photos.filter { it.id in sel }.map { it.id }
                CtrlMenuItem("Collage", Icons.Outlined.Dashboard, enabled = sel.size in 2..6) { create = false; vm.startCollage(ordered) }
                val stills = vm.photos.filter { it.id in sel && !it.isVideo }.map { it.id }
                CtrlMenuItem("Merge to HDR (${stills.size} photos)", Icons.Outlined.HdrOn, enabled = stills.size in 2..7) { create = false; vm.mergeHdr(stills) }
                val videos = vm.photos.filter { it.id in sel && it.isVideo }.map { it.id }
                CtrlMenuItem("Make reel (${videos.size} videos)", Icons.Outlined.Movie, enabled = videos.size >= 2) { create = false; reelIds = videos }
            }
        }
        CtrlIconButton(Icons.Outlined.Delete, "Remove", onDelete, style = ghost)
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit) {
    val c = Ctrl.colors
    Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MonoLabel("Empty")
            Text("No photos yet", style = CtrlType.title, color = c.text)
            Text(
                "Import from your gallery, take a photo, or share images into Lumen from any app. Originals are copied into private storage and never modified.",
                color = c.muted,
            )
            CtrlButton("Import photos", onClick = onImport, icon = Icons.Outlined.AddPhotoAlternate, style = CtrlButtonStyle.Accent)
        }
    }
}

@Composable
private fun Thumbnail(photo: Catalog.Photo, version: Int, selected: Boolean, modifier: Modifier) {
    val c = Ctrl.colors
    val bmp by produceState<ImageBitmap?>(null, photo.id, version, photo.edited) {
        value = withContext(Dispatchers.IO) { ImageLoader.thumbnail(photo) }
    }
    Box(modifier.background(c.surface).border(if (selected) 3.dp else 1.dp, if (selected) c.accent else c.border, ThumbShape)) {
        bmp?.let { Image(it, contentDescription = photo.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (photo.meta.flag != 0 || photo.meta.rating > 0) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(c.bg.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (photo.meta.flag == 1) Icon(Icons.Outlined.CheckCircle, "Pick", tint = c.accent, modifier = Modifier.size(12.dp))
                if (photo.meta.flag == -1) Icon(Icons.Outlined.Close, "Rejected", tint = c.muted, modifier = Modifier.size(12.dp))
                if (photo.meta.rating > 0) Text("★".repeat(photo.meta.rating), style = CtrlType.label, color = c.text)
            }
        }
        if (photo.isVideo) {
            Row(
                Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(c.bg.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PlayArrow, "Video", tint = c.text, modifier = Modifier.size(12.dp))
                val s = photo.meta.durationMs / 1000
                Text("%d:%02d".format(s / 60, s % 60), style = CtrlType.label, color = c.text)
            }
        }
        if (photo.edited) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(c.bg.copy(alpha = 0.8f), RoundedCornerShape(5.dp)).padding(4.dp),
            ) { StatusDot(c.accent) }
        }
        if (selected) {
            Icon(
                Icons.Outlined.CheckCircle, "Selected", tint = c.onAccent,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(c.accent, RoundedCornerShape(12.dp)).padding(2.dp).size(18.dp),
            )
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Ctrl.colors
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, shape = CardShape,
        title = { Text(title, style = CtrlType.title, color = c.text) },
        text = { Text(text, color = c.muted) },
        confirmButton = { CtrlButton(confirm, onClick = { onConfirm(); onDismiss() }, style = CtrlButtonStyle.Accent) },
        dismissButton = { CtrlButton("Cancel", onClick = onDismiss, style = CtrlButtonStyle.Ghost) },
    )
}

@Composable
fun TextDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Ctrl.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, shape = CardShape,
        title = { Text(title, style = CtrlType.title, color = c.text) },
        text = { CtrlTextField(text, { text = it.take(40) }, "Name", Modifier.fillMaxWidth()) },
        confirmButton = { CtrlButton(confirm, onClick = { onConfirm(text); onDismiss() }, style = CtrlButtonStyle.Accent) },
        dismissButton = { CtrlButton("Cancel", onClick = onDismiss, style = CtrlButtonStyle.Ghost) },
    )
}
