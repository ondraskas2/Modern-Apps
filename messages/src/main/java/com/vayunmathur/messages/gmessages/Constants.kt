package com.vayunmathur.messages.gmessages

/**
 * Endpoints + headers ported from
 * /Users/vayun/Documents/gmessages/pkg/libgm/util/{paths,constants,func}.go.
 *
 * All values are copied verbatim from the reference implementation —
 * Google's Messages-for-Web client uses the same constants. Bumping the
 * Chrome version periodically (it's part of the User-Agent + Sec-CH-UA)
 * is recommended to stay current with real Chrome releases.
 */
object Endpoints {
    const val GoogleApiKey = "AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8"
    const val UserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    const val SecUA = "\"Google Chrome\";v=\"146\", \"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\""
    const val UAPlatform = "Linux"
    const val SecUAMobile = "?0"
    const val XUserAgent = "grpc-web-javascript/0.1"

    /** Base URL the QR code points at. Combined with a base64-encoded
     *  URLData protobuf to form the actual scanned URL. */
    const val QrCodeBaseUrl = "https://support.google.com/messages/?p=web_computer#?c="

    const val MessagesBaseUrl = "https://messages.google.com"
    const val GoogleAuthenticationUrl = "$MessagesBaseUrl/web/authentication"
    const val GoogleTimesourceUrl = "$MessagesBaseUrl/web/timesource"

    private const val ImBase = "https://instantmessaging-pa.googleapis.com"
    private const val ImBaseGoogle = "https://instantmessaging-pa.clients6.google.com"

    private const val PairingBase = "$ImBase/\$rpc/google.internal.communications.instantmessaging.v1.Pairing"
    const val RegisterPhoneRelayUrl = "$PairingBase/RegisterPhoneRelay"
    const val RefreshPhoneRelayUrl = "$PairingBase/RefreshPhoneRelay"
    const val GetWebEncryptionKeyUrl = "$PairingBase/GetWebEncryptionKey"
    const val RevokeRelayPairingUrl = "$PairingBase/RevokeRelayPairing"

    private const val RegistrationBase = "$ImBase/\$rpc/google.internal.communications.instantmessaging.v1.Registration"
    const val RegisterRefreshUrl = "$RegistrationBase/RegisterRefresh"

    private const val MessagingBase = "$ImBase/\$rpc/google.internal.communications.instantmessaging.v1.Messaging"
    const val ReceiveMessagesUrl = "$MessagingBase/ReceiveMessages"
    const val SendMessageUrl = "$MessagingBase/SendMessage"
    const val AckMessagesUrl = "$MessagingBase/AckMessages"

    /** Used by [com.vayunmathur.messages.gmessages.Media] for the
     *  resumable-upload + finalize media flow. The body is form-encoded
     *  on START and raw on FINALIZE; both go to the same URL. */
    const val UploadMediaUrl = "$ImBase/upload"
}

/** MIME types for the two wire encodings the relay accepts. */
object ContentTypes {
    /** Native protobuf binary encoding — used for inbound long-poll
     *  payloads and Pairing/Receive paths. */
    const val Protobuf = "application/x-protobuf"

    /** Google's "PB-Lite" JSON-array encoding — used for some outbound
     *  paths including SendMessage. See [PbLite] for the codec. */
    const val PbLite = "application/json+protobuf"
}
