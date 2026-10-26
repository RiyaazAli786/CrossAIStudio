package com.pintoinsta.app.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object VideoMuxerUtils {
    private const val TAG = "VideoMuxerUtils"

    /**
     * Muxes separate video and audio tracks (e.g. CMAF .cmfv and .cmfa)
     * into a single standard MP4 file using Android's native MediaExtractor & MediaMuxer.
     * Requires no external dependencies or FFmpeg.
     */
    fun mux(videoFile: File, audioFile: File?, outputFile: File): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            videoExtractor = MediaExtractor().apply {
                setDataSource(videoFile.absolutePath)
            }

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                Log.w(TAG, "No video track found in ${videoFile.name}, copying directly")
                videoFile.copyTo(outputFile, overwrite = true)
                return outputFile.exists() && outputFile.length() > 0
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                try {
                    audioExtractor = MediaExtractor().apply {
                        setDataSource(audioFile.absolutePath)
                    }
                    for (i in 0 until audioExtractor.trackCount) {
                        val format = audioExtractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            audioTrackIndex = i
                            audioFormat = format
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading audio track: ${e.message}")
                }
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoExtractor.selectTrack(videoTrackIndex)
            val muxerVideoTrack = muxer.addTrack(videoFormat)

            val muxerAudioTrack = if (audioTrackIndex != -1 && audioFormat != null && audioExtractor != null) {
                audioExtractor.selectTrack(audioTrackIndex)
                muxer.addTrack(audioFormat)
            } else {
                -1
            }

            muxer.start()

            val bufferSize = 1024 * 1024 // 1MB buffer
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // Write video samples
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // Write audio samples if available
            if (muxerAudioTrack != -1 && audioExtractor != null) {
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    bufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }

            muxer.stop()
            Log.d(TAG, "Muxed video successfully to ${outputFile.name} (${outputFile.length()} bytes)")
            outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "Muxing error (${e.message}), copying source video directly")
            try {
                videoFile.copyTo(outputFile, overwrite = true)
                outputFile.exists() && outputFile.length() > 0
            } catch (_: Exception) {
                false
            }
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
