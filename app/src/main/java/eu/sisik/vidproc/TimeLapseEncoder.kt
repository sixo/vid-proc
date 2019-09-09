/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */

package eu.sisik.vidproc

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.*
import java.lang.RuntimeException
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.lang.Exception

/**
 * This class is responsible for generating a time-lapse video from multiple input images.
 * You can find more information about how it works at https://sisik.eu/blog/android/media/images-to-video
 *
 * The default configuration produces H.264 video with 30 FPS, but it should be fairly easy to customize it
 * and use your preferred configuration.
 *
 * I used various information sources to glue this together, but I found the most important and most relevant
 * information in EncodeAndMuxTest.java at https://bigflake.com/mediacodec/EncodeAndMuxTest.java.txt
 */
class TimeLapseEncoder {

    // MediaCodec and encoding configuration
    private var encoder: MediaCodec? = null

    private var muxer: MediaMuxer? = null

    private var mime = "video/avc"

    private var trackIndex = -1

    private var presentationTimeUs = 0L

    private var frameRate = 30

    private val timeoutUs = 10000L

    private val bufferInfo = MediaCodec.BufferInfo()

    private var size: Size? = null


    // EGL
    private var eglDisplay: EGLDisplay? = null

    private var eglContext: EGLContext? = null

    private var eglSurface: EGLSurface? = null


    // Surface provided by MediaCodec and used to get data produced by OpenGL
    private var surface: Surface? = null


    fun encode(outVideoFilePath: String, imageUris: List<Uri>, contentResolver: ContentResolver) {
        try {
            initEncoder(outVideoFilePath, imageUris, contentResolver)

            encodeImages(imageUris, contentResolver)
        } catch (e: Exception) {
            Log.e(TAG, "Encoding failed: " + e)
        } finally {
            releaseEncoder()
        }
    }

    private fun initEncoder(outVideoFilePath: String, imageUris: List<Uri>, contentResolver: ContentResolver) {
        encoder = MediaCodec.createEncoderByType(mime)

        // Try to find supported size by checking the resolution of first supplied image
        // This could also be set manually as parameter to TimeLapseEncoder
        size = getSupportedSize(imageUris[0], contentResolver)

        val format = getFormat(size!!)

        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Prepare surface
        initEgl()

        // Switch to executing state - we're ready to encode
        encoder!!.start()

        // Prepare muxer
        muxer = MediaMuxer(outVideoFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun getSupportedSize(inBitmapUri: Uri, contentResolver: ContentResolver): Size {
        val first = MediaStore.Images.Media.getBitmap(contentResolver, inBitmapUri)
        return getBestSupportedResolution(encoder!!, mime, Size(first.width, first.height))
    }

    private fun getFormat(size: Size): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime, size.width, size.height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 15)

        return format
    }

    private fun initEgl() {
        surface = encoder!!.createInputSurface()
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY)
            throw RuntimeException("eglDisplay == EGL14.EGL_NO_DISPLAY: "
                    + GLUtils.getEGLErrorString(EGL14.eglGetError()))

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
            throw RuntimeException("eglInitialize(): " + GLUtils.getEGLErrorString(EGL14.eglGetError()))

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val nConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, nConfigs, 0)

        var err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
        err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
            throw RuntimeException("eglMakeCurrent(): " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
    }

    private fun encodeImages(imageUris: List<Uri>, contentResolver: ContentResolver) {
        // Init OpenGL, once we have initialized context and surface
        val renderer = TextureRenderer()

        for (imageUri in imageUris) {
            // Get encoded data and feed it to muxer
            drainEncoder(false)

            // Render the bitmap/texture here
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
            renderer.draw(size!!.width, size!!.height, bitmap, getMvp())

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface,
                presentationTimeUs * 1000)

            // Feed encoder with next frame produced by OpenGL
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        // Drain last remaining encoded data and finalize the video file
        drainEncoder(true)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream)
            encoder!!.signalEndOfInputStream()

        while (true) {
            val outBufferId = encoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)

            if (outBufferId >= 0) {
                val encodedBuffer = encoder!!.getOutputBuffer(outBufferId)

                // MediaMuxer is ignoring KEY_FRAMERATE, so I set it manually here
                // to achieve the desired frame rate
                bufferInfo.presentationTimeUs = presentationTimeUs
                muxer!!.writeSampleData(trackIndex, encodedBuffer, bufferInfo)

                presentationTimeUs += 1000000/frameRate

                encoder!!.releaseOutputBuffer(outBufferId, false)

                // Are we finished here?
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                    break
            } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream)
                    break

                // End of stream, but still no output available. Try again.
            } else if (outBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                muxer!!.start()
            }
        }
    }

    private fun getMvp(): FloatArray {
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, 1f, -1f, 1f)

        return mvp
    }

    private fun releaseEncoder() {
        encoder?.stop()
        encoder?.release()
        encoder = null

        releaseEgl()

        muxer?.stop()
        muxer?.release()
        muxer = null

        size = null
        trackIndex = -1
        presentationTimeUs = 0L
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }

        surface?.release()
        surface = null

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }


    companion object {
        const val TAG = "TimeLapseEncoder"
    }
}
