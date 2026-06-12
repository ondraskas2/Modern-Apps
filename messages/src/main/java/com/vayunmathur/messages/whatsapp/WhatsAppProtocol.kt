package com.vayunmathur.messages.whatsapp

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest

/**
 * WhatsApp Web protocol implementation.
 * Implements Noise_XX_25519_AESGCM_SHA256 handshake, binary XML encoding,
 * protobuf E2E message format, and media encryption.
 *
 * Reference: whatsmeow (github.com/tulir/whatsmeow)
 */
object WhatsAppProtocol {
    private const val TAG = "WhatsAppProtocol"

    const val WS_URL = "wss://web.whatsapp.com/ws/chat"
    const val WS_ORIGIN = "https://web.whatsapp.com"

    // Noise protocol pattern — 32 bytes, null-padded
    const val NOISE_START_PATTERN = "Noise_XX_25519_AESGCM_SHA256\u0000\u0000\u0000\u0000"

    // WA connection header: 'W', 'A', WAMagicValue(6), DictVersion(3)
    val WA_CONN_HEADER = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 6, 3)

    // Frame constants (from whatsmeow/socket/constants.go)
    const val FRAME_MAX_SIZE = 1 shl 24
    const val FRAME_LENGTH_SIZE = 3

    // WhatsApp web message ID prefix
    private const val WEB_MESSAGE_ID_PREFIX = "3EB0"

    // WhatsApp web client version (from whatsmeow/store/clientpayload.go)
    val WA_VERSION = intArrayOf(2, 3000, 1040390703)

    // Media type keys for HKDF (from whatsmeow/download.go)
    const val MEDIA_KEY_IMAGE = "WhatsApp Image Keys"
    const val MEDIA_KEY_VIDEO = "WhatsApp Video Keys"
    const val MEDIA_KEY_AUDIO = "WhatsApp Audio Keys"
    const val MEDIA_KEY_DOCUMENT = "WhatsApp Document Keys"
    const val MEDIA_KEY_STICKER = "WhatsApp Image Keys"
    const val MEDIA_KEY_PTV = "WhatsApp Video Keys"
    const val MEDIA_KEY_HISTORY = "WhatsApp History Keys"
    const val MEDIA_KEY_APP_STATE = "WhatsApp App State Keys"
    const val MEDIA_KEY_STICKER_PACK = "WhatsApp Sticker Pack Keys"
    const val MEDIA_KEY_LINK_THUMBNAIL = "WhatsApp Link Thumbnail Keys"

    // WhatsApp certificate authority public key (Ed25519)
    val WA_CERT_PUBKEY = byteArrayOf(
        0x14, 0x23, 0x75, 0x57, 0x4d, 0x0a, 0x58, 0x71,
        0x66, 0xaa.toByte(), 0xe7.toByte(), 0x1e, 0xbe.toByte(), 0x51, 0x64, 0x37,
        0xc4.toByte(), 0xa2.toByte(), 0x8b.toByte(), 0x73, 0xe3.toByte(), 0x69, 0x5c, 0x6c,
        0xe1.toByte(), 0xf7.toByte(), 0xf9.toByte(), 0x54, 0x5d, 0xa8.toByte(), 0xee.toByte(), 0x6b
    )

    data class Node(
        val tag: String,
        val attrs: Map<String, String> = emptyMap(),
        val content: List<Node> = emptyList(),
        val data: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Node
            if (tag != other.tag) return false
            if (attrs != other.attrs) return false
            if (content != other.content) return false
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = tag.hashCode()
            result = 31 * result + attrs.hashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + (data?.contentHashCode() ?: 0)
            return result
        }

        fun getChildren(): List<Node> = content

        fun getChildByTag(tag: String): Node? = content.find { it.tag == tag }
    }

    /**
     * Noise Protocol Handshake State Machine
     * Implements Noise_XX_25519_AESGCM_SHA256 as per whatsmeow/socket/noisehandshake.go
     */
    class NoiseHandshake {
        private var hash = ByteArray(32)
        private var salt = ByteArray(32)
        private var key: SecretKeySpec? = null
        private var counter: UInt = 0u

        fun start(pattern: String, header: ByteArray) {
            val data = pattern.toByteArray(Charsets.UTF_8)
            hash = if (data.size == 32) {
                data
            } else {
                sha256(data)
            }
            salt = hash.copyOf()
            key = SecretKeySpec(hash, "AES")
            authenticate(header)
        }

        fun authenticate(data: ByteArray) {
            hash = sha256(hash + data)
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val currentKey = key ?: throw IllegalStateException("Handshake not started")
            val iv = generateIV(counter)
            counter++

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, spec)
            cipher.updateAAD(hash)
            val ciphertext = cipher.doFinal(plaintext)
            authenticate(ciphertext)
            return ciphertext
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val currentKey = key ?: throw IllegalStateException("Handshake not started")
            val iv = generateIV(counter)
            counter++

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, currentKey, spec)
            cipher.updateAAD(hash)
            val plaintext = cipher.doFinal(ciphertext)
            authenticate(ciphertext)
            return plaintext
        }

        fun mixSharedSecretIntoKey(privateKey: ByteArray, publicKey: ByteArray) {
            val sharedSecret = x25519(privateKey, publicKey)
            mixIntoKey(sharedSecret)
        }

        fun mixIntoKey(data: ByteArray) {
            counter = 0u
            val (newSalt, newKey) = extractAndExpand(salt, data)
            salt = newSalt
            key = SecretKeySpec(newKey, "AES")
        }

        private fun extractAndExpand(salt: ByteArray, data: ByteArray): Pair<ByteArray, ByteArray> {
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(data, salt, null))

            val writeKey = ByteArray(32)
            val readKey = ByteArray(32)
            hkdf.generateBytes(writeKey, 0, 32)
            hkdf.generateBytes(readKey, 0, 32)

            return Pair(writeKey, readKey)
        }

        fun finish(): Pair<SecretKeySpec, SecretKeySpec> {
            val (writeKey, readKey) = extractAndExpand(salt, ByteArray(0))
            return Pair(
                SecretKeySpec(writeKey, "AES"),
                SecretKeySpec(readKey, "AES")
            )
        }

        private fun generateIV(counter: UInt): ByteArray {
            val iv = ByteArray(12)
            ByteBuffer.wrap(iv, 8, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(counter.toInt())
            return iv
        }
    }

    // -- Cryptography helpers --

    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val pubParams = X25519PublicKeyParameters(publicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)
        return sharedSecret
    }

    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val random = SecureRandom()
        val privateKey = ByteArray(32)
        random.nextBytes(privateKey)
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = (privateKey[31].toInt() and 127).toByte()
        privateKey[31] = (privateKey[31].toInt() or 64).toByte()

        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val publicKey = privParams.generatePublicKey().encoded
        return Pair(privateKey, publicKey)
    }

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    /**
     * Derive media encryption keys from a media key using HKDF.
     * Returns (iv, cipherKey, macKey, refKey) — each used in media encrypt/decrypt.
     * From whatsmeow/download.go getMediaKeys()
     */
    fun getMediaKeys(mediaKey: ByteArray, mediaType: String): MediaKeys {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(mediaKey, null, mediaType.toByteArray(Charsets.UTF_8)))
        val expanded = ByteArray(112)
        hkdf.generateBytes(expanded, 0, 112)
        return MediaKeys(
            iv = expanded.copyOfRange(0, 16),
            cipherKey = expanded.copyOfRange(16, 48),
            macKey = expanded.copyOfRange(48, 80),
            refKey = expanded.copyOfRange(80, 112)
        )
    }

    data class MediaKeys(
        val iv: ByteArray,
        val cipherKey: ByteArray,
        val macKey: ByteArray,
        val refKey: ByteArray,
    )

    /**
     * Encrypt media using AES-256-CBC + HMAC-SHA256.
     * From whatsmeow/upload.go Upload()
     */
    fun encryptMedia(plaintext: ByteArray, mediaType: String): MediaEncryptResult {
        val random = SecureRandom()
        val mediaKey = ByteArray(32).also { random.nextBytes(it) }
        val keys = getMediaKeys(mediaKey, mediaType)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keys.cipherKey, "AES")
        val ivSpec = IvParameterSpec(keys.iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val ciphertext = cipher.doFinal(plaintext)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
        mac.update(keys.iv)
        mac.update(ciphertext)
        val macValue = mac.doFinal()

        val dataToUpload = ciphertext + macValue.copyOfRange(0, 10)
        val fileSha256 = sha256(plaintext)
        val fileEncSha256 = sha256(dataToUpload)

        return MediaEncryptResult(
            mediaKey = mediaKey,
            encryptedData = dataToUpload,
            fileSha256 = fileSha256,
            fileEncSha256 = fileEncSha256,
            fileLength = plaintext.size.toLong()
        )
    }

    data class MediaEncryptResult(
        val mediaKey: ByteArray,
        val encryptedData: ByteArray,
        val fileSha256: ByteArray,
        val fileEncSha256: ByteArray,
        val fileLength: Long,
    )

    /**
     * Decrypt downloaded media.
     * From whatsmeow/download.go
     */
    fun decryptMedia(ciphertextWithMac: ByteArray, mediaKey: ByteArray, mediaType: String): ByteArray {
        val keys = getMediaKeys(mediaKey, mediaType)

        val macOffset = ciphertextWithMac.size - 10
        val ciphertext = ciphertextWithMac.copyOfRange(0, macOffset)

        // Verify HMAC
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
        mac.update(keys.iv)
        mac.update(ciphertext)
        val expectedMac = mac.doFinal().copyOfRange(0, 10)
        val actualMac = ciphertextWithMac.copyOfRange(macOffset, ciphertextWithMac.size)
        if (!expectedMac.contentEquals(actualMac)) {
            throw SecurityException("Media HMAC verification failed")
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keys.cipherKey, "AES")
        val ivSpec = IvParameterSpec(keys.iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }

    // -- Message ID generation --

    /**
     * Generate a message ID in WhatsApp's format: "3EB0" + uppercase hex.
     * From whatsmeow/send.go GenerateMessageID()
     */
    fun generateMessageId(ownJid: String?): String {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putLong(System.currentTimeMillis() / 1000)
        val data = mutableListOf<Byte>()
        data.addAll(buf.array().toList())
        if (ownJid != null) {
            val user = ownJid.substringBefore("@")
            data.addAll((user + "@c.us").toByteArray(Charsets.UTF_8).toList())
        }
        val randomPart = ByteArray(16)
        SecureRandom().nextBytes(randomPart)
        data.addAll(randomPart.toList())

        val hash = sha256(data.toByteArray())
        val hex = hash.copyOfRange(0, 9).joinToString("") { "%02X".format(it) }
        return WEB_MESSAGE_ID_PREFIX + hex
    }

    // -- Message padding (Signal Protocol requirement) --

    /**
     * Pad message with random 1-15 bytes where each pad byte equals the pad count.
     * From whatsmeow/message.go padMessage()
     */
    fun padMessage(plaintext: ByteArray): ByteArray {
        var padSize = SecureRandom().nextInt(16) and 0x0F
        if (padSize == 0) padSize = 0x0F
        val padded = ByteArray(plaintext.size + padSize)
        System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
        for (i in plaintext.size until padded.size) {
            padded[i] = padSize.toByte()
        }
        return padded
    }

    fun unpadMessage(padded: ByteArray): ByteArray {
        if (padded.isEmpty()) return padded
        val padSize = padded.last().toInt() and 0xFF
        if (padSize > padded.size || padSize > 15 || padSize == 0) return padded
        return padded.copyOfRange(0, padded.size - padSize)
    }

    // -- Binary XML encoding/decoding (unchanged, already correct) --

    private object BinaryToken {
        const val LIST_EMPTY: Byte = 0
        const val DICTIONARY_0: Int = 236
        const val DICTIONARY_1: Int = 237
        const val DICTIONARY_2: Int = 238
        const val DICTIONARY_3: Int = 239
        const val LIST_8: Byte = 248.toByte()
        const val LIST_16: Byte = 249.toByte()
        const val JID_PAIR: Byte = 250.toByte()
        const val HEX_8: Int = 251
        const val BINARY_8: Byte = 252.toByte()
        const val BINARY_20: Byte = 253.toByte()
        const val BINARY_32: Byte = 254.toByte()
        const val NIBBLE_8: Int = 255
        const val PACKED_MAX = 127
        const val SINGLE_BYTE_MAX = 256

        val doubleByteTokens = arrayOf(
            arrayOf("read-self", "active", "fbns", "protocol", "reaction", "screen_width", "heartbeat", "deviceid", "2:47DEQpj8", "uploadfieldstat", "voip_settings", "retry", "priority", "longitude", "conflict", "false", "ig_professional", "replaced", "preaccept", "cover_photo", "uncompressed", "encopt", "ppic", "04", "passive", "status-revoke-drop", "keygen", "540", "offer", "rate", "opus", "latitude", "w:gp2", "ver", "4", "business_profile", "medium", "sender", "prev_v_id", "email", "website", "invited", "sign_credential", "05", "transport", "skey", "reason", "peer_abtest_bucket", "America/Sao_Paulo", "appid", "refresh", "100", "06", "404", "101", "104", "107", "102", "109", "103", "member_add_mode", "105", "transaction-id", "110", "106", "outgoing", "108", "111", "tokens", "followers", "ig_handle", "self_pid", "tue", "dec", "thu", "joinable", "peer_pid", "mon", "features", "wed", "peer_device_presence", "pn", "delete", "07", "fri", "audio_duration", "admin", "connected", "delta", "rcat", "disable", "collection", "08", "480", "sat", "phash", "all", "invite", "accept", "critical_unblock_low", "group_update", "signed_credential", "blinded_credential", "eph_setting", "net", "09", "background_location", "refresh_id", "Asia/Kolkata", "privacy_mode_ts", "account_sync", "voip_payload_type", "service_areas", "acs_public_key", "v_id", "0a", "fallback_class", "relay", "actual_actors", "metadata", "w:biz", "5", "connected-limit", "notice", "0b", "host_storage", "fb_page", "subject", "privatestats", "invis", "groupadd", "010", "note.m4r", "uuid", "0c", "8000", "sun", "372", "1020", "stage", "1200", "720", "canonical", "fb", "011", "video_duration", "0d", "1140", "superadmin", "012", "Opening.m4r", "keystore_attestation", "dleq_proof", "013", "timestamp", "ab_key", "w:sync:app:state", "0e", "vertical", "600", "p_v_id", "6", "likes", "014", "500", "1260", "creator", "0f", "rte", "destination", "group", "group_info", "syncd_anti_tampering_fatal_exception_enabled", "015", "dl_bw", "Asia/Jakarta", "vp8/h.264", "online", "1320", "fb:multiway", "10", "timeout", "016", "nse_retry", "urn:xmpp:whatsapp:dirty", "017", "a_v_id", "web_shops_chat_header_button_enabled", "nse_call", "inactive-upgrade", "none", "web", "groups", "2250", "mms_hot_content_timespan_in_seconds", "contact_blacklist", "nse_read", "suspended_group_deletion_notification", "binary_version", "018", "https://www.whatsapp.com/otp/copy/", "reg_push", "shops_hide_catalog_attachment_entrypoint", "server_sync", ".", "ephemeral_messages_allowed_values", "019", "mms_vcache_aggregation_enabled", "iphone", "America/Argentina/Buenos_Aires", "01a", "mms_vcard_autodownload_size_kb", "nse_ver", "shops_header_dropdown_menu_item", "dhash", "catalog_status", "communities_mvp_new_iqs_serverprop", "blocklist", "default", "11", "ephemeral_messages_enabled", "01b", "original_dimensions", "8", "mms4_media_retry_notification_encryption_enabled", "mms4_server_error_receipt_encryption_enabled", "original_image_url", "sync", "multiway", "420", "companion_enc_static", "shops_profile_drawer_entrypoint", "01c", "vcard_as_document_size_kb", "status_video_max_duration", "request_image_url", "01d", "regular_high", "s_t", "abt", "share_ext_min_preliminary_image_quality", "01e", "32", "syncd_key_rotation_enabled", "data_namespace", "md_downgrade_read_receipts2", "patch", "polltype", "ephemeral_messages_setting", "userrate", "15", "partial_pjpeg_bw_threshold", "played-self", "catalog_exists", "01f", "mute_v2"),
            arrayOf("reject", "dirty", "announcement", "020", "13", "9", "status_video_max_bitrate", "fb:thrift_iq", "offline_batch", "022", "full", "ctwa_first_business_reply_logging", "h.264", "smax_id", "group_description_length", "https://www.whatsapp.com/otp/code", "status_image_max_edge", "smb_upsell_business_profile_enabled", "021", "web_upgrade_to_md_modal", "14", "023", "s_o", "smaller_video_thumbs_status_enabled", "media_max_autodownload", "960", "blocking_status", "peer_msg", "joinable_group_call_client_version", "group_call_video_maximization_enabled", "return_snapshot", "high", "America/Mexico_City", "entry_point_block_logging_enabled", "pop", "024", "1050", "16", "1380", "one_tap_calling_in_group_chat_size", "regular_low", "inline_joinable_education_enabled", "hq_image_max_edge", "locked", "America/Bogota", "smb_biztools_deeplink_enabled", "status_image_quality", "1088", "025", "payments_upi_intent_transaction_limit", "voip", "w:g2", "027", "md_pin_chat_enabled", "026", "multi_scan_pjpeg_download_enabled", "shops_product_grid", "transaction_id", "ctwa_context_enabled", "20", "fna", "hq_image_quality", "alt_jpeg_doc_detection_quality", "group_call_max_participants", "pkey", "America/Belem", "image_max_kbytes", "web_cart_v1_1_order_message_changes_enabled", "ctwa_context_enterprise_enabled", "urn:xmpp:whatsapp:account", "840", "Asia/Kuala_Lumpur", "max_participants", "video_remux_after_repair_enabled", "stella_addressbook_restriction_type", "660", "900", "780", "context_menu_ios13_enabled", "mute-state", "ref", "payments_request_messages", "029", "frskmsg", "vcard_max_size_kb", "sample_buffer_gif_player_enabled", "match_last_seen", "510", "4983", "video_max_bitrate", "028", "w:comms:chat", "17", "frequently_forwarded_max", "groups_privacy_blacklist", "Asia/Karachi", "02a", "web_download_document_thumb_mms_enabled", "02b", "hist_sync", "biz_block_reasons_version", "1024", "18", "web_is_direct_connection_for_plm_transparent", "view_once_write", "file_max_size", "paid_convo_id", "online_privacy_setting", "video_max_edge", "view_once_read", "enhanced_storage_management", "multi_scan_pjpeg_encoding_enabled", "ctwa_context_forward_enabled", "video_transcode_downgrade_enable", "template_doc_mime_types", "hq_image_bw_threshold", "30", "body", "u_aud_limit_sil_restarts_ctrl", "other", "participating", "w:biz:directory", "1110", "vp8", "4018", "meta", "doc_detection_image_max_edge", "image_quality", "1170", "02c", "smb_upsell_chat_banner_enabled", "key_expiry_time_second", "pid", "stella_interop_enabled", "19", "linked_device_max_count", "md_device_sync_enabled", "02d", "02e", "360", "enhanced_block_enabled", "ephemeral_icon_in_forwarding", "paid_convo_status", "gif_provider", "project_name", "server-error", "canonical_url_validation_enabled", "wallpapers_v2", "syncd_clear_chat_delete_chat_enabled", "medianotify", "02f", "shops_required_tos_version", "vote", "reset_skey_on_id_change", "030", "image_max_edge", "multicast_limit_global", "ul_bw", "21", "25", "5000", "poll", "570", "22", "031", "1280", "WhatsApp", "032", "bloks_shops_enabled", "50", "upload_host_switching_enabled", "web_ctwa_context_compose_enabled", "ptt_forwarded_features_enabled", "unblocked", "partial_pjpeg_enabled", "fbid:devices", "height", "ephemeral_group_query_ts", "group_join_permissions", "order", "033", "alt_jpeg_status_quality", "migrate", "popular-bank", "win_uwp_deprecation_killswitch_enabled", "web_download_status_thumb_mms_enabled", "blocking", "url_text", "035", "web_forwarding_limit_to_groups", "1600", "val", "1000", "syncd_msg_date_enabled", "bank-ref-id", "max_subject", "payments_web_enabled", "web_upload_document_thumb_mms_enabled", "size", "request", "ephemeral", "24", "receipt_agg", "ptt_remember_play_position", "sampling_weight", "enc_rekey", "mute_always", "037", "034", "23", "036", "action", "click_to_chat_qr_enabled", "width", "disabled", "038", "md_blocklist_v2", "played_self_enabled", "web_buttons_message_enabled", "flow_id", "clear", "450", "fbid:thread", "bloks_session_state", "America/Lima", "attachment_picker_refresh", "download_host_switching_enabled", "1792", "u_aud_limit_sil_restarts_test2", "custom_urls", "device_fanout", "optimistic_upload", "2000", "key_cipher_suite", "web_smb_upsell_in_biz_profile_enabled", "e", "039", "siri_post_status_shortcut", "pair-device", "lg", "lc", "stream_attribution_url", "model", "mspjpeg_phash_gen", "catalog_send_all", "new_multi_vcards_ui", "share_biz_vcard_enabled", "-", "clean", "200", "md_blocklist_v2_server", "03b", "03a", "web_md_migration_experience", "ptt_conversation_waveform", "u_aud_limit_sil_restarts_test1"),
            arrayOf("64", "ptt_playback_speed_enabled", "web_product_list_message_enabled", "paid_convo_ts", "27", "manufacturer", "psp-routing", "grp_uii_cleanup", "ptt_draft_enabled", "03c", "business_initiated", "web_catalog_products_onoff", "web_upload_link_thumb_mms_enabled", "03e", "mediaretry", "35", "hfm_string_changes", "28", "America/Fortaleza", "max_keys", "md_mhfs_days", "streaming_upload_chunk_size", "5541", "040", "03d", "2675", "03f", "...", "512", "mute", "48", "041", "alt_jpeg_quality", "60", "042", "md_smb_quick_reply", "5183", "c", "1343", "40", "1230", "043", "044", "mms_cat_v1_forward_hot_override_enabled", "user_notice", "ptt_waveform_send", "047", "Asia/Calcutta", "250", "md_privacy_v2", "31", "29", "128", "md_messaging_enabled", "046", "crypto", "690", "045", "enc_iv", "75", "failure", "ptt_oot_playback", "AIzaSyDR5yfaG7OG8sMTUj8kfQEb8T9pN8BM6Lk", "w", "048", "2201", "web_large_files_ui", "Asia/Makassar", "812", "status_collapse_muted", "1334", "257", "2HP4dm", "049", "patches", "1290", "43cY6T", "America/Caracas", "web_sticker_maker", "campaign", "ptt_pausable_enabled", "33", "42", "attestation", "biz", "04b", "query_linked", "s", "125", "04a", "810", "availability", "1411", "responsiveness_v2_m1", "catalog_not_created", "34", "America/Santiago", "1465", "enc_p", "04d", "status_info", "04f", "key_version", "..", "04c", "04e", "md_group_notification", "1598", "1215", "web_cart_enabled", "37", "630", "1920", "2394", "-1", "vcard", "38", "elapsed", "36", "828", "peer", "pricing_category", "1245", "invalid", "stella_ios_enabled", "2687", "45", "1528", "39", "u_is_redial_audio_1104_ctrl", "1025", "1455", "58", "2524", "2603", "054", "bsp_system_message_enabled", "web_pip_redesign", "051", "verify_apps", "1974", "1272", "1322", "1755", "052", "70", "050", "1063", "1135", "1361", "80", "1096", "1828", "1851", "1251", "1921", "key_config_id", "1254", "1566", "1252", "2525", "critical_block", "1669", "max_available", "w:auth:backup:token", "product", "2530", "870", "1022", "participant_uuid", "web_cart_on_off", "1255", "1432", "1867", "41", "1415", "1440", "240", "1204", "1608", "1690", "1846", "1483", "1687", "1749", "69", "url_number", "053", "1325", "1040", "365", "59", "Asia/Riyadh", "1177", "test_recommended", "057", "1612", "43", "1061", "1518", "1635", "055", "1034", "1375", "750", "1430", "event_code", "1682", "503", "55", "865", "78", "1309", "1365", "44", "America/Guayaquil", "535", "LIMITED", "1377", "1613", "1420", "1599", "1822", "05a", "1681", "password", "1111", "1214", "1376", "1478", "47", "1082", "4282", "Europe/Istanbul", "1307", "46", "058", "1124", "256", "rate-overlimit", "retail", "u_a_socket_err_fix_succ_test", "1292", "1370", "1388", "520", "861", "psa", "regular", "1181", "1766", "05b", "1183", "1213", "1304", "1537"),
            arrayOf("1724", "profile_picture", "1071", "1314", "1605", "407", "990", "1710", "746", "pricing_model", "056", "059", "061", "1119", "6027", "65", "877", "1607", "05d", "917", "seen", "1516", "49", "470", "973", "1037", "1350", "1394", "1480", "1796", "keys", "794", "1536", "1594", "2378", "1333", "1524", "1825", "116", "309", "52", "808", "827", "909", "495", "1660", "361", "957", "google", "1357", "1565", "1967", "996", "1775", "586", "736", "1052", "1670", "bank", "177", "1416", "2194", "2222", "1454", "1839", "1275", "53", "997", "1629", "6028", "smba", "1378", "1410", "05c", "1849", "727", "create", "1559", "536", "1106", "1310", "1944", "670", "1297", "1316", "1762", "en", "1148", "1295", "1551", "1853", "1890", "1208", "1784", "7200", "05f", "178", "1283", "1332", "381", "643", "1056", "1238", "2024", "2387", "179", "981", "1547", "1705", "05e", "290", "903", "1069", "1285", "2436", "062", "251", "560", "582", "719", "56", "1700", "2321", "325", "448", "613", "777", "791", "51", "488", "902", "Asia/Almaty", "is_hidden", "1398", "1527", "1893", "1999", "2367", "2642", "237", "busy", "065", "067", "233", "590", "993", "1511", "54", "723", "860", "363", "487", "522", "605", "995", "1321", "1691", "1865", "2447", "2462", "NON_TRANSACTIONAL", "433", "871", "432", "1004", "1207", "2032", "2050", "2379", "2446", "279", "636", "703", "904", "248", "370", "691", "700", "1068", "1655", "2334", "060", "063", "364", "533", "534", "567", "1191", "1210", "1473", "1827", "069", "701", "2531", "514", "prev_dhash", "064", "496", "790", "1046", "1139", "1505", "1521", "1108", "207", "544", "637", "final", "1173", "1293", "1694", "1939", "1951", "1993", "2353", "2515", "504", "601", "857", "modify", "spam_request", "p_121_aa_1101_test4", "866", "1427", "1502", "1638", "1744", "2153", "068", "382", "725", "1704", "1864", "1990", "2003", "Asia/Dubai", "508", "531", "1387", "1474", "1632", "2307", "2386", "819", "2014", "066", "387", "1468", "1706", "2186", "2261", "471", "728", "1147", "1372", "1961")
        )

        private val doubleByteIndex: Map<String, Pair<Byte, Byte>> by lazy {
            val map = HashMap<String, Pair<Byte, Byte>>()
            for (dictIdx in doubleByteTokens.indices) {
                for (tokenIdx in doubleByteTokens[dictIdx].indices) {
                    val token = doubleByteTokens[dictIdx][tokenIdx]
                    if (token.isNotEmpty()) {
                        map[token] = Pair(dictIdx.toByte(), tokenIdx.toByte())
                    }
                }
            }
            map
        }

        const val INTEROP_JID: Int = 245
        const val FB_JID: Int = 246
        const val AD_JID: Int = 247

        val singleByteTokens = arrayOf("", "xmlstreamstart", "xmlstreamend", "s.whatsapp.net", "type", "participant", "from", "receipt", "id", "notification", "disappearing_mode", "status", "jid", "broadcast", "user", "devices", "device_hash", "to", "offline", "message", "result", "class", "xmlns", "duration", "notify", "iq", "t", "ack", "g.us", "enc", "urn:xmpp:whatsapp:push", "presence", "config_value", "picture", "verified_name", "config_code", "key-index-list", "contact", "mediatype", "routing_info", "edge_routing", "get", "read", "urn:xmpp:ping", "fallback_hostname", "0", "chatstate", "business_hours_config", "unavailable", "download_buckets", "skmsg", "verified_level", "composing", "handshake", "device-list", "media", "text", "fallback_ip4", "media_conn", "device", "creation", "location", "config", "item", "fallback_ip6", "count", "w:profile:picture", "image", "business", "2", "hostname", "call-creator", "display_name", "relaylatency", "platform", "abprops", "success", "msg", "offline_preview", "prop", "key-index", "v", "day_of_week", "pkmsg", "version", "1", "ping", "w:p", "download", "video", "set", "specific_hours", "props", "primary", "unknown", "hash", "commerce_experience", "last", "subscribe", "max_buckets", "call", "profile", "member_since_text", "close_time", "call-id", "sticker", "mode", "participants", "value", "query", "profile_options", "open_time", "code", "list", "host", "ts", "contacts", "upload", "lid", "preview", "update", "usync", "w:stats", "delivery", "auth_ttl", "context", "fail", "cart_enabled", "appdata", "category", "atn", "direct_connection", "decrypt-fail", "relay_id", "mmg-fallback.whatsapp.net", "target", "available", "name", "last_id", "mmg.whatsapp.net", "categories", "401", "is_new", "index", "tctoken", "ip4", "token_id", "latency", "recipient", "edit", "ip6", "add", "thumbnail-document", "26", "paused", "true", "identity", "stream:error", "key", "sidelist", "background", "audio", "3", "thumbnail-image", "biz-cover-photo", "cat", "gcm", "thumbnail-video", "error", "auth", "deny", "serial", "in", "registration", "thumbnail-link", "remove", "00", "gif", "thumbnail-gif", "tag", "capability", "multicast", "item-not-found", "description", "business_hours", "config_expo_key", "md-app-state", "expiration", "fallback", "ttl", "300", "md-msg-hist", "device_orientation", "out", "w:m", "open_24h", "side_list", "token", "inactive", "01", "document", "te2", "played", "encrypt", "msgr", "hide", "direct_path", "12", "state", "not-authorized", "url", "terminate", "signature", "status-revoke-delay", "02", "te", "linked_accounts", "trusted_contact", "timezone", "ptt", "kyc-id", "privacy_token", "readreceipts", "appointment_only", "address", "expected_ts", "privacy", "7", "android", "interactive", "device-identity", "enabled", "attribute_padding", "1080", "03", "screen_height")

        private val singleByteIndex: Map<String, Byte> by lazy {
            val map = HashMap<String, Byte>(singleByteTokens.size)
            for (i in singleByteTokens.indices) {
                if (singleByteTokens[i].isNotEmpty()) {
                    map[singleByteTokens[i]] = i.toByte()
                }
            }
            map
        }

        fun indexOfSingleToken(token: String): Int {
            return singleByteIndex[token]?.toInt()?.and(0xFF) ?: -1
        }

        fun indexOfDoubleByteToken(token: String): Triple<Int, Int, Boolean> {
            val pair = doubleByteIndex[token] ?: return Triple(0, 0, false)
            return Triple(pair.first.toInt() and 0xFF, pair.second.toInt() and 0xFF, true)
        }

        fun getDoubleToken(dictIndex: Int, tokenIndex: Int): String {
            if (dictIndex < 0 || dictIndex >= doubleByteTokens.size) return ""
            if (tokenIndex < 0 || tokenIndex >= doubleByteTokens[dictIndex].size) return ""
            return doubleByteTokens[dictIndex][tokenIndex]
        }
    }

    private class BinaryEncoder {
        private val data = mutableListOf<Byte>(0)

        fun getData(): ByteArray = data.toByteArray()

        private fun pushByte(b: Byte) { data.add(b) }
        private fun pushByte(b: Int) { data.add(b.toByte()) }
        private fun pushBytes(bytes: ByteArray) { bytes.forEach { data.add(it) } }

        private fun pushInt8(value: Int) { pushByte((value and 0xFF).toByte()) }
        private fun pushInt16(value: Int) {
            pushByte((value shr 8 and 0xFF).toByte())
            pushByte((value and 0xFF).toByte())
        }
        private fun pushInt20(value: Int) {
            pushByte(((value shr 16) and 0x0F).toByte())
            pushByte(((value shr 8) and 0xFF).toByte())
            pushByte((value and 0xFF).toByte())
        }
        private fun pushInt32(value: Int) {
            pushByte((value shr 24 and 0xFF).toByte())
            pushByte((value shr 16 and 0xFF).toByte())
            pushByte((value shr 8 and 0xFF).toByte())
            pushByte((value and 0xFF).toByte())
        }

        private fun writeByteLength(length: Int) {
            when {
                length < 256 -> { pushByte(BinaryToken.BINARY_8); pushInt8(length) }
                length < (1 shl 20) -> { pushByte(BinaryToken.BINARY_20); pushInt20(length) }
                else -> { pushByte(BinaryToken.BINARY_32); pushInt32(length) }
            }
        }

        fun writeNode(n: Node) {
            if (n.tag == "0") {
                pushByte(BinaryToken.LIST_8)
                pushByte(BinaryToken.LIST_EMPTY)
                return
            }

            val hasContent = if (n.data != null || n.content.isNotEmpty()) 1 else 0
            val attrCount = n.attrs.count { it.value.isNotEmpty() }
            writeListStart(2 * attrCount + 1 + hasContent)
            writeString(n.tag)
            writeAttributes(n.attrs)
            if (n.data != null) {
                writeBytes(n.data)
            } else if (n.content.isNotEmpty()) {
                writeListStart(n.content.size)
                for (child in n.content) {
                    writeNode(child)
                }
            }
        }

        private fun writeString(value: String) {
            val tokenIndex = BinaryToken.indexOfSingleToken(value)
            if (tokenIndex >= 0) {
                pushByte(tokenIndex)
            } else {
                val (dictIndex, tokIndex, found) = BinaryToken.indexOfDoubleByteToken(value)
                if (found) {
                    pushByte(BinaryToken.DICTIONARY_0 + dictIndex)
                    pushByte(tokIndex)
                } else if (validateNibble(value)) {
                    writePackedBytes(value, BinaryToken.NIBBLE_8)
                } else if (validateHex(value)) {
                    writePackedBytes(value, BinaryToken.HEX_8)
                } else {
                    writeStringRaw(value)
                }
            }
        }

        private fun writeBytes(value: ByteArray) {
            writeByteLength(value.size)
            pushBytes(value)
        }

        private fun writeStringRaw(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeByteLength(bytes.size)
            pushBytes(bytes)
        }

        private fun writeAttributes(attrs: Map<String, String>) {
            for ((key, value) in attrs) {
                if (value.isEmpty()) continue
                writeString(key)
                writeString(value)
            }
        }

        private fun writeListStart(size: Int) {
            when {
                size == 0 -> pushByte(BinaryToken.LIST_EMPTY)
                size < 256 -> { pushByte(BinaryToken.LIST_8); pushInt8(size) }
                else -> { pushByte(BinaryToken.LIST_16); pushInt16(size) }
            }
        }

        private fun validateNibble(value: String): Boolean {
            if (value.length > BinaryToken.PACKED_MAX) return false
            return value.all { it in '0'..'9' || it == '-' || it == '.' }
        }

        private fun validateHex(value: String): Boolean {
            if (value.length > BinaryToken.PACKED_MAX) return false
            return value.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
        }

        private fun writePackedBytes(value: String, dataType: Int) {
            pushByte(dataType)
            val roundedLength = ((value.length + 1) / 2)
            val flag = if (value.length % 2 != 0) (roundedLength or 128) else roundedLength
            pushByte(flag)
            val packer = if (dataType == BinaryToken.NIBBLE_8) ::packNibble else ::packHex
            var i = 0
            while (i < value.length / 2) {
                pushByte(((packer(value[2 * i]) shl 4) or packer(value[2 * i + 1])).toByte())
                i++
            }
            if (value.length % 2 != 0) {
                pushByte(((packer(value.last()) shl 4) or packer('\u0000')).toByte())
            }
        }

        private fun packNibble(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            '-' -> 10
            '.' -> 11
            '\u0000' -> 15
            else -> throw IllegalArgumentException("Invalid nibble char: $c")
        }

        private fun packHex(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> 10 + (c - 'A')
            in 'a'..'f' -> 10 + (c - 'a')
            '\u0000' -> 15
            else -> throw IllegalArgumentException("Invalid hex char: $c")
        }
    }

    private class BinaryDecoder(private val data: ByteArray) {
        private var index = 0

        private fun checkEOS(length: Int) {
            if (index + length > data.size) throw IllegalStateException("End of stream")
        }

        private fun readByte(): Int {
            checkEOS(1)
            return data[index++].toInt() and 0xFF
        }

        private fun readInt8(): Int = readByte()
        private fun readInt16(): Int {
            checkEOS(2)
            val v = ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
            return v
        }
        private fun readInt20(): Int {
            checkEOS(3)
            val v = ((data[index].toInt() and 0x0F) shl 16) or
                    ((data[index + 1].toInt() and 0xFF) shl 8) or
                    (data[index + 2].toInt() and 0xFF)
            index += 3
            return v
        }
        private fun readInt32(): Int {
            checkEOS(4)
            val v = ((data[index].toInt() and 0xFF) shl 24) or
                    ((data[index + 1].toInt() and 0xFF) shl 16) or
                    ((data[index + 2].toInt() and 0xFF) shl 8) or
                    (data[index + 3].toInt() and 0xFF)
            index += 4
            return v
        }

        private fun readRaw(length: Int): ByteArray {
            checkEOS(length)
            val result = data.copyOfRange(index, index + length)
            index += length
            return result
        }

        private fun readPacked8(tag: Int): String {
            val startByte = readByte()
            val sb = StringBuilder()
            for (i in 0 until (startByte and 127)) {
                val currByte = readByte()
                sb.append(unpackByte(tag, (currByte shr 4) and 0x0F))
                sb.append(unpackByte(tag, currByte and 0x0F))
            }
            var result = sb.toString()
            if ((startByte shr 7) != 0) result = result.dropLast(1)
            return result
        }

        private fun unpackByte(tag: Int, value: Int): Char = when (tag) {
            BinaryToken.NIBBLE_8 -> unpackNibble(value)
            BinaryToken.HEX_8 -> unpackHex(value)
            else -> throw IllegalArgumentException("Unknown packed tag: $tag")
        }
        private fun unpackNibble(value: Int): Char = when {
            value < 10 -> ('0' + value)
            value == 10 -> '-'
            value == 11 -> '.'
            value == 15 -> '\u0000'
            else -> throw IllegalArgumentException("Invalid nibble: $value")
        }
        private fun unpackHex(value: Int): Char = when {
            value < 10 -> ('0' + value)
            value < 16 -> ('A' + value - 10)
            else -> throw IllegalArgumentException("Invalid hex: $value")
        }

        private fun readListSize(tag: Int): Int = when (tag) {
            BinaryToken.LIST_EMPTY.toInt() and 0xFF -> 0
            BinaryToken.LIST_8.toInt() and 0xFF -> readInt8()
            BinaryToken.LIST_16.toInt() and 0xFF -> readInt16()
            else -> throw IllegalArgumentException("Unknown list tag: $tag")
        }

        private fun read(asString: Boolean): Any? {
            val tag = readByte()
            return when (tag) {
                BinaryToken.LIST_EMPTY.toInt() and 0xFF -> null
                BinaryToken.LIST_8.toInt() and 0xFF,
                BinaryToken.LIST_16.toInt() and 0xFF -> readList(tag)
                BinaryToken.BINARY_8.toInt() and 0xFF -> {
                    val size = readInt8()
                    if (asString) String(readRaw(size), Charsets.UTF_8) else readRaw(size)
                }
                BinaryToken.BINARY_20.toInt() and 0xFF -> {
                    val size = readInt20()
                    if (asString) String(readRaw(size), Charsets.UTF_8) else readRaw(size)
                }
                BinaryToken.BINARY_32.toInt() and 0xFF -> {
                    val size = readInt32()
                    if (asString) String(readRaw(size), Charsets.UTF_8) else readRaw(size)
                }
                in BinaryToken.DICTIONARY_0..BinaryToken.DICTIONARY_3 -> {
                    val idx = readInt8()
                    BinaryToken.getDoubleToken(tag - BinaryToken.DICTIONARY_0, idx)
                }
                BinaryToken.AD_JID -> {
                    val agent = readByte()
                    val device = readByte()
                    val user = read(true) as? String ?: ""
                    "$user.${agent}:${device}@s.whatsapp.net"
                }
                BinaryToken.FB_JID -> {
                    val user = read(true) as? String ?: ""
                    val device = readInt16()
                    val server = read(true) as? String ?: "msgr"
                    "$user:$device@$server"
                }
                BinaryToken.INTEROP_JID -> {
                    val user = read(true) as? String ?: ""
                    val device = readInt16()
                    val integrator = readInt16()
                    val server = read(true) as? String ?: ""
                    "$user:$device:$integrator@$server"
                }
                BinaryToken.JID_PAIR.toInt() and 0xFF -> {
                    val user = read(true) as? String
                    val server = read(true) as? String ?: throw IllegalStateException("JID missing server")
                    if (user != null) "$user@$server" else "@$server"
                }
                BinaryToken.NIBBLE_8, BinaryToken.HEX_8 -> readPacked8(tag)
                else -> {
                    if (tag in 1 until BinaryToken.singleByteTokens.size) {
                        BinaryToken.singleByteTokens[tag]
                    } else {
                        throw IllegalArgumentException("Invalid token $tag at position $index")
                    }
                }
            }
        }

        private fun readList(tag: Int): List<Node> {
            val size = readListSize(tag)
            return (0 until size).map { readNode() }
        }

        fun readNode(): Node {
            val listTag = readInt8()
            val listSize = readListSize(listTag)
            val tag = read(true) as? String ?: throw IllegalStateException("Node tag is not a string")
            if (listSize == 0 || tag.isEmpty()) throw IllegalStateException("Invalid node")

            val attrCount = (listSize - 1) shr 1
            val attrs = mutableMapOf<String, String>()
            for (i in 0 until attrCount) {
                val key = read(true) as? String ?: continue
                val value = read(true)
                attrs[key] = value?.toString() ?: ""
            }

            val content = mutableListOf<Node>()
            var nodeData: ByteArray? = null
            if (listSize % 2 == 0) {
                val contentData = read(false)
                when (contentData) {
                    is List<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        content.addAll(contentData as List<Node>)
                    }
                    is ByteArray -> nodeData = contentData
                    is String -> nodeData = contentData.toByteArray(Charsets.UTF_8)
                }
            }

            return Node(tag, attrs, content, nodeData)
        }
    }

    fun encodeNode(node: Node): ByteArray {
        val encoder = BinaryEncoder()
        encoder.writeNode(node)
        return encoder.getData()
    }

    fun decodeNode(data: ByteArray): Node {
        val decoder = BinaryDecoder(data)
        return decoder.readNode()
    }

    // -- Message node builders (from whatsmeow/send.go) --

    /**
     * Build a text message node with E2E encrypted protobuf payload.
     * The enc node contains the Signal-encrypted E2E.Message protobuf.
     */
    fun buildTextMessage(to: String, id: String, text: String): Node {
        val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setConversation(text)
            .build()
        val plaintext = e2eMessage.toByteArray()

        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to to,
                "id" to id,
                "type" to "text"
            ),
            content = listOf(
                Node(
                    tag = "enc",
                    attrs = mapOf("v" to "2", "type" to "msg"),
                    data = padMessage(plaintext)
                )
            )
        )
    }

    /**
     * Build a reaction message node.
     * From whatsmeow/send.go BuildReaction()
     */
    fun buildReactionMessage(
        chatJid: String,
        senderJid: String,
        targetMessageId: String,
        emoji: String,
        ownJid: String,
        id: String,
    ): Node {
        val isFromMe = senderJid.isEmpty() || senderJid == ownJid
        val messageKey = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
            .setFromMe(isFromMe)
            .setId(targetMessageId)
            .setRemoteJid(chatJid)
        if (!isFromMe && chatJid.contains("@g.us")) {
            messageKey.setParticipant(senderJid)
        }

        val reactionMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ReactionMessage.newBuilder()
            .setKey(messageKey.build())
            .setText(emoji)
            .setSenderTimestampMs(System.currentTimeMillis())
            .build()

        val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setReactionMessage(reactionMessage)
            .build()
        val plaintext = e2eMessage.toByteArray()

        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to chatJid,
                "id" to id,
                "type" to "reaction"
            ),
            content = listOf(
                Node(
                    tag = "enc",
                    attrs = mapOf("v" to "2", "type" to "msg", "decrypt-fail" to "hide"),
                    data = padMessage(plaintext)
                )
            )
        )
    }

    /**
     * Build a receipt node with configurable type.
     * Supports: "read", "read-self", "played", "" (delivery), "inactive"
     * From whatsmeow/receipt.go
     */
    fun buildReceipt(
        chatJid: String,
        messageIds: List<String>,
        receiptType: String,
        senderJid: String? = null,
    ): Node {
        if (messageIds.isEmpty()) throw IllegalArgumentException("No message IDs")

        val attrs = mutableMapOf(
            "id" to messageIds.first(),
            "to" to chatJid
        )
        if (receiptType.isNotEmpty()) {
            attrs["type"] = receiptType
        }
        if (senderJid != null && chatJid.contains("@g.us")) {
            attrs["participant"] = senderJid
        }

        val children = mutableListOf<Node>()
        if (messageIds.size > 1) {
            val items = messageIds.drop(1).map { id ->
                Node(tag = "item", attrs = mapOf("id" to id))
            }
            children.add(Node(tag = "list", content = items))
        }

        return Node(tag = "receipt", attrs = attrs, content = children)
    }

    /**
     * Build a media message node.
     * From whatsmeow/send.go + upload.go
     */
    fun buildMediaMessage(
        to: String,
        id: String,
        url: String,
        directPath: String,
        mediaKey: ByteArray,
        fileSha256: ByteArray,
        fileEncSha256: ByteArray,
        fileLength: Long,
        mimeType: String,
        caption: String?,
        mediaType: String, // "image", "video", "audio", "document"
    ): Node {
        val e2eBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()

        when (mediaType) {
            "image" -> {
                val imgBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ImageMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                if (caption != null) imgBuilder.setCaption(caption)
                e2eBuilder.setImageMessage(imgBuilder.build())
            }
            "video" -> {
                val vidBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.VideoMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                if (caption != null) vidBuilder.setCaption(caption)
                e2eBuilder.setVideoMessage(vidBuilder.build())
            }
            "audio" -> {
                val audBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.AudioMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                e2eBuilder.setAudioMessage(audBuilder.build())
            }
            "document" -> {
                val docBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.DocumentMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                if (caption != null) docBuilder.setTitle(caption)
                e2eBuilder.setDocumentMessage(docBuilder.build())
            }
        }

        val plaintext = e2eBuilder.build().toByteArray()

        val encMediaType = when (mediaType) {
            "image" -> "image"
            "video" -> "video"
            "audio" -> "audio"
            "document" -> "document"
            else -> ""
        }
        val encAttrs = mutableMapOf("v" to "2", "type" to "msg")
        if (encMediaType.isNotEmpty()) encAttrs["mediatype"] = encMediaType

        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to to,
                "id" to id,
                "type" to "media"
            ),
            content = listOf(
                Node(
                    tag = "enc",
                    attrs = encAttrs,
                    data = padMessage(plaintext)
                )
            )
        )
    }

    /**
     * Build a read receipt node.
     * From whatsmeow/receipt.go MarkRead()
     */
    fun buildReadReceipt(
        chatJid: String,
        messageIds: List<String>,
        senderJid: String? = null,
        timestamp: Long = System.currentTimeMillis() / 1000,
    ): Node {
        if (messageIds.isEmpty()) throw IllegalArgumentException("No message IDs")

        val attrs = mutableMapOf(
            "id" to messageIds.first(),
            "type" to "read",
            "to" to chatJid,
            "t" to timestamp.toString()
        )
        if (senderJid != null && chatJid.contains("@g.us")) {
            attrs["participant"] = senderJid
        }

        val children = mutableListOf<Node>()
        if (messageIds.size > 1) {
            val items = messageIds.drop(1).map { id ->
                Node(tag = "item", attrs = mapOf("id" to id))
            }
            children.add(Node(tag = "list", content = items))
        }

        return Node(tag = "receipt", attrs = attrs, content = children)
    }

    /**
     * Build an edit message node.
     * From whatsmeow/send.go BuildEdit()
     * Go wraps in EditedMessage -> FutureProofMessage -> Message -> ProtocolMessage
     */
    fun buildEditMessage(
        chatJid: String,
        targetMessageId: String,
        newText: String,
        ownJid: String,
        id: String,
    ): Node {
        val messageKey = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
            .setFromMe(true)
            .setId(targetMessageId)
            .setRemoteJid(chatJid)
            .build()

        val newContent = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setConversation(newText)
            .build()

        val protocolMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
            .setType(com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT)
            .setKey(messageKey)
            .setEditedMessage(newContent)
            .setTimestampMs(System.currentTimeMillis())
            .build()

        val innerMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(protocolMessage)
            .build()

        val futureProof = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.FutureProofMessage.newBuilder()
            .setMessage(innerMessage)
            .build()

        val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setEditedMessage(futureProof)
            .build()
        val plaintext = e2eMessage.toByteArray()

        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to chatJid,
                "id" to id,
                "type" to "text",
                "edit" to "1"
            ),
            content = listOf(
                Node(
                    tag = "enc",
                    attrs = mapOf("v" to "2", "type" to "msg", "decrypt-fail" to "hide"),
                    data = padMessage(plaintext)
                )
            )
        )
    }

    /**
     * Build a revoke (delete) message node.
     * From whatsmeow/send.go BuildRevoke()
     */
    fun buildRevokeMessage(
        chatJid: String,
        senderJid: String,
        targetMessageId: String,
        ownJid: String,
        id: String,
    ): Node {
        val isFromMe = senderJid.isEmpty() || senderJid == ownJid ||
            senderJid.substringBefore("@") == ownJid.substringBefore("@")
        val messageKey = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
            .setFromMe(isFromMe)
            .setId(targetMessageId)
            .setRemoteJid(chatJid)
        if (!isFromMe && chatJid.contains("@g.us")) {
            messageKey.setParticipant(senderJid)
        }

        val protocolMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
            .setType(com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.REVOKE)
            .setKey(messageKey.build())
            .build()

        val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(protocolMessage)
            .build()
        val plaintext = e2eMessage.toByteArray()

        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to chatJid,
                "id" to id,
                "type" to "text",
                "edit" to if (isFromMe) "7" else "8"
            ),
            content = listOf(
                Node(
                    tag = "enc",
                    attrs = mapOf("v" to "2", "type" to "msg", "decrypt-fail" to "hide"),
                    data = padMessage(plaintext)
                )
            )
        )
    }

    /**
     * Build a chat presence (typing indicator) node.
     * From whatsmeow/send.go SendChatPresence()
     */
    fun buildChatPresence(
        chatJid: String,
        isComposing: Boolean,
        isAudio: Boolean = false,
        ownJid: String = "",
    ): Node {
        val state = if (isComposing) "composing" else "paused"
        val childAttrs = if (isComposing && isAudio) mapOf("media" to "audio") else emptyMap()
        val attrs = mutableMapOf("to" to chatJid)
        if (ownJid.isNotEmpty()) attrs["from"] = ownJid
        return Node(
            tag = "chatstate",
            attrs = attrs,
            content = listOf(
                Node(tag = state, attrs = childAttrs)
            )
        )
    }

    /**
     * Build a keepalive (ping) IQ node.
     * From whatsmeow/keepalive.go
     */
    fun buildKeepalive(id: String): Node {
        return Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "xmlns" to "w:p",
                "type" to "get",
                "to" to "s.whatsapp.net"
            )
        )
    }

    /**
     * Build an ack node for acknowledging received messages.
     * From whatsmeow/receipt.go sendAck()
     */
    fun buildAck(
        nodeClass: String,
        nodeId: String,
        from: String,
        participant: String? = null,
        recipient: String? = null,
        type: String? = null,
    ): Node {
        val attrs = mutableMapOf(
            "class" to nodeClass,
            "id" to nodeId,
            "to" to from
        )
        if (participant != null) attrs["participant"] = participant
        if (recipient != null) attrs["recipient"] = recipient
        if (type != null && nodeClass != "message") attrs["type"] = type
        return Node(tag = "ack", attrs = attrs)
    }

    // -- Frame helpers --

    /**
     * Build a framed message with optional header and 3-byte big-endian length prefix.
     * From whatsmeow/socket/framesocket.go SendFrame()
     */
    fun buildFramedMessage(data: ByteArray, header: ByteArray?): ByteArray {
        val headerLength = header?.size ?: 0
        val dataLength = data.size
        if (dataLength >= FRAME_MAX_SIZE) {
            throw IllegalArgumentException("Frame too large: $dataLength bytes (max $FRAME_MAX_SIZE)")
        }
        val frame = ByteArray(headerLength + FRAME_LENGTH_SIZE + dataLength)

        var offset = 0
        if (header != null) {
            System.arraycopy(header, 0, frame, offset, headerLength)
            offset += headerLength
        }
        frame[offset] = (dataLength shr 16).toByte()
        frame[offset + 1] = (dataLength shr 8).toByte()
        frame[offset + 2] = dataLength.toByte()
        offset += FRAME_LENGTH_SIZE
        System.arraycopy(data, 0, frame, offset, dataLength)
        return frame
    }

    /**
     * Extract frame payload from raw data (strip 3-byte length prefix).
     * From whatsmeow/socket/framesocket.go processData()
     */
    fun extractFrame(data: ByteArray): ByteArray {
        if (data.size < FRAME_LENGTH_SIZE) return data
        val length = ((data[0].toInt() and 0xFF) shl 16) or
                ((data[1].toInt() and 0xFF) shl 8) or
                (data[2].toInt() and 0xFF)
        if (data.size < FRAME_LENGTH_SIZE + length) return data
        return data.copyOfRange(FRAME_LENGTH_SIZE, FRAME_LENGTH_SIZE + length)
    }

    // -- Message parsing --

    private fun formatDisappearingTimer(seconds: Int): String {
        return when {
            seconds >= 86400 * 90 -> "90 days"
            seconds >= 86400 * 7 -> "7 days"
            seconds >= 86400 -> "${seconds / 86400} days"
            seconds >= 3600 -> "${seconds / 3600} hours"
            seconds >= 60 -> "${seconds / 60} minutes"
            else -> "$seconds seconds"
        }
    }

    private fun extractContextInfo(
        e2eMessage: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message
    ): ContextInfoResult {
        val ctx = when {
            e2eMessage.hasExtendedTextMessage() -> e2eMessage.extendedTextMessage.contextInfo
            e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.contextInfo
            e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.contextInfo
            e2eMessage.hasPtvMessage() -> e2eMessage.ptvMessage.contextInfo
            e2eMessage.hasAudioMessage() -> e2eMessage.audioMessage.contextInfo
            e2eMessage.hasDocumentMessage() -> e2eMessage.documentMessage.contextInfo
            e2eMessage.hasStickerMessage() -> e2eMessage.stickerMessage.contextInfo
            e2eMessage.hasLocationMessage() -> e2eMessage.locationMessage.contextInfo
            e2eMessage.hasContactMessage() -> e2eMessage.contactMessage.contextInfo
            e2eMessage.hasLiveLocationMessage() -> e2eMessage.liveLocationMessage.contextInfo
            else -> null
        } ?: return ContextInfoResult()

        return ContextInfoResult(
            isForwarded = ctx.isForwarded,
            forwardingScore = ctx.forwardingScore,
            replyToId = ctx.stanzaId.ifEmpty { null },
            mentionedJids = ctx.mentionedJidList.orEmpty(),
        )
    }

    /**
     * Build a poll creation message.
     * From whatsmeow matrixpoll.go PollStartToWhatsApp()
     */
    fun buildPollCreationMessage(
        chatJid: String,
        question: String,
        options: List<String>,
        selectableCount: Int = 0,
        id: String,
    ): Node {
        val proto = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto

        val pollOptions = options.map { option ->
            proto.PollCreationMessage.Option.newBuilder()
                .setOptionName(option)
                .build()
        }

        val messageSecret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        val pollCreation = proto.PollCreationMessage.newBuilder()
            .setName(question)
            .addAllOptions(pollOptions)
            .setSelectableOptionsCount(selectableCount)
            .build()

        val e2eMessage = proto.Message.newBuilder()
            .setPollCreationMessage(pollCreation)
            .setMessageContextInfo(
                proto.MessageContextInfo.newBuilder()
                    .setMessageSecret(com.google.protobuf.ByteString.copyFrom(messageSecret))
            )
            .build()

        val plaintext = e2eMessage.toByteArray()
        return Node(
            tag = "message",
            attrs = mapOf("to" to chatJid, "id" to id, "type" to "text"),
            content = listOf(
                Node(tag = "enc", attrs = mapOf("v" to "2", "type" to "msg"), data = padMessage(plaintext))
            )
        )
    }

    /**
     * Build a location message.
     * From whatsmeow from-matrix.go location handling via parseGeoURI()
     */
    fun buildLocationMessage(
        chatJid: String,
        latitude: Double,
        longitude: Double,
        name: String? = null,
        address: String? = null,
        id: String,
    ): Node {
        val proto = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto

        val locBuilder = proto.LocationMessage.newBuilder()
            .setDegreesLatitude(latitude)
            .setDegreesLongitude(longitude)
        if (!name.isNullOrEmpty()) locBuilder.setName(name)
        if (!address.isNullOrEmpty()) locBuilder.setAddress(address)

        val e2eMessage = proto.Message.newBuilder()
            .setLocationMessage(locBuilder.build())
            .build()

        val plaintext = e2eMessage.toByteArray()
        return Node(
            tag = "message",
            attrs = mapOf("to" to chatJid, "id" to id, "type" to "text"),
            content = listOf(
                Node(tag = "enc", attrs = mapOf("v" to "2", "type" to "msg"), data = padMessage(plaintext))
            )
        )
    }

    /**
     * Build a contact/vCard message.
     * From whatsmeow wa-contact.go
     */
    fun buildContactMessage(
        chatJid: String,
        displayName: String,
        vcard: String,
        id: String,
    ): Node {
        val proto = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto

        val contactMsg = proto.ContactMessage.newBuilder()
            .setDisplayName(displayName)
            .setVcard(vcard)
            .build()

        val e2eMessage = proto.Message.newBuilder()
            .setContactMessage(contactMsg)
            .build()

        val plaintext = e2eMessage.toByteArray()
        return Node(
            tag = "message",
            attrs = mapOf("to" to chatJid, "id" to id, "type" to "text"),
            content = listOf(
                Node(tag = "enc", attrs = mapOf("v" to "2", "type" to "msg"), data = padMessage(plaintext))
            )
        )
    }

    /**
     * Build a disappearing timer change message.
     * From whatsmeow handlematrix.go HandleMatrixDisappearingTimer()
     * Allowed values: 0 (off), 86400 (24h), 604800 (7d), 7776000 (90d)
     */
    fun buildDisappearingTimerMessage(
        chatJid: String,
        timerSeconds: Long,
        id: String,
    ): Node {
        val proto = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto

        val protocolMsg = proto.ProtocolMessage.newBuilder()
            .setType(proto.ProtocolMessage.Type.EPHEMERAL_SETTING)
            .setEphemeralExpiration(timerSeconds.toInt())
            .build()

        val e2eMessage = proto.Message.newBuilder()
            .setProtocolMessage(protocolMsg)
            .build()

        val plaintext = e2eMessage.toByteArray()
        return Node(
            tag = "message",
            attrs = mapOf("to" to chatJid, "id" to id, "type" to "text"),
            content = listOf(
                Node(tag = "enc", attrs = mapOf("v" to "2", "type" to "msg"), data = padMessage(plaintext))
            )
        )
    }

    /**
     * Build a group info change IQ node.
     * From whatsmeow group.go SetGroupName/SetGroupTopic
     */
    fun buildGroupInfoChange(
        groupJid: String,
        field: String,
        value: String,
        id: String,
        extraAttrs: Map<String, String> = emptyMap(),
    ): Node {
        val childAttrs = mutableMapOf<String, String>()
        childAttrs.putAll(extraAttrs)
        return Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "type" to "set",
                "xmlns" to "w:g2",
                "to" to groupJid,
            ),
            content = listOf(
                Node(tag = field, attrs = childAttrs, content = listOf(), data = value.toByteArray(Charsets.UTF_8))
            ),
        )
    }

    /**
     * Build a group participant change IQ node.
     * From whatsmeow group.go UpdateGroupParticipants
     */
    fun buildGroupParticipantChange(
        groupJid: String,
        participantJids: List<String>,
        action: String,
        id: String,
    ): Node {
        val participants = participantJids.map { jid ->
            Node(tag = "participant", attrs = mapOf("jid" to jid))
        }
        return Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "type" to "set",
                "xmlns" to "w:g2",
                "to" to groupJid,
            ),
            content = listOf(
                Node(tag = action, content = participants)
            ),
        )
    }

    /**
     * Reroute LID JIDs to phone number JIDs.
     * From whatsmeow handlewhatsapp.go rerouteWAMessage()
     */
    fun rerouteLIDSender(senderJid: String, participants: Map<String, String>?): String {
        if (!senderJid.contains("@lid")) return senderJid
        val phoneJid = participants?.get(senderJid)
        return phoneJid ?: senderJid
    }

    fun getMessageType(e2eMessage: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message?): String {
        val proto = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto
        return when {
            e2eMessage == null -> "ignore"
            e2eMessage.hasConversation() || e2eMessage.hasExtendedTextMessage() -> "text"
            e2eMessage.hasImageMessage() -> "image ${e2eMessage.imageMessage.mimetype}"
            e2eMessage.hasStickerMessage() -> "sticker ${e2eMessage.stickerMessage.mimetype}"
            e2eMessage.hasVideoMessage() -> "video ${e2eMessage.videoMessage.mimetype}"
            e2eMessage.hasPtvMessage() -> "round video ${e2eMessage.ptvMessage.mimetype}"
            e2eMessage.hasAudioMessage() -> "audio ${e2eMessage.audioMessage.mimetype}"
            e2eMessage.hasDocumentMessage() -> "document ${e2eMessage.documentMessage.mimetype}"
            e2eMessage.hasContactMessage() -> "contact"
            e2eMessage.hasContactsArrayMessage() -> "contact array"
            e2eMessage.hasLocationMessage() -> "location"
            e2eMessage.hasLiveLocationMessage() -> "live location start"
            e2eMessage.hasGroupInviteMessage() -> "group invite"
            e2eMessage.hasGroupMentionedMessage() -> "group mention"
            e2eMessage.hasReactionMessage() -> {
                if (e2eMessage.reactionMessage.text.isNullOrEmpty()) "reaction remove" else "reaction"
            }
            e2eMessage.hasEncReactionMessage() -> "enc reaction"
            e2eMessage.hasEncCommentMessage() -> "enc comment"
            e2eMessage.hasCommentMessage() -> "comment"
            e2eMessage.hasPollCreationMessage() || e2eMessage.hasPollCreationMessageV2() || e2eMessage.hasPollCreationMessageV3() -> "poll create"
            e2eMessage.hasPollUpdateMessage() -> "poll update"
            e2eMessage.hasEventMessage() -> "event"
            e2eMessage.hasEventCoverImage() -> "event cover image"
            e2eMessage.hasProtocolMessage() -> {
                when (e2eMessage.protocolMessage.type) {
                    proto.ProtocolMessage.Type.REVOKE -> {
                        if (e2eMessage.protocolMessage.hasKey()) "revoke" else "ignore"
                    }
                    proto.ProtocolMessage.Type.MESSAGE_EDIT -> "edit"
                    proto.ProtocolMessage.Type.EPHEMERAL_SETTING -> "disappearing timer change"
                    proto.ProtocolMessage.Type.APP_STATE_SYNC_KEY_SHARE,
                    proto.ProtocolMessage.Type.HISTORY_SYNC_NOTIFICATION,
                    proto.ProtocolMessage.Type.INITIAL_SECURITY_NOTIFICATION_SETTING_SYNC,
                    proto.ProtocolMessage.Type.APP_STATE_FATAL_EXCEPTION_NOTIFICATION,
                    proto.ProtocolMessage.Type.SHARE_PHONE_NUMBER,
                    proto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE,
                    proto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE -> "ignore"
                    else -> "unknown_protocol_${e2eMessage.protocolMessage.type.number}"
                }
            }
            e2eMessage.hasOrderMessage() -> "order"
            e2eMessage.hasProductMessage() -> "product"
            e2eMessage.hasPaymentInviteMessage() -> "payment"
            e2eMessage.hasListMessage() -> "list"
            e2eMessage.hasListResponseMessage() -> "list response"
            e2eMessage.hasButtonsMessage() -> "buttons"
            e2eMessage.hasButtonsResponseMessage() -> "buttons response"
            e2eMessage.hasTemplateMessage() -> "template"
            e2eMessage.hasTemplateButtonReplyMessage() -> "template button reply"
            e2eMessage.hasHighlyStructuredMessage() -> "highly structured"
            e2eMessage.hasInteractiveMessage() -> "interactive"
            e2eMessage.hasInteractiveResponseMessage() -> "interactive response"
            e2eMessage.hasKeepInChatMessage() -> "keep in chat"
            e2eMessage.hasPinInChatMessage() -> "pin in chat"
            e2eMessage.hasNewsletterAdminInviteMessage() -> "newsletter admin invite"
            e2eMessage.hasSecretEncryptedMessage() -> "secret encrypted"
            e2eMessage.hasBotInvokeMessage() -> "bot invoke"
            e2eMessage.hasStickerPackMessage() -> "sticker pack"
            e2eMessage.hasSenderKeyDistributionMessage() -> "sender key distribution"
            e2eMessage.hasMessageHistoryBundle() -> "message history bundle"
            else -> "unknown"
        }
    }

    fun parseMessage(node: Node): WhatsAppMessage? {
        if (node.tag != "message") return null

        val from = node.attrs["from"] ?: return null
        val id = node.attrs["id"] ?: return null
        val type = node.attrs["type"] ?: "text"
        val timestamp = node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000
        val participant = node.attrs["participant"]

        val encNode = node.getChildByTag("enc")
        if (encNode?.data != null) {
            return try {
                val plaintext = unpadMessage(encNode.data)
                val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.parseFrom(plaintext)

                val parsedType = getMessageType(e2eMessage)
                if (parsedType == "ignore" || parsedType.startsWith("unknown_protocol_")) {
                    return null
                }

                val body = when {
                    e2eMessage.hasConversation() -> e2eMessage.conversation
                    e2eMessage.hasExtendedTextMessage() -> e2eMessage.extendedTextMessage.text
                    e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.caption.ifEmpty { "[Image]" }
                    e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.caption.ifEmpty { "[Video]" }
                    e2eMessage.hasPtvMessage() -> e2eMessage.ptvMessage.caption.ifEmpty { "[Round Video]" }
                    e2eMessage.hasAudioMessage() -> "[Audio]"
                    e2eMessage.hasDocumentMessage() -> "[Document: ${e2eMessage.documentMessage.title}]"
                    e2eMessage.hasStickerMessage() -> "[Sticker]"
                    e2eMessage.hasContactMessage() -> {
                        val c = e2eMessage.contactMessage
                        "[Contact: ${c.displayName}]\n${c.vcard}"
                    }
                    e2eMessage.hasContactsArrayMessage() -> {
                        val contacts = e2eMessage.contactsArrayMessage
                        "[${contacts.contactsCount} Contacts: ${contacts.contactsList.joinToString(", ") { it.displayName }}]"
                    }
                    e2eMessage.hasLocationMessage() -> {
                        val loc = e2eMessage.locationMessage
                        val lat = loc.degreesLatitude
                        val lng = loc.degreesLongitude
                        val name = loc.name.ifEmpty { null }
                        val address = loc.address.ifEmpty { null }
                        val geoUri = "geo:%.5f,%.5f".format(lat, lng)
                        val mapsUrl = "https://maps.google.com/maps?q=%.5f,%.5f".format(lat, lng)
                        buildString {
                            append("Location: ")
                            if (name != null) append("$name\n")
                            if (address != null) append("$address\n")
                            append("$geoUri\n$mapsUrl")
                        }
                    }
                    e2eMessage.hasLiveLocationMessage() -> {
                        val loc = e2eMessage.liveLocationMessage
                        "Live Location: geo:%.5f,%.5f".format(loc.degreesLatitude, loc.degreesLongitude)
                    }
                    e2eMessage.hasGroupInviteMessage() -> {
                        val inv = e2eMessage.groupInviteMessage
                        "[Group Invite: ${inv.groupName}]\nCode: ${inv.inviteCode}\nExpiry: ${inv.inviteExpiration}"
                    }
                    e2eMessage.hasReactionMessage() -> e2eMessage.reactionMessage.text
                    e2eMessage.hasPollCreationMessage() -> {
                        val poll = e2eMessage.pollCreationMessage
                        "[Poll: ${poll.name}]\n${poll.optionsList.joinToString("\n") { "• ${it.optionName}" }}"
                    }
                    e2eMessage.hasPollCreationMessageV2() -> {
                        val poll = e2eMessage.pollCreationMessageV2
                        "[Poll: ${poll.name}]\n${poll.optionsList.joinToString("\n") { "• ${it.optionName}" }}"
                    }
                    e2eMessage.hasPollCreationMessageV3() -> {
                        val poll = e2eMessage.pollCreationMessageV3
                        "[Poll: ${poll.name}]\n${poll.optionsList.joinToString("\n") { "• ${it.optionName}" }}"
                    }
                    e2eMessage.hasPollUpdateMessage() -> "[Poll Vote]"
                    e2eMessage.hasEventMessage() -> {
                        val evt = e2eMessage.eventMessage
                        buildString {
                            append("[Event: ${evt.name}]")
                            if (evt.description.isNotEmpty()) append("\n${evt.description}")
                            if (evt.location?.name?.isNotEmpty() == true) append("\nAt: ${evt.location.name}")
                        }
                    }
                    e2eMessage.hasCommentMessage() -> e2eMessage.commentMessage.message?.conversation ?: "[Comment]"
                    e2eMessage.hasProtocolMessage() -> {
                        when (parsedType) {
                            "revoke" -> "[Message Deleted]"
                            "edit" -> e2eMessage.protocolMessage.editedMessage?.conversation ?: "[Edited]"
                            "disappearing timer change" -> {
                                val timer = e2eMessage.protocolMessage.ephemeralExpiration
                                if (timer == 0) "[Disappearing Messages Disabled]"
                                else "[Disappearing Messages: ${formatDisappearingTimer(timer)}]"
                            }
                            else -> ""
                        }
                    }
                    e2eMessage.hasOrderMessage() -> "[Order]"
                    e2eMessage.hasProductMessage() -> "[Product]"
                    e2eMessage.hasListMessage() -> {
                        val list = e2eMessage.listMessage
                        buildString {
                            append(list.title.ifEmpty { "[List]" })
                            if (list.description.isNotEmpty()) append("\n${list.description}")
                            list.sectionsList.forEach { section ->
                                if (section.title.isNotEmpty()) append("\n**${section.title}**")
                                section.rowsList.forEach { row ->
                                    append("\n• ${row.title}")
                                    if (row.description.isNotEmpty()) append(" - ${row.description}")
                                }
                            }
                        }
                    }
                    e2eMessage.hasListResponseMessage() -> {
                        val resp = e2eMessage.listResponseMessage
                        "[Selected: ${resp.title}]"
                    }
                    e2eMessage.hasButtonsMessage() -> {
                        val btns = e2eMessage.buttonsMessage
                        buildString {
                            append(btns.contentText.ifEmpty { "[Buttons]" })
                            btns.buttonsList.forEach { btn ->
                                append("\n[${btn.buttonText.displayText}]")
                            }
                        }
                    }
                    e2eMessage.hasButtonsResponseMessage() -> {
                        "[Button: ${e2eMessage.buttonsResponseMessage.selectedDisplayText}]"
                    }
                    e2eMessage.hasTemplateMessage() -> {
                        val tmpl = e2eMessage.templateMessage
                        if (tmpl.hasHydratedTemplate()) {
                            val hydrated = tmpl.hydratedTemplate
                            buildString {
                                append(hydrated.hydratedContentText.ifEmpty { "[Template]" })
                                if (hydrated.hydratedFooterText.isNotEmpty()) append("\n_${hydrated.hydratedFooterText}_")
                                hydrated.hydratedButtonsList.forEach { btn ->
                                    when {
                                        btn.hasQuickReplyButton() -> append("\n[${btn.quickReplyButton.displayText}]")
                                        btn.hasUrlButton() -> append("\n[${btn.urlButton.displayText}: ${btn.urlButton.url}]")
                                        btn.hasCallButton() -> append("\n[Call: ${btn.callButton.displayText}]")
                                    }
                                }
                            }
                        } else "[Template]"
                    }
                    e2eMessage.hasInteractiveMessage() -> {
                        val inter = e2eMessage.interactiveMessage
                        buildString {
                            if (inter.hasBody()) append(inter.body.text)
                            else append("[Interactive]")
                            if (inter.hasFooter()) append("\n_${inter.footer.text}_")
                            if (inter.hasNativeFlowMessage()) {
                                inter.nativeFlowMessage.buttonsList.forEach { btn ->
                                    append("\n[${btn.name}]")
                                }
                            }
                        }
                    }
                    e2eMessage.hasKeepInChatMessage() -> "[Keep in Chat]"
                    e2eMessage.hasPinInChatMessage() -> "[Pin in Chat]"
                    e2eMessage.hasEncReactionMessage() -> {
                        val encReaction = e2eMessage.encReactionMessage
                        if (encReaction.hasTargetMessageKey()) {
                            val targetId = encReaction.targetMessageKey.id
                            "[Reaction to $targetId]"
                        } else {
                            "[Encrypted Reaction]"
                        }
                    }
                    e2eMessage.hasEncCommentMessage() -> "[Encrypted Comment]"
                    e2eMessage.hasNewsletterAdminInviteMessage() -> "[Newsletter Admin Invite]"
                    e2eMessage.hasSecretEncryptedMessage() -> "[Secret Encrypted Message]"
                    e2eMessage.hasBotInvokeMessage() -> "[Bot Message]"
                    else -> ""
                }
                val mediaUrl = when {
                    e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.url
                    e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.url
                    e2eMessage.hasPtvMessage() -> e2eMessage.ptvMessage.url
                    e2eMessage.hasAudioMessage() -> e2eMessage.audioMessage.url
                    e2eMessage.hasDocumentMessage() -> e2eMessage.documentMessage.url
                    e2eMessage.hasStickerMessage() -> e2eMessage.stickerMessage.url
                    else -> null
                }
                val isRevoke = parsedType == "revoke"
                val revokeTargetId = if (isRevoke) e2eMessage.protocolMessage.key.id else null
                val isEdit = parsedType == "edit"
                val editTargetId = if (isEdit) e2eMessage.protocolMessage.key.id else null

                val locationData = when {
                    e2eMessage.hasLocationMessage() -> LocationData(
                        latitude = e2eMessage.locationMessage.degreesLatitude,
                        longitude = e2eMessage.locationMessage.degreesLongitude,
                        name = e2eMessage.locationMessage.name.ifEmpty { null },
                        address = e2eMessage.locationMessage.address.ifEmpty { null },
                        url = e2eMessage.locationMessage.url.ifEmpty { null },
                    )
                    e2eMessage.hasLiveLocationMessage() -> LocationData(
                        latitude = e2eMessage.liveLocationMessage.degreesLatitude,
                        longitude = e2eMessage.liveLocationMessage.degreesLongitude,
                        isLive = true,
                    )
                    else -> null
                }

                val contactData = when {
                    e2eMessage.hasContactMessage() -> ContactData(
                        displayName = e2eMessage.contactMessage.displayName,
                        vcard = e2eMessage.contactMessage.vcard,
                    )
                    else -> null
                }

                val pollData = when {
                    e2eMessage.hasPollCreationMessage() -> PollData.fromCreation(e2eMessage.pollCreationMessage)
                    e2eMessage.hasPollCreationMessageV2() -> PollData.fromCreationV2(e2eMessage.pollCreationMessageV2)
                    e2eMessage.hasPollCreationMessageV3() -> PollData.fromCreationV3(e2eMessage.pollCreationMessageV3)
                    e2eMessage.hasPollUpdateMessage() -> PollData(
                        isPollVote = true,
                        pollCreationMessageKey = e2eMessage.pollUpdateMessage.pollCreationMessageKey?.id,
                        encPayload = e2eMessage.pollUpdateMessage.vote?.selectedOptions?.map { it.toByteArray() },
                    )
                    else -> null
                }

                val groupInviteData = if (e2eMessage.hasGroupInviteMessage()) {
                    val inv = e2eMessage.groupInviteMessage
                    GroupInviteMeta(
                        jid = inv.groupJid ?: "",
                        code = inv.inviteCode ?: "",
                        expiration = inv.inviteExpiration,
                        inviter = participant ?: from,
                        groupName = inv.groupName ?: "",
                    )
                } else null

                val disappearingTimer = if (parsedType == "disappearing timer change") {
                    e2eMessage.protocolMessage.ephemeralExpiration.toLong()
                } else null

                val contextInfo = extractContextInfo(e2eMessage)

                WhatsAppMessage(
                    id = id,
                    from = from,
                    to = node.attrs["to"] ?: "",
                    body = body,
                    timestamp = timestamp,
                    type = type,
                    participant = participant,
                    mediaUrl = mediaUrl,
                    isReaction = e2eMessage.hasReactionMessage(),
                    reactionTargetId = if (e2eMessage.hasReactionMessage()) e2eMessage.reactionMessage.key.id else null,
                    isRevoke = isRevoke,
                    revokeTargetId = revokeTargetId,
                    isEdit = isEdit,
                    editTargetId = editTargetId,
                    messageType = parsedType,
                    locationData = locationData,
                    contactData = contactData,
                    pollData = pollData,
                    groupInviteData = groupInviteData,
                    disappearingTimer = disappearingTimer,
                    isForwarded = contextInfo.isForwarded,
                    forwardingScore = contextInfo.forwardingScore,
                    replyToId = contextInfo.replyToId,
                    mentionedJids = contextInfo.mentionedJids,
                    e2eMessage = e2eMessage,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse E2E message", e)
                WhatsAppMessage(id = id, from = from, to = node.attrs["to"] ?: "", body = "", timestamp = timestamp, type = type, participant = participant)
            }
        }

        // Fallback: plaintext body node
        val bodyNode = node.getChildByTag("body")
        val body = bodyNode?.data?.let { String(it, Charsets.UTF_8) } ?: ""

        return WhatsAppMessage(
            id = id,
            from = from,
            to = node.attrs["to"] ?: "",
            body = body,
            timestamp = timestamp,
            type = type,
            participant = participant,
        )
    }
}

data class WhatsAppMessage(
    val id: String,
    val from: String,
    val to: String,
    val body: String,
    val timestamp: Long,
    val type: String,
    val participant: String? = null,
    val mediaUrl: String? = null,
    val isReaction: Boolean = false,
    val reactionTargetId: String? = null,
    val isRevoke: Boolean = false,
    val revokeTargetId: String? = null,
    val isEdit: Boolean = false,
    val editTargetId: String? = null,
    val messageType: String = "unknown",
    val locationData: LocationData? = null,
    val contactData: ContactData? = null,
    val pollData: PollData? = null,
    val groupInviteData: GroupInviteMeta? = null,
    val disappearingTimer: Long? = null,
    val isForwarded: Boolean = false,
    val forwardingScore: Int = 0,
    val replyToId: String? = null,
    val mentionedJids: List<String> = emptyList(),
    val e2eMessage: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message? = null,
)

data class LocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val name: String? = null,
    val address: String? = null,
    val url: String? = null,
    val isLive: Boolean = false,
) {
    fun toGeoUri(): String = "geo:%.5f,%.5f".format(latitude, longitude)
    fun toMapsUrl(): String = "https://maps.google.com/maps?q=%.5f,%.5f".format(latitude, longitude)
}

data class ContactData(
    val displayName: String = "",
    val vcard: String = "",
)

data class PollData(
    val question: String = "",
    val options: List<String> = emptyList(),
    val selectableOptionCount: Int = 0,
    val isPollVote: Boolean = false,
    val pollCreationMessageKey: String? = null,
    val encPayload: List<ByteArray>? = null,
) {
    companion object {
        fun fromCreation(poll: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage): PollData {
            return PollData(
                question = poll.name,
                options = poll.optionsList.map { it.optionName },
                selectableOptionCount = poll.selectableOptionsCount,
            )
        }
        fun fromCreationV2(poll: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage): PollData {
            return PollData(
                question = poll.name,
                options = poll.optionsList.map { it.optionName },
                selectableOptionCount = poll.selectableOptionsCount,
            )
        }
        fun fromCreationV3(poll: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage): PollData {
            return PollData(
                question = poll.name,
                options = poll.optionsList.map { it.optionName },
                selectableOptionCount = poll.selectableOptionsCount,
            )
        }
    }
}

data class ContextInfoResult(
    val isForwarded: Boolean = false,
    val forwardingScore: Int = 0,
    val replyToId: String? = null,
    val mentionedJids: List<String> = emptyList(),
)
