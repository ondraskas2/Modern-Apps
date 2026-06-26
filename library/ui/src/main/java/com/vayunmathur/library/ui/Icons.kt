package com.vayunmathur.library.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.R

@Composable
fun AppIcon(
    @DrawableRes res: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(painterResource(res), contentDescription, modifier = modifier, tint = tint)
}

@Composable
fun IconAdd(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.add_24px, "Add", modifier, tint)

@Composable
fun IconSave(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.save_24px, "Save", modifier, tint)

@Composable
fun IconEdit(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.edit_24px, "Edit", modifier, tint)

@Composable
fun IconDelete(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.delete_24px, "Delete", modifier, tint)

@Composable
fun IconShare(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.share_24px, "Share", modifier, tint)

@Composable
fun IconClose(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.close_24px, "Close", modifier, tint)

@Composable
fun IconSettings(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.settings_24px, "Settings", modifier, tint)

@Composable
fun IconVisible(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.visibility_24px, "Visible", modifier, tint)

@Composable
fun IconSearch(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_search_24, "Search", modifier, tint)

@Composable
fun IconCopy(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_content_copy_24, "Copy", modifier, tint)

@Composable
fun IconCrop(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.crop_24px, "Crop", modifier, tint)

@Composable
fun IconRotateLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.rotate_left_24px, "Rotate Left", modifier, tint)

@Composable
fun IconRotateRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.rotate_right_24px, "Rotate Right", modifier, tint)

@Composable
fun IconNavigation(navBack: () -> Unit) {
    IconButton({
        navBack()
    }) {
        Icon(painterResource(R.drawable.arrow_back_24px), "Back")
    }
}

@Composable
fun IconNavigation(backStack: NavBackStack<out NavKey>, modifier: Modifier = Modifier) {
    IconButton({
        backStack.pop()
    }, modifier = modifier) {
        Icon(painterResource(R.drawable.arrow_back_24px), "Back")
    }
}

@Composable
fun IconCheck(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_check_24, "Check", modifier, tint)

@Composable
fun IconStar(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.baseline_star_24, "Star", modifier, tint)

@Composable
fun IconPlay(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_play_arrow_24, "Play", modifier, tint)

@Composable
fun IconPause(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_pause_24, "Pause", modifier, tint)

@Composable
fun IconStop(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.baseline_stop_24, "Stop", modifier, tint)

@Composable
fun IconMenu(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.baseline_menu_24, "Menu", modifier, tint)

@Composable
fun IconUpload(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.baseline_upload_24, "Upload", modifier, tint)

@Composable
fun IconUnarchive(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.unarchive_24px, "Unarchive", modifier, tint)

@Composable
fun IconArchive(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.archive_24px, "Archive", modifier, tint)

@Composable
fun IconChevronRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.chevron_right_24px, "Chevron", modifier, tint)

@Composable
fun IconUndo(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.undo_24px, "Undo", modifier, tint)

@Composable
fun IconForward(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_shortcut_24, "Forward", modifier, tint)

@Composable
fun IconDraw(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.pen_24px, "Draw", modifier, tint)

@Composable
fun IconBrush(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.brush_24px, "Brush", modifier, tint)

@Composable
fun IconEraser(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.eraser_24px, "Eraser", modifier, tint)

@Composable
fun IconCamera(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.camera_alt_24px, "Camera", modifier, tint)

@Composable
fun IconBackup(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_backup_24, "Backup", modifier, tint)

@Composable
fun IconRestore(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_settings_backup_restore_24, "Restore", modifier, tint)

@Composable
fun IconMarkRead(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_check_24, "Mark Read", modifier, tint)

@Composable
fun IconMarkUnread(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_mail_outline_24, "Mark Unread", modifier, tint)

@Composable
fun IconFavorite(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.favorite_24px, "Favorite", modifier, tint)

@Composable
fun IconFire(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.fire_24px, "Fire", modifier, tint)

@Composable
fun IconInbox(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_inbox_24, "Inbox", modifier, tint)

@Composable
fun IconSend(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_send_24, "Send", modifier, tint)

@Composable
fun IconAttachment(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_attachment_24, "Attachment", modifier, tint)

@Composable
fun IconMail(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_mail_outline_24, "Mail", modifier, tint)

@Composable
fun IconDownload(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.outline_file_download_24, "Download", modifier, tint)

@Composable
fun IconNavigationArrow(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.navigation_24px, "Navigation arrow", modifier, tint)

@Composable
fun IconBack(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.arrow_back_24px, "Back", modifier, tint)

@Composable
fun IconRefresh(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(R.drawable.refresh_24px, "Refresh", modifier, tint)
