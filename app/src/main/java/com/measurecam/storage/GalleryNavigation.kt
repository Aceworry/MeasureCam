package com.measurecam.storage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import com.measurecam.PhotoPreviewActivity
import com.measurecam.R
import com.measurecam.RecordListActivity

object GalleryNavigation {
    fun open(context: Context, pick: (Intent) -> Unit) {
        val selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
        if (available { context.startActivity(selector) }) return

        // 部分图库只声明 APP_GALLERY，没有 DEFAULT。此时隐式启动失败，
        // 但可以正常打开它公开声明的图库主页。
        val gallery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
        val candidates = try {
            context.packageManager.queryIntentActivities(gallery, 0)
        } catch (_: SecurityException) { emptyList() }
        for (match in candidates) {
            val info = match.activityInfo ?: continue
            if (!info.exported || !info.enabled) continue
            val home = Intent.makeMainActivity(ComponentName(info.packageName, info.name))
            if (available { context.startActivity(home) }) return
        }

        var pickerAction: String? = null
        if (available {
            val picker = PickVisualMedia().createIntent(context, PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            pickerAction = picker.action
            pick(picker)
        }) return
        // Jetpack 在旧系统上已选用 OPEN_DOCUMENT；只有先前的选择器是别的组件才再尝试。
        if (pickerAction != Intent.ACTION_OPEN_DOCUMENT) {
            val document = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            if (available { pick(document) }) return
        }
        Toast.makeText(context, R.string.gallery_records_fallback, Toast.LENGTH_LONG).show()
        context.startActivity(Intent(context, RecordListActivity::class.java))
    }

    fun onSelection(context: Context, result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val data = result.data
        val uri = data?.data ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri?.scheme == ContentResolver.SCHEME_CONTENT) {
            PhotoPreviewActivity.open(context, uri)
        } else {
            Toast.makeText(context, R.string.gallery_image_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** 查看记录时才对一张具体成片使用 ACTION_VIEW，并授权接收方读取这张照片。 */
    fun viewRecordPhoto(context: Context, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!available { context.startActivity(view) }) PhotoPreviewActivity.open(context, uri)
    }

    private inline fun available(launch: () -> Unit): Boolean = try {
        launch()
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
