package com.vayunmathur.messages.telegram.api.types

import android.util.Log
import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer

object TlSkip {

    private const val TAG = "TlSkip"

    /** Thrown when an unknown boxed constructor is encountered: its wire size is unknowable, so
     *  continuing would silently mis-parse the rest of the message. Callers decoding a single
     *  message/update catch this and drop that message rather than corrupting the stream. */
    class TlDesyncException(val constructorId: Int) :
        IllegalStateException("Unknown boxed constructor 0x${constructorId.toUInt().toString(16)}; aborting decode to avoid desync")

    fun skipRecentStory(buf: TlBuffer) {
        val typeId = buf.int32() // constructor 0x711d692d
        val flags = Fields.decode(buf)
        if (flags.has(1)) buf.int32() // max_id
    }

    fun skipMessageFwdHeader(buf: TlBuffer) {
        val flags = Fields.decode(buf)
        if (flags.has(0)) decodePeer(buf) // from_id
        if (flags.has(5)) buf.string() // from_name
        buf.int32() // date
        if (flags.has(2)) buf.int32() // channel_post
        if (flags.has(3)) buf.string() // post_author
        if (flags.has(4)) { decodePeer(buf); buf.int32() } // saved_from_peer + saved_from_msg_id
        if (flags.has(8)) decodePeer(buf) // saved_from_id
        if (flags.has(9)) buf.string() // saved_from_name
        if (flags.has(10)) buf.int32() // saved_date
        if (flags.has(6)) buf.string() // psa_type
    }

    fun skipReplyTo(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x0e5af939.toInt() -> { // messageReplyStoryHeader
                decodePeer(buf) // peer
                buf.int32() // story_id
            }
            0x1b97dd66.toInt() -> { // messageReplyHeader
                val flags = Fields.decode(buf)
                if (flags.has(4)) buf.int32() // reply_to_msg_id
                if (flags.has(0)) decodePeer(buf) // reply_to_peer_id
                if (flags.has(5)) skipBoxedType(buf) // reply_from (MessageFwdHeader)
                if (flags.has(8)) skipBoxedType(buf) // reply_media
                if (flags.has(1)) buf.int32() // reply_to_top_id
                if (flags.has(6)) buf.string() // quote_text
                if (flags.has(7)) skipVector(buf) { skipMessageEntity(it) } // quote_entities
                if (flags.has(10)) buf.int32() // quote_offset
                if (flags.has(11)) buf.int32() // todo_item_id
                if (flags.has(12)) buf.bytes() // poll_option
            }
        }
    }

    fun skipPeerNotifySettings(buf: TlBuffer) {
        val typeId = buf.int32()
        if (typeId == 0x99622c0c.toInt()) {
            val flags = Fields.decode(buf)
            if (flags.has(0)) buf.int32() // show_previews (Bool type id)
            if (flags.has(1)) buf.int32() // silent (Bool type id)
            if (flags.has(2)) buf.int32() // mute_until
            if (flags.has(3)) skipNotificationSound(buf) // ios_sound
            if (flags.has(4)) skipNotificationSound(buf) // android_sound
            if (flags.has(5)) skipNotificationSound(buf) // other_sound
            if (flags.has(6)) buf.int32() // stories_muted (Bool type id)
            if (flags.has(7)) buf.int32() // stories_hide_sender (Bool type id)
            if (flags.has(8)) skipNotificationSound(buf) // stories_ios_sound
            if (flags.has(9)) skipNotificationSound(buf) // stories_android_sound
            if (flags.has(10)) skipNotificationSound(buf) // stories_other_sound
        }
    }

    private fun skipNotificationSound(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x97e8bebe.toInt() -> {} // notificationSoundDefault
            0x6f0c34df.toInt() -> {} // notificationSoundNone
            0x830b9ae4.toInt() -> { buf.string(); buf.string() } // notificationSoundLocal
            0xff6c8049.toInt() -> buf.int64() // notificationSoundRingtone
        }
    }

    fun skipChatPhoto(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x37c1011c.toInt() -> {} // chatPhotoEmpty
            0x1c6e1c11.toInt() -> { // chatPhoto
                val flags = Fields.decode(buf)
                buf.int64() // photo_id
                if (flags.has(1)) buf.bytes() // stripped_thumb
                buf.int32() // dc_id
            }
        }
    }

    /** Like [skipChatPhoto] but captures photo_id + dc_id. Consumes identical bytes. */
    fun parseChatPhoto(buf: TlBuffer): ProfilePhoto? {
        val typeId = buf.int32()
        return when (typeId) {
            0x37c1011c.toInt() -> null // chatPhotoEmpty
            0x1c6e1c11.toInt() -> { // chatPhoto
                val flags = Fields.decode(buf)
                val photoId = buf.int64()
                if (flags.has(1)) buf.bytes() // stripped_thumb
                val dcId = buf.int32()
                if (photoId == 0L) null else ProfilePhoto(photoId, dcId)
            }
            else -> null
        }
    }

    fun skipUserProfilePhoto(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x4f11bae1.toInt() -> {} // userProfilePhotoEmpty
            0x82d1f706.toInt() -> { // userProfilePhoto
                val flags = Fields.decode(buf)
                buf.int64() // photo_id
                if (flags.has(1)) buf.bytes() // stripped_thumb
                buf.int32() // dc_id
            }
        }
    }

    /** Like [skipUserProfilePhoto] but captures photo_id + dc_id. Consumes identical bytes. */
    fun parseUserProfilePhoto(buf: TlBuffer): ProfilePhoto? {
        val typeId = buf.int32()
        return when (typeId) {
            0x4f11bae1.toInt() -> null // userProfilePhotoEmpty
            0x82d1f706.toInt() -> { // userProfilePhoto
                val flags = Fields.decode(buf)
                val photoId = buf.int64()
                if (flags.has(1)) buf.bytes() // stripped_thumb
                val dcId = buf.int32()
                if (photoId == 0L) null else ProfilePhoto(photoId, dcId)
            }
            else -> null
        }
    }

    fun skipUserStatus(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x09d05049.toInt() -> {} // userStatusEmpty
            0xedb93949.toInt() -> buf.int32() // userStatusOnline: expires
            0x008c703f.toInt() -> buf.int32() // userStatusOffline: was_online
            0x7b197dc8.toInt() -> Fields.decode(buf) // userStatusRecently: flags (layer 225)
            0x541a1d1a.toInt() -> Fields.decode(buf) // userStatusLastWeek: flags (layer 225)
            0x65899777.toInt() -> Fields.decode(buf) // userStatusLastMonth: flags (layer 225)
        }
    }

    fun skipBoxedType(buf: TlBuffer) {
        val typeId = buf.int32()
        skipByTypeId(typeId, buf)
    }

    private fun skipByTypeId(typeId: Int, buf: TlBuffer) {
        when (typeId) {
            // ---- MessageMedia ----
            0x3ded6320.toInt() -> {} // messageMediaEmpty
            0x695150d7.toInt() -> { // messageMediaPhoto (old)
                val flags = Fields.decode(buf)
                if (flags.has(0)) skipBoxedType(buf) // photo
                if (flags.has(2)) buf.int32() // ttl_seconds
            }
            0xe216eb63.toInt() -> { // messageMediaPhoto (current)
                val flags = Fields.decode(buf)
                if (flags.has(0)) skipBoxedType(buf) // photo
                if (flags.has(2)) buf.int32() // ttl_seconds
                if (flags.has(4)) skipBoxedType(buf) // video
            }
            0x4cf4d72d.toInt() -> { // messageMediaDocument (old)
                val flags = Fields.decode(buf)
                if (flags.has(0)) skipBoxedType(buf) // document
                if (flags.has(5)) skipBoxedType(buf) // alt_document
                if (flags.has(9)) skipBoxedType(buf) // video_cover (Photo)
                if (flags.has(10)) buf.int32() // video_timestamp
                if (flags.has(2)) buf.int32() // ttl_seconds
            }
            0x52d8ccd9.toInt() -> { // messageMediaDocument (current)
                val flags = Fields.decode(buf)
                if (flags.has(0)) skipBoxedType(buf) // document
                if (flags.has(5)) skipVectorBoxed(buf) // alt_documents
                if (flags.has(9)) skipBoxedType(buf) // video_cover
                if (flags.has(10)) buf.int32() // video_timestamp
                if (flags.has(2)) buf.int32() // ttl_seconds
            }
            0x56e0d474.toInt() -> skipBoxedType(buf) // messageMediaGeo: geo
            0xb940c666.toInt() -> { // messageMediaGeoLive
                val f = Fields.decode(buf)
                skipBoxedType(buf) // geo
                if (f.has(0)) buf.int32() // heading
                buf.int32() // period
                if (f.has(1)) buf.int32() // proximity_notification_radius
            }
            0x70322949.toInt() -> { // messageMediaContact
                buf.string(); buf.string(); buf.string(); buf.string(); buf.int64()
            }
            0x9f84f49e.toInt() -> {} // messageMediaUnsupported
            0x2ec0533f.toInt() -> { // messageMediaVenue
                skipBoxedType(buf) // geo
                buf.string(); buf.string(); buf.string(); buf.string(); buf.string()
            }
            0xfdb19008.toInt() -> skipBoxedType(buf) // messageMediaGame: game
            0x3f7ee58b.toInt() -> { buf.int32(); buf.string() } // messageMediaDice (old): value emoticon
            0x08cbec07.toInt() -> { // messageMediaDice (current)
                val flags = Fields.decode(buf)
                buf.int32() // value
                buf.string() // emoticon
                if (flags.has(0)) skipBoxedType(buf) // game_outcome
            }
            0xddf10c3b.toInt() -> { // messageMediaWebPage
                Fields.decode(buf) // flags
                skipWebPage(buf)
            }

            // ---- Photo ----
            0x2331b22d.toInt() -> buf.int64() // photoEmpty: id
            0xfb197a65.toInt() -> { // photo
                val flags = Fields.decode(buf)
                buf.int64() // id
                buf.int64() // access_hash
                buf.bytes() // file_reference
                buf.int32() // date
                skipVector(buf) { skipPhotoSize(it) } // sizes
                if (flags.has(1)) skipVector(buf) { skipVideoSize(it) } // video_sizes
                buf.int32() // dc_id
            }

            // ---- GeoPoint ----
            0x1117dd5f.toInt() -> {} // geoPointEmpty
            0xb2a2f663.toInt() -> { // geoPoint
                val f = Fields.decode(buf)
                buf.double(); buf.double(); buf.int64() // long lat access_hash
                if (f.has(0)) buf.int32() // accuracy_radius
            }

            // ---- Document ----
            0x36f8c871.toInt() -> buf.int64() // documentEmpty: id
            0x8fd4c4d8.toInt() -> { // document
                val flags = Fields.decode(buf)
                buf.int64() // id
                buf.int64() // access_hash
                buf.bytes() // file_reference
                buf.int32() // date
                buf.string() // mime_type
                buf.int64() // size
                if (flags.has(0)) skipVector(buf) { skipPhotoSize(it) } // thumbs
                if (flags.has(1)) skipVector(buf) { skipVideoSize(it) } // video_thumbs
                buf.int32() // dc_id
                skipVector(buf) { skipDocumentAttribute(it) } // attributes
            }

            // ---- Game ----
            0xbdf9653b.toInt() -> { // game
                val flags = Fields.decode(buf)
                buf.int64(); buf.int64() // id access_hash
                buf.string(); buf.string(); buf.string() // short_name title description
                skipBoxedType(buf) // photo
                if (flags.has(0)) skipBoxedType(buf) // document
            }

            // ---- ChatAdminRights ----
            0x5fb224d5.toInt() -> Fields.decode(buf) // chatAdminRights: flags only

            // ---- ChatBannedRights ----
            0x9f120418.toInt() -> { Fields.decode(buf); buf.int32() } // chatBannedRights: flags + until_date

            // ---- PeerColor ----
            0xb54b5acf.toInt() -> { // peerColor
                val f = Fields.decode(buf)
                if (f.has(0)) buf.int32() // color
                if (f.has(1)) buf.int64() // background_emoji_id
            }

            // ---- Username ----
            0xb4073647.toInt() -> { Fields.decode(buf); buf.string() } // username: flags + username

            // ---- EmojiStatus ----
            0x2de11aae.toInt() -> {} // emojiStatusEmpty
            0xe7ff068a.toInt() -> { // emojiStatus
                val f = Fields.decode(buf)
                buf.int64() // document_id
                if (f.has(0)) buf.int32() // until
            }
            0x7184603b.toInt() -> { // emojiStatusCollectible
                val f = Fields.decode(buf)
                buf.int64() // collectible_id
                buf.int64() // document_id
                buf.string() // title
                buf.string() // slug
                buf.int64() // pattern_document_id
                buf.int32(); buf.int32(); buf.int32(); buf.int32() // center/edge/pattern/text color
                if (f.has(0)) buf.int32() // until
            }

            // ---- RestrictionReason ----
            0xd072acb4.toInt() -> { buf.string(); buf.string(); buf.string() } // restrictionReason: platform reason text

            // ---- InputChannel ----
            0xee8c1e86.toInt() -> {} // inputChannelEmpty
            0xf35aec28.toInt() -> { buf.int64(); buf.int64() } // inputChannel: channel_id access_hash

            // ---- InputPeer ----
            0x7f3b18ea.toInt() -> {} // inputPeerEmpty
            0x7da07ec9.toInt() -> {} // inputPeerSelf
            0x35a95cb9.toInt() -> buf.int64() // inputPeerChat: chat_id
            0xdde8a54c.toInt() -> { buf.int64(); buf.int64() } // inputPeerUser: user_id access_hash
            0x27bcbbfc.toInt() -> { buf.int64(); buf.int64() } // inputPeerChannel: channel_id access_hash

            // ---- DraftMessage ----
            0x1b0c841a.toInt() -> { // draftMessageEmpty
                val f = Fields.decode(buf)
                if (f.has(0)) buf.int32() // date
            }
            0x96eaa5eb.toInt() -> skipDraftMessage(buf) // draftMessage

            // ---- SuggestedPost ----
            0x0e8e37e5.toInt() -> { // suggestedPost
                val f = Fields.decode(buf)
                if (f.has(3)) skipBoxedType(buf) // price (StarsAmountClass)
                if (f.has(0)) buf.int32() // schedule_date
            }

            // ---- StarsAmount ----
            0xbbb6b4a3.toInt() -> { buf.int64(); buf.int32() } // starsAmount: amount nanos
            0x74aee3e0.toInt() -> buf.int64() // starsTonAmount: amount

            // ---- RequestPeerType ----
            0x5f3b8a00.toInt() -> { // requestPeerTypeUser
                val f = Fields.decode(buf)
                if (f.has(0)) buf.int32() // bot (Bool)
                if (f.has(1)) buf.int32() // premium (Bool)
            }
            0xc9f06e1b.toInt() -> { // requestPeerTypeChat
                val f = Fields.decode(buf)
                if (f.has(3)) buf.int32() // has_username (Bool)
                if (f.has(4)) buf.int32() // forum (Bool)
                if (f.has(1)) skipBoxedType(buf) // user_admin_rights
                if (f.has(2)) skipBoxedType(buf) // bot_admin_rights
            }
            0x339bef6c.toInt() -> { // requestPeerTypeBroadcast
                val f = Fields.decode(buf)
                if (f.has(3)) buf.int32() // has_username (Bool)
                if (f.has(1)) skipBoxedType(buf) // user_admin_rights
                if (f.has(2)) skipBoxedType(buf) // bot_admin_rights
            }

            // ---- PasswordKdfAlgo ----
            0xd45ab096.toInt() -> {} // passwordKdfAlgoUnknown
            0x3a912d4a.toInt() -> { buf.bytes(); buf.bytes(); buf.int32(); buf.bytes() } // passwordKdfAlgoSHA256...: salt1 salt2 g p

            // ---- SecurePasswordKdfAlgo ----
            0x004a8537.toInt() -> {} // securePasswordKdfAlgoUnknown
            0x86471d92.toInt() -> buf.bytes() // securePasswordKdfAlgoSHA512: salt
            0xbbf2dda0.toInt() -> buf.bytes() // securePasswordKdfAlgoPBKDF2...: salt

            // ---- SearchPostsFlood ----
            0x3e0b5b6a.toInt() -> { // searchPostsFlood
                val f = Fields.decode(buf)
                buf.int32() // total_daily
                buf.int32() // remains
                if (f.has(1)) buf.int32() // wait_till
                buf.int64() // stars_amount
            }

            // ---- MessageFwdHeader ----
            0x4e4df4bb.toInt() -> skipMessageFwdHeader(buf) // messageFwdHeader

            // ---- ForumTopic ----
            0x023f109b.toInt() -> buf.int32() // forumTopicDeleted: id
            0xfcdad815.toInt() -> skipForumTopic(buf) // forumTopic

            else -> {
                // Unknown boxed type: we cannot know its size, so any further reads would
                // mis-parse. Abort the current message decode instead of silently corrupting.
                throw TlDesyncException(typeId)
            }
        }
    }

    fun skipVectorBoxed(buf: TlBuffer) {
        val vecId = buf.int32()
        val count = buf.int32()
        for (i in 0 until count) {
            skipBoxedType(buf)
        }
    }

    fun skipVector(buf: TlBuffer, elementSkip: (TlBuffer) -> Unit) {
        buf.int32() // vector constructor id
        val count = buf.int32()
        repeat(count) { elementSkip(buf) }
    }

    // ---- PhotoSize (public for media decoder) ----

    fun skipPhotoSizeBoxed(buf: TlBuffer) = skipPhotoSize(buf)
    fun skipVideoSizeBoxed(buf: TlBuffer) = skipVideoSize(buf)
    fun skipInputStickerSetBoxed(buf: TlBuffer) = skipInputStickerSet(buf)

    // ---- ForumTopic ----

    private fun skipForumTopic(buf: TlBuffer) {
        val flags = Fields.decode(buf)
        buf.int32() // id
        buf.int32() // date
        decodePeer(buf) // peer
        buf.string() // title
        buf.int32() // icon_color
        if (flags.has(0)) buf.int64() // icon_emoji_id
        buf.int32() // top_message
        buf.int32(); buf.int32() // read_inbox_max_id, read_outbox_max_id
        buf.int32(); buf.int32(); buf.int32(); buf.int32() // unread_count, mentions, reactions, poll_votes
        decodePeer(buf) // from_id
        skipPeerNotifySettings(buf) // notify_settings
        if (flags.has(4)) skipBoxedType(buf) // draft
    }

    // ---- DraftMessage ----

    private fun skipDraftMessage(buf: TlBuffer) {
        val flags = Fields.decode(buf)
        if (flags.has(4)) skipInputReplyTo(buf) // reply_to
        buf.string() // message
        if (flags.has(3)) skipVector(buf) { skipMessageEntity(it) } // entities
        if (flags.has(5)) skipBoxedType(buf) // media
        buf.int32() // date
        if (flags.has(7)) buf.int64() // effect
        if (flags.has(8)) skipBoxedType(buf) // suggested_post
    }

    private fun skipInputReplyTo(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x22c0f6d5.toInt(), 0x3bd4b7c2.toInt() -> { // inputReplyToMessage (layer 225 / latest)
                val f = Fields.decode(buf)
                buf.int32() // reply_to_msg_id
                if (f.has(0)) skipBoxedType(buf) // reply_to_peer_id
                if (f.has(1)) buf.int32() // top_msg_id
                if (f.has(2)) buf.string() // quote_text
                if (f.has(3)) skipVector(buf) { skipMessageEntity(it) } // quote_entities
                if (f.has(4)) buf.int32() // quote_offset
            }
            0x15b0f283.toInt(), 0x5881323a.toInt() -> { // inputReplyToStory (layer 225 / latest)
                skipBoxedType(buf) // peer
                buf.int32() // story_id
            }
            else -> Log.w(TAG, "Unknown InputReplyTo: 0x${typeId.toUInt().toString(16)}")
        }
    }

    // ---- PhotoSize ----

    private fun skipPhotoSize(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xe17e23c0.toInt() -> buf.string() // photoSizeEmpty: type
            0x75c78e60.toInt() -> { buf.string(); buf.int32(); buf.int32(); buf.int32() } // photoSize
            0x021e1ad6.toInt() -> { buf.string(); buf.int32(); buf.int32(); buf.bytes() } // photoCachedSize
            0xe0b0bc2e.toInt() -> { buf.string(); buf.bytes() } // photoStrippedSize
            0xfa3efb95.toInt() -> { // photoSizeProgressive
                buf.string(); buf.int32(); buf.int32()
                buf.int32() // vector constructor
                val c = buf.int32() // count
                repeat(c) { buf.int32() }
            }
            0xd8214d41.toInt() -> { buf.string(); buf.bytes() } // photoPathSize
            else -> Log.w(TAG, "Unknown PhotoSize: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipVideoSize(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xde33b094.toInt() -> { // videoSize
                val flags = Fields.decode(buf)
                buf.string(); buf.int32(); buf.int32(); buf.int32() // type w h size
                if (flags.has(0)) buf.double() // video_start_ts
            }
            0x0f85c68f.toInt() -> { // videoSizeEmojiMarkup
                buf.int64() // emoji_id
                skipVector(buf) { it.int32() } // background_colors
            }
            0x0da082fe.toInt() -> { // videoSizeStickerMarkup
                skipInputStickerSet(buf)
                buf.int64() // sticker_id
                skipVector(buf) { it.int32() } // background_colors
            }
            else -> Log.w(TAG, "Unknown VideoSize: 0x${typeId.toUInt().toString(16)}")
        }
    }

    // ---- DocumentAttribute ----

    private fun skipDocumentAttribute(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x6c37c15c.toInt() -> { buf.int32(); buf.int32() } // imageSize: w h
            0x11b58939.toInt() -> {} // animated
            0x6319d612.toInt() -> { // sticker
                val flags = Fields.decode(buf)
                buf.string() // alt
                skipInputStickerSet(buf)
                if (flags.has(0)) { // mask_coords
                    buf.int32() // constructor
                    buf.int32(); buf.double(); buf.double(); buf.double() // n x y zoom
                }
            }
            0x17399fad.toInt(), 0x43c57c48.toInt() -> { // video
                val flags = Fields.decode(buf)
                buf.double(); buf.int32(); buf.int32() // duration w h
                if (flags.has(2)) buf.int32() // preload_prefix_size
                if (flags.has(4)) buf.double() // video_start_ts
                if (flags.has(5)) buf.string() // video_codec
            }
            0x9852f9c6.toInt() -> { // audio
                val flags = Fields.decode(buf)
                buf.int32() // duration
                if (flags.has(0)) buf.string() // title
                if (flags.has(1)) buf.string() // performer
                if (flags.has(2)) buf.bytes() // waveform
            }
            0x15590068.toInt() -> buf.string() // filename
            0x9801d2f7.toInt() -> {} // hasStickers
            0xfd149899.toInt() -> { // customEmoji
                Fields.decode(buf)
                buf.string() // alt
                skipInputStickerSet(buf)
            }
            else -> Log.w(TAG, "Unknown DocumentAttribute: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipInputStickerSet(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xffb62b95.toInt() -> {} // empty
            0x9de7a269.toInt() -> { buf.int64(); buf.int64() } // ID
            0x861cc8a0.toInt() -> buf.string() // shortName
            0xe67f520e.toInt() -> buf.string() // dice: emoticon
            else -> {} // most other variants are parameterless
        }
    }

    // ---- WebPage (public for media decoder) ----

    fun skipWebPageBoxed(buf: TlBuffer) {
        skipWebPage(buf)
    }

    private fun skipWebPage(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x211a1788.toInt() -> { // webPageEmpty
                val f = Fields.decode(buf)
                buf.int64() // id
                if (f.has(0)) buf.string() // url
            }
            0xb0d13e47.toInt() -> { // webPagePending
                val flags = Fields.decode(buf)
                buf.int64() // id
                if (flags.has(0)) buf.string() // url
                buf.int32() // date
            }
            0xe89c45b2.toInt() -> { // webPage
                val flags = Fields.decode(buf)
                buf.int64() // id
                buf.string() // url
                buf.string() // display_url
                buf.int32() // hash
                if (flags.has(0)) buf.string() // type
                if (flags.has(1)) buf.string() // site_name
                if (flags.has(2)) buf.string() // title
                if (flags.has(3)) buf.string() // description
                if (flags.has(4)) skipBoxedType(buf) // photo
                if (flags.has(5)) { buf.string(); buf.string() } // embed_url embed_type
                if (flags.has(6)) { buf.int32(); buf.int32() } // embed_width embed_height
                if (flags.has(7)) buf.int32() // duration
                if (flags.has(8)) buf.string() // author
                if (flags.has(9)) skipBoxedType(buf) // document
                if (flags.has(10)) skipPage(buf) // cached_page
                if (flags.has(12)) skipVectorBoxed(buf) // attributes
            }
            0x7311ca11.toInt() -> { // webPageNotModified
                val wFlags = Fields.decode(buf)
                if (wFlags.has(0)) buf.int32() // cached_page_views
            }
            else -> Log.w(TAG, "Unknown WebPage: 0x${typeId.toUInt().toString(16)}")
        }
    }

    // ---- MessageEntity ----

    fun skipMessageEntity(buf: TlBuffer) {
        val typeId = buf.int32()
        skipMessageEntity(buf, typeId)
    }

    fun skipMessageEntity(buf: TlBuffer, typeId: Int) {
        when (typeId) {
            // Simple: offset length
            0xbb92ba95.toInt(), // unknown
            0xfa04579d.toInt(), // mention
            0x6f635b0d.toInt(), // hashtag
            0x6cef8ac7.toInt(), // botCommand
            0x6ed02538.toInt(), // url
            0x64e475c2.toInt(), // email
            0xbd610bc9.toInt(), // bold
            0x826f8b60.toInt(), // italic
            0x28a20571.toInt(), // code
            0x9b69e34b.toInt(), // phone
            0x4c4e743f.toInt(), // cashtag
            0x9c4e7e8b.toInt(), // underline
            0xbf0693d4.toInt(), // strike
            0x32ca960f.toInt(), // spoiler
            0x761e6af4.toInt(), // bankCard
            -> { buf.int32(); buf.int32() }

            0x73924be0.toInt() -> { buf.int32(); buf.int32(); buf.string() } // pre: offset length language
            0x76a6d327.toInt() -> { buf.int32(); buf.int32(); buf.string() } // textUrl: offset length url
            0xdc7b1140.toInt() -> { buf.int32(); buf.int32(); buf.int64() } // mentionName: offset length user_id
            0xc8cf05f8.toInt() -> { buf.int32(); buf.int32(); buf.int64() } // customEmoji: offset length document_id
            0xf1ccaaac.toInt() -> { Fields.decode(buf); buf.int32(); buf.int32() } // blockquote: flags offset length
            0x020df5d0.toInt() -> { buf.int32(); buf.int32() } // blockquote (old): offset length

            else -> {
                Log.w(TAG, "Unknown MessageEntity 0x${typeId.toUInt().toString(16)}, assuming offset+length")
                buf.int32(); buf.int32()
            }
        }
    }

    // ---- ReplyMarkup ----

    fun skipReplyMarkup(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xa03e5b85.toInt() -> Fields.decode(buf) // replyKeyboardHide
            0x86b40b08.toInt() -> { // replyKeyboardForceReply
                val flags = Fields.decode(buf)
                if (flags.has(3)) buf.string() // placeholder
            }
            0x85dd99d1.toInt() -> { // replyKeyboardMarkup
                val flags = Fields.decode(buf)
                skipVector(buf) { skipKeyboardButtonRow(it) }
                if (flags.has(3)) buf.string() // placeholder
            }
            0x48a30254.toInt() -> { // replyInlineMarkup
                skipVector(buf) { skipKeyboardButtonRow(it) }
            }
            else -> Log.w(TAG, "Unknown ReplyMarkup: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipKeyboardButtonRow(buf: TlBuffer) {
        buf.int32() // constructor 0x77608b83
        skipVector(buf) { skipKeyboardButton(it) }
    }

    private fun skipKeyboardButtonStyle(buf: TlBuffer) {
        buf.int32() // constructor 0x4fdd3430
        val f = Fields.decode(buf)
        if (f.has(3)) buf.int64() // icon
    }

    private fun skipKeyboardButton(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x7d170cff.toInt() -> { // keyboardButton
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string() // text
            }
            0xd80c25ec.toInt() -> { // keyboardButtonUrl
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string(); buf.string() // text url
            }
            0xe62bc960.toInt() -> { // callback
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string(); buf.bytes() // text data
            }
            0x417efd8f.toInt() -> { // requestPhone
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string() // text
            }
            0xaa40f94d.toInt() -> { // requestGeoLocation
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string() // text
            }
            0x991399fc.toInt() -> { // switchInline
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string(); buf.string() // text query
                if (flags.has(1)) skipVectorBoxed(buf) // peer_types
            }
            0x89c590f9.toInt() -> { // game
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string() // text
            }
            0x3fa53905.toInt() -> { // buy
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string() // text
            }
            0xf51006f9.toInt() -> { // urlAuth
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string() // text
                if (flags.has(0)) buf.string() // fwd_text
                buf.string() // url
                buf.int32() // button_id
            }
            0x7a11d782.toInt() -> { // requestPoll
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                if (flags.has(0)) buf.int32() // quiz Bool
                buf.string() // text
            }
            0xe846b1a0.toInt() -> { // webView
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string(); buf.string() // text url
            }
            0xe15c4370.toInt() -> { // simpleWebView
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string(); buf.string() // text url
            }
            0x5b0f15f5.toInt() -> { // requestPeer
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string(); buf.int32() // text button_id
                skipBoxedType(buf) // peer_type
                buf.int32() // max_quantity
            }
            0xbcc4af10.toInt() -> { // copy
                val flags = Fields.decode(buf)
                if (flags.has(10)) skipKeyboardButtonStyle(buf) // style
                buf.string(); buf.string() // text copy_text
            }
            else -> Log.w(TAG, "Unknown KeyboardButton: 0x${typeId.toUInt().toString(16)}")
        }
    }

    // ---- MessageReplies ----

    fun skipMessageReplies(buf: TlBuffer) {
        val typeId = buf.int32() // 0x83d60fc2
        val flags = Fields.decode(buf)
        buf.int32() // replies
        buf.int32() // replies_pts
        if (flags.has(1)) skipVector(buf) { decodePeer(it) } // recent_repliers
        if (flags.has(0)) buf.int64() // channel_id
        if (flags.has(2)) buf.int32() // max_id
        if (flags.has(3)) buf.int32() // read_max_id
    }

    // ---- Reactions ----

    fun skipReactions(buf: TlBuffer) {
        val typeId = buf.int32()
        if (typeId == 0x0a339f0b.toInt()) { // messageReactions
            val flags = Fields.decode(buf)
            skipVector(buf) { skipReactionCount(it) } // results
            if (flags.has(1)) skipVector(buf) { skipMessagePeerReaction(it) } // recent_reactions
            if (flags.has(4)) skipVector(buf) { skipMessageReactor(it) } // top_reactors
        }
    }

    private fun skipReactionCount(buf: TlBuffer) {
        buf.int32() // constructor
        val flags = Fields.decode(buf)
        if (flags.has(0)) buf.int32() // chosen_order
        skipReaction(buf) // reaction
        buf.int32() // count
    }

    private fun skipReaction(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x1b2286b8.toInt() -> buf.string() // reactionEmoji: emoticon
            0x8935fc73.toInt() -> buf.int64() // reactionCustomEmoji: document_id
            0x79f5d419.toInt() -> {} // reactionEmpty
            0x523da4eb.toInt() -> {} // reactionPaid
            else -> Log.w(TAG, "Unknown Reaction: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipMessagePeerReaction(buf: TlBuffer) {
        buf.int32() // constructor
        val flags = Fields.decode(buf)
        decodePeer(buf) // peer_id
        buf.int32() // date
        skipReaction(buf) // reaction
    }

    private fun skipMessageReactor(buf: TlBuffer) {
        buf.int32() // constructor 0x4ba3a95a
        val flags = Fields.decode(buf)
        if (flags.has(3)) decodePeer(buf) // peer_id
        buf.int32() // count
    }

    // ---- RestrictionReason ----

    fun skipRestrictionReason(buf: TlBuffer) {
        buf.int32() // constructor
        buf.string(); buf.string(); buf.string() // platform reason text
    }

    // ---- FactCheck ----

    fun skipFactCheck(buf: TlBuffer) {
        buf.int32() // constructor
        val flags = Fields.decode(buf)
        if (flags.has(1)) buf.string() // country
        if (flags.has(0)) { // text: TextWithEntities
            buf.int32() // TextWithEntities constructor
            buf.string() // text
            skipVector(buf) { skipMessageEntity(it) } // entities
        }
        buf.int64() // hash
    }

    // ---- Page (Instant View) ----

    private fun skipPage(buf: TlBuffer) {
        val typeId = buf.int32() // 0x98657f0d
        val flags = Fields.decode(buf)
        buf.string() // url
        skipVector(buf) { skipPageBlock(it) } // blocks
        skipVector(buf) { skipBoxedType(it) } // photos
        skipVector(buf) { skipBoxedType(it) } // documents
        if (flags.has(3)) buf.int32() // views
    }

    private fun skipPageBlock(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x13567e8a.toInt() -> {} // pageBlockUnsupported
            0x70abc3fd.toInt() -> skipRichText(buf) // pageBlockTitle
            0x8ffa9a1f.toInt() -> skipRichText(buf) // pageBlockSubtitle
            0xbaafe5e0.toInt() -> { skipRichText(buf); buf.int32() } // pageBlockAuthorDate: author + published_date
            0xbfd064ec.toInt() -> skipRichText(buf) // pageBlockHeader
            0xf12bb6e1.toInt() -> skipRichText(buf) // pageBlockSubheader
            0x467a0766.toInt() -> skipRichText(buf) // pageBlockParagraph
            0xc070d93e.toInt() -> { skipRichText(buf); buf.string() } // pageBlockPreformatted: text + language
            0x48870999.toInt() -> skipRichText(buf) // pageBlockFooter
            0xdb20b188.toInt() -> {} // pageBlockDivider
            0xce0d37b0.toInt() -> buf.string() // pageBlockAnchor: name
            0xe4e88011.toInt() -> { // pageBlockList
                skipVector(buf) { skipPageListItem(it) }
            }
            0x263d7c26.toInt() -> { skipRichText(buf); skipRichText(buf) } // pageBlockBlockquote: text + caption
            0x4f4456d3.toInt() -> { skipRichText(buf); skipRichText(buf) } // pageBlockPullquote: text + caption
            0x1759c560.toInt() -> { // pageBlockPhoto
                val pf = Fields.decode(buf)
                buf.int64() // photo_id
                skipPageCaption(buf) // caption
                if (pf.has(0)) buf.string() // url
                if (pf.has(0)) buf.int64() // webpage_id
            }
            0x7c8fe7b6.toInt() -> { // pageBlockVideo
                val vf = Fields.decode(buf)
                buf.int64() // video_id
                skipPageCaption(buf) // caption
            }
            0x39f23300.toInt() -> skipPageBlock(buf) // pageBlockCover: cover
            0xa8718dc5.toInt() -> { // pageBlockEmbed
                val ef = Fields.decode(buf)
                if (ef.has(1)) buf.string() // url
                if (ef.has(2)) buf.string() // html
                if (ef.has(4)) buf.int64() // poster_photo_id
                if (ef.has(5)) { buf.int32(); buf.int32() } // w h
                skipPageCaption(buf)
            }
            0xf259a80b.toInt() -> { // pageBlockEmbedPost
                buf.string() // url
                buf.int64() // webpage_id
                buf.int64() // author_photo_id
                buf.string() // author
                buf.int32() // date
                skipVector(buf) { skipPageBlock(it) } // blocks
                skipPageCaption(buf)
            }
            0x65a0fa4d.toInt() -> { // pageBlockCollage
                skipVector(buf) { skipPageBlock(it) }
                skipPageCaption(buf)
            }
            0x031f9590.toInt() -> { // pageBlockSlideshow
                skipVector(buf) { skipPageBlock(it) }
                skipPageCaption(buf)
            }
            0xef1751b5.toInt() -> skipBoxedType(buf) // pageBlockChannel: channel
            0x804361ea.toInt() -> { // pageBlockAudio
                buf.int64() // audio_id
                skipPageCaption(buf)
            }
            0x1e148390.toInt() -> skipRichText(buf) // pageBlockKicker
            0xbf4dea82.toInt() -> { // pageBlockTable
                val tf = Fields.decode(buf)
                skipRichText(buf) // title
                skipVector(buf) { skipPageTableRow(it) } // rows
            }
            0x9a8ae1e1.toInt() -> { // pageBlockOrderedList
                skipVector(buf) { skipPageListOrderedItem(it) }
            }
            0x76768bed.toInt() -> { // pageBlockDetails
                val df = Fields.decode(buf)
                skipVector(buf) { skipPageBlock(it) } // blocks
                skipRichText(buf) // title
            }
            0x16115a96.toInt() -> { // pageBlockRelatedArticles
                skipRichText(buf) // title
                skipVector(buf) { skipRelatedArticle(it) } // articles
            }
            0xa44f3ef6.toInt() -> { // pageBlockMap
                skipBoxedType(buf) // geo
                buf.int32() // zoom
                buf.int32(); buf.int32() // w h
                skipPageCaption(buf)
            }
            else -> Log.w(TAG, "Unknown PageBlock: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipRichText(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xdc3d824f.toInt() -> {} // textEmpty
            0x744694e0.toInt() -> buf.string() // textPlain
            0x6724abc4.toInt() -> skipRichText(buf) // textBold
            0xd912a59c.toInt() -> skipRichText(buf) // textItalic
            0xc12622c4.toInt() -> skipRichText(buf) // textUnderline
            0x9bf8bb95.toInt() -> skipRichText(buf) // textStrike
            0x6c3f19b9.toInt() -> skipRichText(buf) // textFixed
            0x3c2884c1.toInt() -> { skipRichText(buf); buf.string(); buf.int64() } // textUrl: text url webpage_id
            0xde5a0dd6.toInt() -> { skipRichText(buf); buf.string() } // textEmail: text email
            0x7e6260d7.toInt() -> { // textConcat
                buf.int32() // vector constructor
                val count = buf.int32()
                repeat(count) { skipRichText(buf) }
            }
            0xed6a8504.toInt() -> skipRichText(buf) // textSubscript
            0xc7fb5e01.toInt() -> skipRichText(buf) // textSuperscript
            0x034b8621.toInt() -> skipRichText(buf) // textMarked
            0x1ccb966a.toInt() -> { skipRichText(buf); buf.string() } // textPhone: text phone
            0x081ccf4f.toInt() -> { buf.int64(); buf.int32(); buf.int32() } // textImage: document_id w h
            0x35553762.toInt() -> { skipRichText(buf); buf.string() } // textAnchor: text name
            else -> Log.w(TAG, "Unknown RichText: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipPageCaption(buf: TlBuffer) {
        buf.int32() // constructor 0x6f747657
        skipRichText(buf) // text
        skipRichText(buf) // credit
    }

    private fun skipPageListItem(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xb92fb6cd.toInt() -> skipRichText(buf) // pageListItemText
            0x25e073fc.toInt() -> skipVector(buf) { skipPageBlock(it) } // pageListItemBlocks
        }
    }

    private fun skipPageListOrderedItem(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x5e068047.toInt() -> { buf.string(); skipRichText(buf) } // pageListOrderedItemText: num text
            0x98dd8936.toInt() -> { buf.string(); skipVector(buf) { skipPageBlock(it) } } // pageListOrderedItemBlocks
        }
    }

    private fun skipPageTableRow(buf: TlBuffer) {
        buf.int32() // constructor 0xe0c0c5e5
        skipVector(buf) { skipPageTableCell(it) }
    }

    private fun skipPageTableCell(buf: TlBuffer) {
        buf.int32() // constructor 0x34566b6a
        val f = Fields.decode(buf)
        if (f.has(7)) skipRichText(buf) // text
        if (f.has(1)) buf.int32() // colspan
        if (f.has(2)) buf.int32() // rowspan
    }

    private fun skipRelatedArticle(buf: TlBuffer) {
        buf.int32() // constructor 0xb68fbe2d
        val f = Fields.decode(buf)
        buf.string() // url
        buf.int64() // webpage_id
        if (f.has(0)) buf.string() // title
        if (f.has(1)) buf.string() // description
        if (f.has(2)) buf.int64() // photo_id
        if (f.has(3)) buf.string() // author
        if (f.has(4)) buf.int32() // published_date
    }

    // ---- MessageAction (for MessageService) ----

    fun skipMessageAction(buf: TlBuffer) {
        val typeId = buf.int32()
        skipMessageAction(buf, typeId)
    }

    fun skipMessageAction(buf: TlBuffer, typeId: Int) {
        when (typeId) {
            0xb6aef7b0.toInt() -> {} // messageActionEmpty
            0xbd47cbad.toInt() -> { // messageActionChatCreate
                buf.string() // title
                skipVector(buf) { it.int64() } // users
            }
            0xb5a1ce5a.toInt() -> buf.string() // messageActionChatEditTitle
            0x7fcb13a8.toInt() -> skipBoxedType(buf) // messageActionChatEditPhoto: photo
            0x95e3fbef.toInt() -> {} // messageActionChatDeletePhoto
            0x15cefd00.toInt() -> skipVector(buf) { it.int64() } // messageActionChatAddUser
            0xa43f30cc.toInt() -> buf.int64() // messageActionChatDeleteUser
            0x031224c3.toInt() -> buf.int64() // messageActionChatJoinedByLink: inviter_id
            0x95d2ac92.toInt() -> buf.string() // messageActionChannelCreate: title
            0xe1037f92.toInt() -> buf.int64() // messageActionChatMigrateTo: channel_id
            0xea3948e9.toInt() -> { buf.string(); buf.int64() } // messageActionChannelMigrateFrom
            0x94bd38ed.toInt() -> {} // messageActionPinMessage
            0x9fbab604.toInt() -> {} // messageActionHistoryClear
            0x80e11a7f.toInt() -> { // messageActionPhoneCall
                val flags = Fields.decode(buf)
                buf.int64() // call_id
                if (flags.has(0)) buf.int32() // reason (PhoneCallDiscardReason type id)
                if (flags.has(1)) buf.int32() // duration
            }
            0xf3f25f76.toInt() -> {} // messageActionContactSignUp
            0x3c134d7b.toInt() -> { // messageActionSetMessagesTTL
                val flags = Fields.decode(buf)
                buf.int32() // period
                if (flags.has(0)) buf.int64() // auto_setting_from
            }
            0x992a3a2b.toInt() -> { // messageActionChatCreate (new)
                buf.string() // title
                skipVector(buf) { it.int64() } // users (long)
            }
            0xc7848867.toInt() -> buf.int64() // messageActionChatDeleteUser (new - long)
            0x99d79498.toInt() -> buf.int64() // messageActionChatJoinedByLink (new - long)
            0x0d999256.toInt() -> { // messageActionTopicCreate
                val tFlags = Fields.decode(buf)
                buf.string() // title
                buf.int32() // icon_color
                if (tFlags.has(0)) buf.int64() // icon_emoji_id
            }
            0x7a0d7f42.toInt() -> { // messageActionGroupCall
                val gFlags = Fields.decode(buf)
                skipBoxedType(buf) // call
                if (gFlags.has(0)) buf.int32() // duration
            }
            else -> Log.w(TAG, "Unknown MessageAction: 0x${typeId.toUInt().toString(16)}")
        }
    }
}
