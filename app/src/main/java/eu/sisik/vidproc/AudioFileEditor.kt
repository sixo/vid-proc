package eu.sisik.vidproc

import android.media.*
import android.util.Log
import java.lang.IllegalArgumentException
import java.lang.RuntimeException

/**
 *
 */
class AudioFileEditor(val audioProcessor: () -> Unit) {

    fun process(inFile: String, outFile: String) {

        val extractor = MediaExtractor()
        extractor.setDataSource(inFile)

        // Find Audio Track
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)

            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {

                // Extract convert...
                extractor.selectTrack(i)

                val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME))
                decoder.configure(format, null, null, 0)
                decoder.start()

                val outFormat = getOutputFormat(format)
                val encoder = MediaCodec.createEncoderByType(outFormat.getString(MediaFormat.KEY_MIME))
                encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                // Init muxer
                val muxer = MediaMuxer(outFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                var allInputExtracted = false
                var allInputDecoded = false
                var allOutputEncoded = false

                val timeoutUs = 10000L
                val bufferInfo = MediaCodec.BufferInfo()
                var trackIndex = -1

                while (!allOutputEncoded) {

                    // Feed input to decoder
                    if (!allInputExtracted) {
                        Log.d("tst", "decoded extracting")
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

                                // Process decoded data here
                                // ...

                                // Feed encoder
                                val inBufferId = encoder.dequeueInputBuffer(timeoutUs)
                                if (inBufferId < 0)
                                    throw RuntimeException("Could not get input buffer from encoder even after draining encoder output")

                                val inBuffer = encoder.getInputBuffer(inBufferId)

                                // Copy buffers - decoder output goes to encoder input
                                inBuffer.clear()
                                inBuffer.put(outBuffer)

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
}