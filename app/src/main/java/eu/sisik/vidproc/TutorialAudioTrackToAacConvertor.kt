/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */
package eu.sisik.vidproc

import android.media.*
import java.lang.IllegalArgumentException


/**
 * This class decodes audio track from given input file and converts it into m4a/aac format. It is following the steps
 * described in my tutorial - sisik.eu/blog/android/media/mix-audio-into-video.
 *
 * This class is based on examples from Android's CTS. For more information, please see
 * https://android.googlesource.com/platform/cts/+/jb-mr2-release/tests/tests/media/src/android/media/cts/DecodeEditEncodeTest.java
 */
class TutorialAudioTrackToAacConvertor {

    fun convert(inFile: String, outFileM4a: String) {

        val extractor = MediaExtractor()
        extractor.setDataSource(inFile)

        // Find Audio Track
        for (i in 0 until extractor.trackCount) {
            val inputFormat = extractor.getTrackFormat(i)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)

            if (mime.startsWith("audio/")) {

                extractor.selectTrack(i)

                val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME))
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                // Prepare output format for aac/m4a
                val outputFormat = MediaFormat()
                outputFormat.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm")
                outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                outputFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, inputFormat.getInteger(MediaFormat.KEY_BIT_RATE))
                outputFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,1048576) // Needs to be large enough to avoid BufferOverflowException

                // Init encoder
                val encoder = MediaCodec.createEncoderByType(outputFormat.getString(MediaFormat.KEY_MIME))
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                // Init muxer
                val muxer = MediaMuxer(outFileM4a, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                var allInputExtracted = false
                var allInputDecoded = false
                var allOutputEncoded = false

                val timeoutUs = 10000L
                val bufferInfo = MediaCodec.BufferInfo()
                var trackIndex = -1

                while (!allOutputEncoded) {

                    // Feed input to decoder
                    if (!allInputExtracted) {
                        val inBufferId = decoder.dequeueInputBuffer(timeoutUs)
                        if (inBufferId >= 0) {
                            val buffer = decoder.getInputBuffer(inBufferId)
                            val sampleSize = extractor.readSampleData(buffer, 0)

                            if (sampleSize >= 0) {
                                decoder.queueInputBuffer(
                                    inBufferId, 0, sampleSize,
                                    extractor.sampleTime, extractor.sampleFlags
                                )

                                extractor.advance()
                            } else {
                                decoder.queueInputBuffer(
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
                            val outBufferId = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                            if (outBufferId >= 0) {
                                val outBuffer = decoder.getOutputBuffer(outBufferId)

                                // If needed, process decoded data here
                                // ...

                                // We drained the encoder, so there should be input buffer
                                // available. If this is not the case, we get a NullPointerException
                                // when touching inBuffer
                                val inBufferId = encoder.dequeueInputBuffer(timeoutUs)
                                val inBuffer = encoder.getInputBuffer(inBufferId)

                                // Copy buffers - decoder output goes to encoder input
                                inBuffer.put(outBuffer)

                                // Feed encoder
                                encoder.queueInputBuffer(
                                    inBufferId, bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs,
                                    bufferInfo.flags)

                                decoder.releaseOutputBuffer(outBufferId, false)

                                // Did we get all output from decoder?
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                                    allInputDecoded = true

                            } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                decoderOutputAvailable = false
                            }
                        }
                    }
                }

                // Cleanup
                extractor.release()

                decoder.stop()
                decoder.release()

                encoder.stop()
                encoder.release()

                muxer.stop()
                muxer.release()

                return
            }
        }

        throw IllegalArgumentException("Input file doesn't contain audio track")
    }
}