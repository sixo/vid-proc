package eu.sisik.vidproc

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.util.Size
import java.lang.RuntimeException
import kotlin.math.absoluteValue

/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */

fun isServiceRunning(context: Context, clazz: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    for (i in am.getRunningServices(Integer.MAX_VALUE)) {
        if (i.service.className == clazz.name)
            return true
    }

    return false
}

fun getBestSupportedResolution(mediaCodec: MediaCodec, mime: String, preferredResolution: Size): Size {

    // First check if exact combination supported
    if (mediaCodec.codecInfo.getCapabilitiesForType(mime)
            .videoCapabilities.isSizeSupported(preferredResolution.width, preferredResolution.height))
        return preferredResolution

    // I try the resolutions suggested by docs for H.264 and VP8
    // https://developer.android.com/guide/topics/media/media-formats#video-encoding
    // TODO: find more supported resolutions
    val resolutions = arrayListOf(
        Size(176, 144),
        Size(320, 240),
        Size(320, 180),
        Size(640, 360),
        Size(720, 480),
        Size(1280, 720),
        Size(1920, 1080)
    )

    // I prefer similar resolution with similar aspect
    val pix = preferredResolution.width * preferredResolution.height
    val preferredAspect = preferredResolution.width.toFloat() / preferredResolution.height.toFloat()

    val nearestToFurthest = resolutions.sortedWith(compareBy(
        {
            pix - it.width * it.height
        },
        // First compare by aspect
        {
            val aspect = if (it.width < it.height) it.width.toFloat() / it.height.toFloat()
            else it.height.toFloat()/it.width.toFloat()
            (preferredAspect - aspect).absoluteValue
        }))

    for (size in nearestToFurthest) {
        if (mediaCodec.codecInfo.getCapabilitiesForType(mime)
                    .videoCapabilities.isSizeSupported(size.width, size.height))
            return size
    }

    throw RuntimeException("Couldn't find supported resolution")
}

fun needsStoragePermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(
        Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
}

fun requestStoragePermission(activity: AppCompatActivity, code: Int) {
    if (Build.VERSION.SDK_INT >= 23)
        activity.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), code)
}

fun performFileSearch(activity: AppCompatActivity, code: Int, multiple: Boolean, type: String,
                      vararg mimetype: String) {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        this.type = type
        putExtra(Intent.EXTRA_MIME_TYPES, mimetype)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
    }

    activity.startActivityForResult(intent, code)
}

fun performAudioSearch(activity: AppCompatActivity, code: Int) {
    performFileSearch(activity, code, false,
        "audio/*",
        "audio/3gpp", "audio/mpeg", "audio/x-ms-wma", "audio/x-wav", "audio/x-flac")
}

fun performImagesSearch(activity: AppCompatActivity, code: Int) {
    performFileSearch(activity, code, true,
        "image/*",
        "image/png", "image/jpeg", "image/tiff")
}