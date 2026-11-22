package com.vknxworks.lumenctrl.ui

import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.tr
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
import androidx.compose.runtime.saveable.rememberSaveable
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

    // Camera: the system camera app writes into our private cache through FileProvider. The path and address
    // are saved as text, so they survive LumenCtrl being recreated (rotation in the camera app, or Android
    // closing LumenCtrl while the camera is in front) and the photo still reaches the library.
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var captureUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = capturePath?.let { File(it) }; val u = captureUriText?.let { Uri.parse(it) }
        capturePath = null; captureUriText = null
        if (ok && f != null && u != null) vm.importCapture(u, f) else f?.delete()
    }
    val launchCamera: () -> Unit = {
        val f = File(context.cacheDir, "capture/IMG_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val u = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        capturePath = f.absolutePath; captureUriText = u.toString()
        runCatching { camera.launch(u) }.onFailure { vm.toast(tr(R.string.no_camera_app_available)) }
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
                item(span = { GridItemSpan(maxLineSpan) }) { MonoLabel(tr(R.string.no_photos_match), Modifier.padding(vertical = 24.dp)) }
            }
            items(photos, key = { it.id }) { photo ->
                val selected = photo.id in vm.selection
                Thumbnail(
                    photo, vm.thumbVersion, selected,
                    Modifier
                        .aspectRatio(1f)
                        .clip(ThumbShape)
                        .combinedClickable(
                            onClickLabel = if (selecting) tr(R.string.select) else tr(R.string.edit_photo),
                            onLongClickLabel = tr(R.string.select),
                            onClick = { if (selecting) vm.toggleSelect(photo.id) else vm.open(photo) },
                            onLongClick = { vm.toggleSelect(photo.id) },
                        ),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                MonoLabel(tr(R.string.offline_telemetry_free_on_device_gpu), Modifier.fillMaxWidth().padding(top = 24.dp))
            }
        }

        Surface(
            shape = CardShape, color = c.navBg, border = BorderStroke(1.dp, c.borderStrong),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp).fillMaxWidth().height(64.dp),
        ) {
            if (selecting) SelectionBar(vm, onExport = { showExport = true }, onDelete = { dialog = "delete" }, onNewAlbum = { dialog = "newAlbum" })
            else Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CtrlButton(tr(R.string.import_text), onClick = launchPicker, icon = Icons.Outlined.AddPhotoAlternate, style = CtrlButtonStyle.Accent, modifier = Modifier.height(48.dp))
                Spacer(Modifier.weight(1f))
                CtrlIconButton(Icons.Outlined.PhotoCamera, tr(R.string.take_a_photo), launchCamera, style = CtrlButtonStyle.Ghost)
                CtrlIconButton(Icons.Outlined.Usb, tr(R.string.import_from_usb_camera), vm::openCameraImport, style = CtrlButtonStyle.Ghost)
                CtrlIconButton(Icons.Outlined.Slideshow, tr(R.string.slideshow), { vm.startSlideshow(photos.map { it.id }) }, style = CtrlButtonStyle.Ghost, enabled = photos.isNotEmpty())
                CtrlIconButton(Icons.Outlined.Settings, tr(R.string.settings), { vm.screen = Screen.SETTINGS }, style = CtrlButtonStyle.Ghost)
            }
        }
    }

    when (dialog) {
        "delete" -> ConfirmDialog(
            tr(R.string.remove_x_photo_s, vm.selection.size),
            tr(R.string.this_removes_them_and_their_edits),
            tr(R.string.remove), onConfirm = { vm.deletePhotos(vm.selection) }, onDismiss = { dialog = null },
        )
        "newAlbum" -> TextDialog(tr(R.string.new_album), "", tr(R.string.create), onConfirm = { vm.createAlbum(it, vm.selection) }, onDismiss = { dialog = null })
        "renameAlbum" -> {
            val a = vm.albums.firstOrNull { it.id == vm.albumFilter }
            if (a != null) TextDialog(tr(R.string.rename_album), a.name, tr(R.string.rename), onConfirm = { vm.renameAlbum(a.id, it) }, onDismiss = { dialog = null })
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
                MonoLabel(tr(R.string.offline), color = c.text)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel(if (album != null) tr(R.string.album) else tr(R.string.library_originals_untouched))
            Text(album?.name ?: tr(R.string.photos), style = CtrlType.display, color = c.text, maxLines = 1)
            val edited = vm.allPhotos.count { it.edited }
            Text(
                if (album != null) tr(R.string.x_photos, vm.photos.size) else tr(R.string.x_total_x_edited, vm.allPhotos.size, edited),
                style = CtrlType.value, color = c.muted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CtrlTextField(vm.query, { vm.query = it }, tr(R.string.search_name_camera_date), Modifier.weight(1f), leading = Icons.Outlined.Search)
            Box {
                CtrlIconButton(Icons.Outlined.Sort, tr(R.string.sort_x, vm.sort.label), { sortMenu = true })
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
            item { CtrlChip(tr(R.string.all_photos), vm.albumFilter == null, icon = Icons.Outlined.Folder, onClick = { vm.albumFilter = null }) }
            items(vm.albums, key = { it.id }) { a ->
                Box {
                    CtrlChip(a.name, vm.albumFilter == a.id, onClick = {
                        if (vm.albumFilter == a.id) albumMenu = true else vm.albumFilter = a.id
                    })
                    if (vm.albumFilter == a.id) CtrlMenu(albumMenu, { albumMenu = false }) {
                        CtrlMenuItem(tr(R.string.rename_album)) { albumMenu = false; onRenameAlbum() }
                        CtrlMenuItem(tr(R.string.delete_album)) { albumMenu = false; vm.deleteAlbum(a.id) }
                    }
                }
            }
            item { CtrlChip(tr(R.string.new_text), false, icon = Icons.Outlined.CreateNewFolder, onClick = onNewAlbum) }
        }
    }
}

@Composable
private fun SelectionHeader(vm: LumenViewModel) {
    val c = Ctrl.colors
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CtrlIconButton(Icons.Outlined.Close, tr(R.string.clear_selection), vm::clearSelection)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            MonoLabel(tr(R.string.selected))
            Text("${vm.selection.size}", style = CtrlType.title, color = c.text)
        }
        CtrlButton(tr(R.string.select_all), onClick = vm::selectAll, icon = Icons.Outlined.SelectAll, style = CtrlButtonStyle.Ghost)
    }
}

@Composable
private fun SelectionBar(vm: LumenViewModel, onExport: () -> Unit, onDelete: () -> Unit, onNewAlbum: () -> Unit) {
    var reelIds by remember { mutableStateOf<List<String>?>(null) }
    reelIds?.let { ids ->
        com.vknxworks.lumenctrl.ui.editor.VideoExportSheet(tr(R.string.make_reel_x_clips, ids.size), onDismiss = { reelIds = null }) { edge, share ->
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
            CtrlIconButton(Icons.Outlined.Star, tr(R.string.rate), { rate = true }, style = ghost)
            CtrlMenu(rate, { rate = false }) {
                (0..5).forEach { r -> CtrlMenuItem(if (r == 0) tr(R.string.no_rating) else "★".repeat(r)) { vm.rate(sel, r); rate = false } }
            }
        }
        Box {
            CtrlIconButton(Icons.Outlined.Flag, tr(R.string.flag), { flag = true }, style = ghost)
            CtrlMenu(flag, { flag = false }) {
                CtrlMenuItem(tr(R.string.pick), Icons.Outlined.CheckCircle) { vm.flag(sel, 1); flag = false }
                CtrlMenuItem(tr(R.string.reject), Icons.Outlined.Close) { vm.flag(sel, -1); flag = false }
                CtrlMenuItem(tr(R.string.clear_flag)) { vm.flag(sel, 0); flag = false }
            }
        }
        Box {
            CtrlIconButton(Icons.Outlined.Folder, tr(R.string.add_to_album), { album = true }, style = ghost)
            CtrlMenu(album, { album = false }) {
                vm.albums.forEach { a -> CtrlMenuItem(a.name) { vm.addToAlbum(sel, a.id); album = false } }
                vm.albumFilter?.let { cur -> CtrlMenuItem(tr(R.string.remove_from_this_album)) { vm.removeFromAlbum(sel, cur); album = false } }
                CtrlMenuItem(tr(R.string.new_album_2), Icons.Outlined.CreateNewFolder) { album = false; onNewAlbum() }
            }
        }
        CtrlIconButton(Icons.Outlined.ContentPaste, tr(R.string.paste_settings), vm::pasteToSelected, style = ghost, enabled = vm.clipboard != null)
        CtrlIconButton(Icons.Outlined.Download, tr(R.string.export), onExport, style = ghost)
        Box {
            var create by remember { mutableStateOf(false) }
            CtrlIconButton(Icons.Outlined.Dashboard, tr(R.string.create_collage_or_hdr), { create = true }, style = ghost, enabled = sel.size >= 2)
            CtrlMenu(create, { create = false }) {
                val ordered = vm.photos.filter { it.id in sel }.map { it.id }
                CtrlMenuItem(tr(R.string.collage), Icons.Outlined.Dashboard, enabled = sel.size in 2..6) { create = false; vm.startCollage(ordered) }
                val stills = vm.photos.filter { it.id in sel && !it.isVideo }.map { it.id }
                CtrlMenuItem(tr(R.string.merge_to_hdr_x_photos, stills.size), Icons.Outlined.HdrOn, enabled = stills.size in 2..7) { create = false; vm.mergeHdr(stills) }
                val videos = vm.photos.filter { it.id in sel && it.isVideo }.map { it.id }
                CtrlMenuItem(tr(R.string.make_reel_x_videos, videos.size), Icons.Outlined.Movie, enabled = videos.size >= 2) { create = false; reelIds = videos }
            }
        }
        CtrlIconButton(Icons.Outlined.Delete, tr(R.string.remove), onDelete, style = ghost)
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit) {
    val c = Ctrl.colors
    Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MonoLabel(tr(R.string.empty))
            Text(tr(R.string.no_photos_yet), style = CtrlType.title, color = c.text)
            Text(
                tr(R.string.import_from_your_gallery_take_a),
                color = c.muted,
            )
            CtrlButton(tr(R.string.import_photos), onClick = onImport, icon = Icons.Outlined.AddPhotoAlternate, style = CtrlButtonStyle.Accent)
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
                if (photo.meta.flag == 1) Icon(Icons.Outlined.CheckCircle, tr(R.string.pick), tint = c.accent, modifier = Modifier.size(12.dp))
                if (photo.meta.flag == -1) Icon(Icons.Outlined.Close, tr(R.string.rejected), tint = c.muted, modifier = Modifier.size(12.dp))
                if (photo.meta.rating > 0) Text("★".repeat(photo.meta.rating), style = CtrlType.label, color = c.text)
            }
        }
        if (photo.isVideo) {
            Row(
                Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(c.bg.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PlayArrow, tr(R.string.video), tint = c.text, modifier = Modifier.size(12.dp))
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
                Icons.Outlined.CheckCircle, tr(R.string.selected), tint = c.onAccent,
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
        dismissButton = { CtrlButton(tr(R.string.cancel), onClick = onDismiss, style = CtrlButtonStyle.Ghost) },
    )
}

@Composable
fun TextDialog(
    title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit,
    maxLength: Int = 40, placeholder: String = tr(R.string.name),
) {
    val c = Ctrl.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, shape = CardShape,
        title = { Text(title, style = CtrlType.title, color = c.text) },
        text = { CtrlTextField(text, { text = it.take(maxLength) }, placeholder, Modifier.fillMaxWidth()) },
        confirmButton = { CtrlButton(confirm, onClick = { onConfirm(text); onDismiss() }, style = CtrlButtonStyle.Accent) },
        dismissButton = { CtrlButton(tr(R.string.cancel), onClick = onDismiss, style = CtrlButtonStyle.Ghost) },
    )
}
