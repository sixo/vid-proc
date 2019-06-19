package eu.sisik.vidproc

import android.app.IntentService
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri

/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */
class EncodingService: IntentService(TAG) {

    override fun onHandleIntent(p0: Intent?) {
        when (p0?.action) {
            ACTION_ENCODE_IMAGES -> encodeImages(p0)
        }
    }

    private fun encodeImages(intent: Intent) {
        val imageUris = intent.getParcelableArrayListExtra<Uri>(KEY_IMAGES)
        val outPath = intent.getStringExtra(KEY_OUT_PATH)

        // Generate video from provided image uris
        TimeLapseEncoder().encode(outPath, imageUris, contentResolver)

        // Notify MainActivity that we're done
        // TODO: add error message and change result code in case of error
        val pi = intent.getParcelableExtra<PendingIntent>(KEY_RESULT_INTENT)
        pi.send()
    }


    companion object {

        val TAG = this::class.java.simpleName

        const val ACTION_ENCODE_IMAGES = "eu.sisik.vidproc.action.ENCODE_IMAGES"

        const val KEY_IMAGES = "eu.sisik.vidproc.key.IMAGES"

        const val KEY_OUT_PATH = "eu.sisik.vidproc.key.OUT_PATH"

        const val KEY_RESULT_INTENT = "eu.sisik.vidproc.key.RESULT_INTENT"
    }
}