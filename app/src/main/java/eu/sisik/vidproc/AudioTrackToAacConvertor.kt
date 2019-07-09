/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */
package eu.sisik.vidproc

import android.media.*
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.InvalidParameterException
import kotlin.math.log


/**
 * This class converts audio from various audio formats that are supported by Android's decoders into
 * m4a/aac audio.
 * It is based on the examples from Android's CTS. For more information, please see
 * https://android.googlesource.com/platform/cts/+/jb-mr2-release/tests/tests/media/src/android/media/cts/DecodeEditEncodeTest.java
 */
class AudioTrackToAacConvertor {

    var extractor: MediaExtractor? = null
    var muxer: MediaMuxer? = null
    var decoder: MediaCodec? = null
    var encoder: MediaCodec? = null

    val timeoutUs = 10000L
    val bufferInfo = MediaCodec.BufferInfo()
    var trackIndex = -1

    fun convert(inFd: FileDescriptor, outFile: String, maxDurationMillis: Int = -1,
                fadeInDurationMillis: Int = -1, fadeOutDurationMillis: Int = -1) {

        extractor = MediaExtractor()
        extractor!!.setDataSource(inFd)

        // Init muxer
        muxer = MediaMuxer(outFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        convert(maxDurationMillis, fadeInDurationMillis, fadeOutDurationMillis)
    }

    fun convert(inFile: String, outFile: String, maxDurationMillis: Int = -1,
                fadeInDurationMillis: Int = -1, fadeOutDurationMillis: Int = -1) {

        extractor = MediaExtractor()
        extractor!!.setDataSource(inFile)

        // Init muxer
        muxer = MediaMuxer(outFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        convert(maxDurationMillis, fadeInDurationMillis, fadeOutDurationMillis)
    }

    private fun convert(maxDurationMillis: Int = -1, fadeInDurationMillis: Int = -1,
                        fadeOutDurationMillis: Int = -1) {
        try {
            val inFormat = selectAudioTrack(extractor!!)
            initCodecs(inFormat)

            var allInputExtracted = false
            var allInputDecoded = false
            var allOutputEncoded = false

            // This will determine the total duration of output file
            var totalDurationMillis = maxDurationMillis
            if (totalDurationMillis < 0)
                totalDurationMillis = (inFormat.getLong(MediaFormat.KEY_DURATION) / 1000).toInt()

            while (!allOutputEncoded) {

                // Feed input to decoder
                if (!allInputExtracted) {
                    val inBufferId = decoder!!.dequeueInputBuffer(timeoutUs)
                    if (inBufferId >= 0) {
                        val buffer = decoder!!.getInputBuffer(inBufferId)
                        val sampleSize = extractor!!.readSampleData(buffer, 0)

                        if (sampleSize >= 0 && totalDurationMillis >= extractor!!.sampleTime / 1000) {
                            decoder!!.queueInputBuffer(
                                inBufferId, 0, sampleSize,
                                extractor!!.sampleTime, extractor!!.sampleFlags
                            )

                            extractor!!.advance()
                        } else {
                            decoder!!.queueInputBuffer(
                                inBufferId, 0, 0,
                                0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            allInputExtracted = true
                        }
                    }
                }

                var encoderOutputAvailable = true
                var decoderOutputAvailable = !allInputDecoded

                while (encoderOutputAvailable || decoderOutputAvailable) {
                    // Drain Encoder & mux first
                    val outBufferId = encoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    if (outBufferId >= 0) {

                        val encodedBuffer = encoder!!.getOutputBuffer(outBufferId)

                        muxer!!.writeSampleData(trackIndex, encodedBuffer, bufferInfo)

                        encoder!!.releaseOutputBuffer(outBufferId, false)

                        // Are we finished here?
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            allOutputEncoded = true
                            break
                        }
                    } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        encoderOutputAvailable = false
                    } else if (outBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                        muxer!!.start()
                    }

                    if (outBufferId != MediaCodec.INFO_TRY_AGAIN_LATER)
                        continue

                    // Get output from decoder and feed it to encoder
                    if (!allInputDecoded) {
                        val outBufferId = decoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        if (outBufferId >= 0) {
                            val outBuffer = decoder!!.getOutputBuffer(outBufferId)

                            val inBufferId = encoder!!.dequeueInputBuffer(timeoutUs)
                            val inBuffer = encoder!!.getInputBuffer(inBufferId)

                            var tillEndMillis = totalDurationMillis - bufferInfo.presentationTimeUs / 1000.0
                            val format = decoder!!.getOutputFormat(outBufferId)

                            // Fade in?
                            if (bufferInfo.presentationTimeUs < fadeInDurationMillis * 1000) {
                                fadeIn(inBufferId, outBufferId, fadeInDurationMillis.toLong())
                            }
                            // Fade out?
                            else if (fadeOutDurationMillis >= tillEndMillis) {
                                fadeOut(inBufferId, outBufferId, fadeOutDurationMillis.toLong(), tillEndMillis.toLong())
                            } else {
                                // Just copy whole buffer without processing
                                inBuffer.put(outBuffer)
                            }

                            // Feed to encoder
                            encoder!!.queueInputBuffer(
                                inBufferId, bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs,
                                bufferInfo.flags
                            )

                            decoder!!.releaseOutputBuffer(outBufferId, false)

                            // Did we get all output from decoder?
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                                allInputDecoded = true

                        } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            decoderOutputAvailable = false
                        }
                    }
                }
            }
        } finally {
            cleanup()
        }
    }

    private fun initCodecs(inFormat: MediaFormat) {
        decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME))
        decoder!!.configure(inFormat, null, null, 0)
        decoder!!.start()

        val outFormat = getOutputFormat(inFormat)
        encoder = MediaCodec.createEncoderByType(outFormat.getString(MediaFormat.KEY_MIME))
        encoder!!.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder!!.start()

    }

    private fun selectAudioTrack(extractor: MediaExtractor): MediaFormat {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                extractor.selectTrack(i)
                return format
            }
        }

        throw InvalidParameterException("File contains no audio track")
    }

    private fun getOutputFormat(inputFormat: MediaFormat): MediaFormat {
        val format = MediaFormat()
        format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm")
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        format.setInteger(MediaFormat.KEY_BIT_RATE, inputFormat.getInteger(MediaFormat.KEY_BIT_RATE))
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,1048576) // Important, otherwise you can get BufferOverflowException when copying decoded output to encoder input buffer

        return format
    }

    private fun fadeIn(inBufferId: Int, outBufferId: Int, fadeInDurationMillis: Long) {
        var totalElapsedMillis = bufferInfo.presentationTimeUs / 1000

        fade(inBufferId, outBufferId) { elapsedMillis ->
            // How much progress since the start of fade in effect?
            val progress = (totalElapsedMillis.toDouble() + elapsedMillis) / fadeInDurationMillis

            // Using exponential factor to increase volume
            progress * progress
        }
    }

    private fun fadeOut(inBufferId: Int, outBufferId: Int, fadeOutDurationMillis: Long, tillEndMillis: Long) {
        var totalElapsedMillis = fadeOutDurationMillis - tillEndMillis

        fade(inBufferId, outBufferId) { elapsedMillis ->
            // How much progress since the start of fade in effect?
            val progress = (totalElapsedMillis.toDouble() + elapsedMillis) / fadeOutDurationMillis

            // Logarithic factor for decreasing volume
            (20.0 * log(progress, 10.0)) / -40.0
        }
    }

    private fun fade(inBufferId: Int, outBufferId: Int, getFactor: (elapsedMillis: Long) -> Double) {
        val inBuffer = encoder!!.getInputBuffer(inBufferId)
        val outBuffer = decoder!!.getOutputBuffer(outBufferId)
        val shortSamples = outBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()

        val format = decoder!!.getOutputFormat(outBufferId)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val size = shortSamples.remaining()
        val sampleDurationMillis = 1000L / format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var elapsedMillis = 0L

        for (i in 0 until size step channels) {
            for (c in 0 until channels) {
                // Process the sample
                var sample = shortSamples.get()

                // Put processed sample into encoder's buffer
                inBuffer.putShort((sample * getFactor(elapsedMillis)).toShort())
            }

            elapsedMillis += sampleDurationMillis
        }
    }

    private fun cleanup() {
        extractor?.release()
        extractor = null

        decoder?.stop()
        decoder?.release()
        decoder = null

        encoder?.stop()
        encoder?.release()
        encoder = null

        muxer?.stop()
        muxer?.release()
        muxer = null

        trackIndex = -1
    }
}