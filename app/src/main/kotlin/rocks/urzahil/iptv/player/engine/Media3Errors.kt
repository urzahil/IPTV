package rocks.urzahil.iptv.player.engine

import androidx.media3.common.PlaybackException
import rocks.urzahil.iptv.player.model.FailureKind
import rocks.urzahil.iptv.player.model.PlayerFailure

/** Maps Media3 error codes to engine independent failures. */
object Media3Errors {
    fun classify(errorCode: Int, httpStatus: Int?, message: String = ""): PlayerFailure {
        val kind = when (errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED -> FailureKind.DECODER
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> FailureKind.FORMAT_UNSUPPORTED
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> FailureKind.CONTAINER_UNSUPPORTED
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> FailureKind.CONTAINER_MALFORMED
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> FailureKind.AUDIO_SINK
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> FailureKind.BEHIND_LIVE_WINDOW
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> FailureKind.HTTP_CLIENT_ERROR
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when {
                httpStatus != null && httpStatus in 400..499 -> FailureKind.HTTP_CLIENT_ERROR
                else -> FailureKind.HTTP_SERVER_ERROR
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> FailureKind.NETWORK
            else -> FailureKind.OTHER
        }
        return PlayerFailure(kind, errorCode, httpStatus, message)
    }
}
