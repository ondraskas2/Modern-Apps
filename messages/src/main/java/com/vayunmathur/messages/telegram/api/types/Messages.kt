package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class Message(
    val id: Int,
    val fromId: TlObject? = null,
    val peerId: TlObject,
    val date: Int,
    val message: String,
    val out: Boolean = false,
    val mediaTypeId: Int = 0,
    val media: TlObject? = null,
    val replyToTopId: Int = 0,
    val forumTopic: Boolean = false,
    val replyMarkup: ByteArray? = null,
    val entities: List<MessageEntity> = emptyList(),
    val fwdFrom: MessageFwdHeader? = null,
    val replyToMsgId: Int = 0,
    val editDate: Int = 0,
    val ttlPeriod: Int = 0,
    val views: Int = 0,
) : TlObject {
    override val typeId = 0x95ef6f2b.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): Message {
            val flags = Fields.decode(buf)
            val flags2 = Fields.decode(buf)
            val out = flags.has(1)
            val id = buf.int32()
            val fromId = if (flags.has(8)) decodePeer(buf) else null
            if (flags.has(29)) buf.int32() // from_boosts_applied
            if (flags2.has(12)) buf.string() // from_rank
            val peerId = decodePeer(buf)
            if (flags.has(28)) decodePeer(buf) // saved_peer_id
            val fwdFrom = if (flags.has(2)) {
                buf.int32() // fwd_from constructor id
                parseFwdHeader(buf)
            } else null
            if (flags.has(11)) buf.int64() // via_bot_id
            if (flags2.has(0)) buf.int64() // via_business_bot_id
            if (flags2.has(19)) decodePeer(buf) // guestchat_via_from
            var replyToTopId = 0
            var forumTopic = false
            var replyToMsgId = 0
            if (flags.has(3)) {
                val replyInfo = parseReplyTo(buf)
                replyToTopId = replyInfo.first
                forumTopic = replyInfo.second
                replyToMsgId = replyInfo.third
            }
            val date = buf.int32()
            val message = buf.string()
            val mediaTypeId = if (flags.has(9)) buf.peekId() else 0
            val media = if (flags.has(9)) decodeMedia(buf) else null
            if (flags.has(6)) TlSkip.skipReplyMarkup(buf) // reply_markup
            val entities = if (flags.has(7)) decodeEntities(buf) else emptyList()
            var views = 0
            if (flags.has(10)) { views = buf.int32(); buf.int32() } // views, forwards
            if (flags.has(23)) TlSkip.skipMessageReplies(buf) // replies
            val editDate = if (flags.has(15)) buf.int32() else 0
            if (flags.has(16)) buf.string() // post_author
            if (flags.has(17)) buf.int64() // grouped_id
            if (flags.has(20)) TlSkip.skipReactions(buf) // reactions
            if (flags.has(22)) TlSkip.skipVector(buf) { TlSkip.skipRestrictionReason(it) } // restriction_reason
            val ttlPeriod = if (flags.has(25)) buf.int32() else 0
            if (flags.has(30)) buf.int32() // quick_reply_shortcut_id
            if (flags2.has(2)) buf.int64() // effect
            if (flags2.has(3)) TlSkip.skipFactCheck(buf) // factcheck
            if (flags2.has(5)) buf.int32() // report_delivery_until_date
            if (flags2.has(6)) buf.int64() // paid_message_stars
            if (flags2.has(7)) TlSkip.skipBoxedType(buf) // suggested_post
            if (flags2.has(10)) buf.int32() // schedule_repeat_period
            if (flags2.has(11)) buf.string() // summary_from_language
            return Message(id, fromId, peerId, date, message, out, mediaTypeId, media, replyToTopId, forumTopic,
                null, entities, fwdFrom, replyToMsgId, editDate, ttlPeriod, views)
        }

        private fun parseFwdHeader(buf: TlBuffer): MessageFwdHeader {
            val flags = Fields.decode(buf)
            val fromId = if (flags.has(0)) decodePeer(buf) else null
            val fromName = if (flags.has(5)) buf.string() else ""
            val date = buf.int32()
            if (flags.has(6)) buf.int32() // channel_post
            if (flags.has(7)) buf.string() // post_author
            if (flags.has(8)) decodePeer(buf) // saved_from_peer
            if (flags.has(9)) buf.int32() // saved_from_msg_id
            if (flags.has(10)) buf.string() // psa_type
            if (flags.has(11)) decodePeer(buf) // saved_from_id
            if (flags.has(12)) buf.string() // saved_from_name
            if (flags.has(13)) buf.int32() // saved_date
            return MessageFwdHeader(fromId, fromName, date)
        }

        private fun parseReplyTo(buf: TlBuffer): Triple<Int, Boolean, Int> {
            val typeId = buf.int32()
            when (typeId) {
                0x0e5af939.toInt() -> { // messageReplyStoryHeader
                    decodePeer(buf)
                    buf.int32()
                    return Triple(0, false, 0)
                }
                0x1b97dd66.toInt() -> { // messageReplyHeader
                    val flags = Fields.decode(buf)
                    val forumTopic = flags.has(3)
                    val replyToMsgId = if (flags.has(4)) buf.int32() else 0
                    if (flags.has(0)) decodePeer(buf) // reply_to_peer_id
                    if (flags.has(5)) TlSkip.skipBoxedType(buf) // reply_from (MessageFwdHeader)
                    if (flags.has(8)) TlSkip.skipBoxedType(buf) // reply_media
                    val replyToTopId = if (flags.has(1)) buf.int32() else 0
                    if (flags.has(6)) buf.string() // quote_text
                    if (flags.has(7)) TlSkip.skipVector(buf) { TlSkip.skipMessageEntity(it) } // quote_entities
                    if (flags.has(10)) buf.int32() // quote_offset
                    if (flags.has(11)) buf.int32() // todo_item_id
                    if (flags.has(12)) buf.bytes() // poll_option
                    val topicId = if (forumTopic) {
                        if (replyToTopId != 0) replyToTopId else replyToMsgId
                    } else 0
                    val actualReplyMsgId = if (!forumTopic || (replyToTopId != 0 && replyToTopId != replyToMsgId)) {
                        replyToMsgId
                    } else 0
                    return Triple(topicId, forumTopic, actualReplyMsgId)
                }
            }
            return Triple(0, false, 0)
        }

        private fun decodeEntities(buf: TlBuffer): List<MessageEntity> {
            val entities = mutableListOf<MessageEntity>()
            buf.int32() // vector constructor
            val count = buf.int32()
            repeat(count) {
                val entityTypeId = buf.int32()
                when (entityTypeId) {
                    0xbb92ba95.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("unknown", o, l)) } // unknown
                    0xfa04579d.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("mention", o, l)) }
                    0x6cef8ac7.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("hashtag", o, l)) }
                    0x6f635b0d.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("botCommand", o, l)) }
                    0x6ed02538.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("url", o, l)) }
                    0x64e475c2.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("email", o, l)) }
                    0xbd610bc9.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("bold", o, l)) }
                    0x826f8b60.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("italic", o, l)) }
                    0x28a20571.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("code", o, l)) }
                    0x73924be0.toInt() -> { val o = buf.int32(); val l = buf.int32(); val lang = buf.string(); entities.add(MessageEntity("pre", o, l, language = lang)) }
                    0x76a6d327.toInt() -> { val o = buf.int32(); val l = buf.int32(); val url = buf.string(); entities.add(MessageEntity("textUrl", o, l, url = url)) }
                    0xdcb49f4e.toInt() -> { val o = buf.int32(); val l = buf.int32(); buf.int64(); entities.add(MessageEntity("mentionName", o, l)) } // old mentionName
                    0x9b69e34b.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("phone", o, l)) }
                    0x4c4e743f.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("cashtag", o, l)) }
                    0x9c4e7e8b.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("underline", o, l)) }
                    0xbf0693d4.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("strikethrough", o, l)) }
                    0x020df5d0.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("blockquote", o, l)) }
                    0x32ca960f.toInt() -> { val o = buf.int32(); val l = buf.int32(); entities.add(MessageEntity("spoiler", o, l)) }
                    0xc8cf05f8.toInt() -> { val o = buf.int32(); val l = buf.int32(); val docId = buf.int64(); entities.add(MessageEntity("customEmoji", o, l, documentId = docId)) }
                    0xf1ccaaac.toInt() -> { // collapsible blockquote
                        val o = buf.int32(); val l = buf.int32()
                        entities.add(MessageEntity("blockquote", o, l))
                    }
                    else -> TlSkip.skipMessageEntity(buf, entityTypeId)
                }
            }
            return entities
        }

        private fun decodeMedia(buf: TlBuffer): TlObject {
            val typeId = buf.int32()
            return when (typeId) {
                0x3ded6320.toInt() -> MessageMediaEmpty // messageMediaEmpty
                0xe216eb63.toInt() -> { // messageMediaPhoto
                    val flags = Fields.decode(buf)
                    if (flags.has(0)) TlSkip.skipBoxedType(buf)
                    if (flags.has(2)) buf.int32()
                    if (flags.has(4)) TlSkip.skipBoxedType(buf) // video
                    MessageMediaPhoto()
                }
                0x52d8ccd9.toInt() -> decodeMediaDocument(buf)
                0x56e0d474.toInt() -> decodeMediaGeo(buf) // messageMediaGeo
                0xb940c666.toInt() -> decodeMediaGeoLive(buf)
                0x70322949.toInt() -> { // messageMediaContact
                    val phone = buf.string()
                    val firstName = buf.string()
                    val lastName = buf.string()
                    val vcard = buf.string()
                    val userId = buf.int64()
                    MessageMediaContact(phone, firstName, lastName, vcard, userId)
                }
                0x9f84f49e.toInt() -> MessageMediaUnsupported
                0x2ec0533f.toInt() -> decodeMediaVenue(buf)
                0xfdb19008.toInt() -> { TlSkip.skipBoxedType(buf); MessageMediaUnsupported } // game
                0x08cbec07.toInt() -> { // messageMediaDice
                    val flags = Fields.decode(buf)
                    val value = buf.int32()
                    val emoticon = buf.string()
                    if (flags.has(0)) TlSkip.skipBoxedType(buf) // game_outcome
                    MessageMediaDice(value, emoticon)
                }
                0xddf10c3b.toInt() -> { // messageMediaWebPage
                    Fields.decode(buf)
                    decodeWebPage(buf)
                }
                0x773f4e66.toInt() -> decodeMediaPoll(buf)
                else -> {
                    MessageMediaUnsupported
                }
            }
        }

        private fun decodeMediaDocument(buf: TlBuffer): MessageMediaDocument {
            val flags = Fields.decode(buf)
            var mimeType = ""
            var fileName = ""
            var isSticker = false
            var stickerAlt = ""
            var isAnimated = false
            var isVoice = false
            var isRoundVideo = false
            var isVideo = false
            var duration = 0.0
            var width = 0
            var height = 0
            if (flags.has(0)) {
                val docTypeId = buf.int32()
                if (docTypeId == 0x8fd4c4d8.toInt()) { // document
                    val docFlags = Fields.decode(buf)
                    buf.int64() // id
                    buf.int64() // access_hash
                    buf.bytes() // file_reference
                    buf.int32() // date
                    mimeType = buf.string()
                    buf.int64() // size
                    if (docFlags.has(0)) TlSkip.skipVector(buf) { TlSkip.skipPhotoSizeBoxed(it) }
                    if (docFlags.has(1)) TlSkip.skipVector(buf) { TlSkip.skipVideoSizeBoxed(it) }
                    buf.int32() // dc_id
                    // parse attributes
                    buf.int32() // vector constructor
                    val attrCount = buf.int32()
                    repeat(attrCount) {
                        val attrId = buf.int32()
                        when (attrId) {
                            0x6c37c15c.toInt() -> { width = buf.int32(); height = buf.int32() } // imageSize
                            0x11b58939.toInt() -> { isAnimated = true } // animated
                            0x6319d612.toInt() -> { // sticker
                                isSticker = true
                                val stickerFlags = Fields.decode(buf)
                                stickerAlt = buf.string()
                                TlSkip.skipInputStickerSetBoxed(buf)
                                if (stickerFlags.has(0)) {
                                    buf.int32(); buf.int32(); buf.double(); buf.double(); buf.double()
                                }
                            }
                            0x17399fad.toInt(), 0x43c57c48.toInt() -> { // video
                                isVideo = true
                                val vFlags = Fields.decode(buf)
                                isRoundVideo = vFlags.has(0)
                                duration = buf.double()
                                width = buf.int32()
                                height = buf.int32()
                                if (vFlags.has(2)) buf.int32()
                                if (vFlags.has(4)) buf.double()
                                if (vFlags.has(5)) buf.string()
                            }
                            0x9852f9c6.toInt() -> { // audio
                                val aFlags = Fields.decode(buf)
                                isVoice = aFlags.has(10)
                                duration = buf.int32().toDouble()
                                if (aFlags.has(0)) buf.string()
                                if (aFlags.has(1)) buf.string()
                                if (aFlags.has(2)) buf.bytes()
                            }
                            0x15590068.toInt() -> { fileName = buf.string() } // filename
                            0x9801d2f7.toInt() -> {} // hasStickers
                            0xfd149899.toInt() -> { // customEmoji
                                Fields.decode(buf)
                                buf.string()
                                TlSkip.skipInputStickerSetBoxed(buf)
                            }
                            else -> {}
                        }
                    }
                } else if (docTypeId == 0x36f8c871.toInt()) {
                    buf.int64() // documentEmpty: id
                }
            }
            if (flags.has(5)) TlSkip.skipVectorBoxed(buf) // alt_documents (vector)
            if (flags.has(9)) TlSkip.skipBoxedType(buf) // video_cover
            if (flags.has(10)) buf.int32() // video_timestamp
            if (flags.has(2)) buf.int32() // ttl_seconds
            return MessageMediaDocument(mimeType, fileName, isSticker, stickerAlt, isAnimated, isVoice, isRoundVideo, isVideo, duration, width, height)
        }

        private fun decodeMediaGeo(buf: TlBuffer): MessageMediaGeo {
            val geoTypeId = buf.int32()
            return when (geoTypeId) {
                0x1117dd5f.toInt() -> MessageMediaGeo(0.0, 0.0) // geoPointEmpty
                0xb2a2f663.toInt() -> { // geoPoint
                    val f = Fields.decode(buf)
                    val long = buf.double()
                    val lat = buf.double()
                    buf.int64() // access_hash
                    if (f.has(0)) buf.int32() // accuracy_radius
                    MessageMediaGeo(lat, long)
                }
                else -> MessageMediaGeo(0.0, 0.0)
            }
        }

        private fun decodeMediaGeoLive(buf: TlBuffer): MessageMediaGeoLive {
            val flags = Fields.decode(buf)
            val geoTypeId = buf.int32()
            var lat = 0.0
            var long = 0.0
            when (geoTypeId) {
                0xb2a2f663.toInt() -> {
                    val f = Fields.decode(buf)
                    long = buf.double()
                    lat = buf.double()
                    buf.int64()
                    if (f.has(0)) buf.int32()
                }
                0x1117dd5f.toInt() -> {}
            }
            val heading = if (flags.has(0)) buf.int32() else 0
            val period = buf.int32()
            val proximityNotificationRadius = if (flags.has(1)) buf.int32() else 0
            return MessageMediaGeoLive(lat, long, heading, period, proximityNotificationRadius)
        }

        private fun decodeMediaVenue(buf: TlBuffer): MessageMediaVenue {
            val geoTypeId = buf.int32()
            var lat = 0.0
            var long = 0.0
            when (geoTypeId) {
                0xb2a2f663.toInt() -> {
                    val f = Fields.decode(buf)
                    long = buf.double()
                    lat = buf.double()
                    buf.int64()
                    if (f.has(0)) buf.int32()
                }
                0x1117dd5f.toInt() -> {}
            }
            val title = buf.string()
            val address = buf.string()
            val provider = buf.string()
            val venueId = buf.string()
            val venueType = buf.string()
            return MessageMediaVenue(lat, long, title, address, provider, venueId, venueType)
        }

        private fun decodeMediaPoll(buf: TlBuffer): MessageMediaPoll {
            val flags = Fields.decode(buf)
            val pollTypeId = buf.int32() // poll constructor
            val pollId = buf.int64()
            val pollFlags = Fields.decode(buf)
            val question = try {
                val textTypeId = buf.int32() // textWithEntities constructor
                val questionText = buf.string()
                TlSkip.skipVector(buf) { TlSkip.skipMessageEntity(it) } // entities
                questionText
            } catch (_: Exception) { "" }
            // Skip remaining poll data
            try {
                TlSkip.skipVector(buf) { // answers
                    buf.int32() // pollAnswer constructor
                    buf.int32() // textWithEntities
                    buf.string() // text
                    TlSkip.skipVector(it) { TlSkip.skipMessageEntity(it) }
                    buf.bytes() // option
                }
                if (pollFlags.has(4)) buf.int32() // close_period
                if (pollFlags.has(5)) buf.int32() // close_date
            } catch (_: Exception) {}
            // Skip poll results
            try { TlSkip.skipBoxedType(buf) } catch (_: Exception) {}
            // Skip attached_media (new in layer 225)
            try { if (flags.has(0)) TlSkip.skipBoxedType(buf) } catch (_: Exception) {}
            return MessageMediaPoll(question)
        }

        private fun decodeWebPage(buf: TlBuffer): TlObject {
            val typeId = buf.int32()
            return when (typeId) {
                0xe89c45b2.toInt() -> MessageMediaUnsupported // webPageEmpty
                0xb0d13e47.toInt() -> { // webPagePending
                    Fields.decode(buf)
                    buf.int64() // id
                    buf.string() // url
                    buf.int32() // date
                    MessageMediaWebPage(url = "")
                }
                0xe89c45b2.toInt() -> MessageMediaUnsupported // webPageEmpty
                else -> {
                    try {
                        val wpFlags = Fields.decode(buf)
                        buf.int64() // id
                        val url = buf.string()
                        val displayUrl = buf.string()
                        if (wpFlags.has(1)) buf.int32() // hash
                        if (wpFlags.has(2)) buf.string() // type
                        val siteName = if (wpFlags.has(3)) buf.string() else ""
                        val title = if (wpFlags.has(4)) buf.string() else ""
                        val description = if (wpFlags.has(5)) buf.string() else ""
                        // Skip rest of web page (photo, embed, etc.)
                        if (wpFlags.has(6)) TlSkip.skipBoxedType(buf) // photo
                        if (wpFlags.has(7)) buf.string() // embed_url
                        if (wpFlags.has(8)) buf.string() // embed_type
                        if (wpFlags.has(9)) buf.int32() // embed_width
                        if (wpFlags.has(10)) buf.int32() // embed_height
                        if (wpFlags.has(11)) buf.int32() // duration
                        if (wpFlags.has(12)) buf.string() // author
                        if (wpFlags.has(13)) TlSkip.skipBoxedType(buf) // document
                        if (wpFlags.has(14)) TlSkip.skipVector(buf) { TlSkip.skipBoxedType(it) } // cached_page (Page)
                        if (wpFlags.has(15)) TlSkip.skipVector(buf) { TlSkip.skipBoxedType(it) } // attributes
                        MessageMediaWebPage(url, title, description)
                    } catch (_: Exception) {
                        MessageMediaWebPage()
                    }
                }
            }
        }
    }
}

object MessageEmpty : TlObject {
    override val typeId = 0x90a6ca84.toInt()
    override fun encode(buf: TlBuffer) {}
    fun decode(buf: TlBuffer): MessageEmpty {
        val flags = Fields.decode(buf)
        buf.int32() // id
        if (flags.has(0)) decodePeer(buf) // peer_id
        return MessageEmpty
    }
}

data class MessageService(
    val id: Int,
    val peerId: TlObject,
    val date: Int,
    val out: Boolean,
    val fromId: TlObject? = null,
    val action: MessageAction? = null,
) : TlObject {
    override val typeId = 0x7a800e0a.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer): MessageService {
            val flags = Fields.decode(buf)
            val out = flags.has(1)
            val id = buf.int32()
            val fromId = if (flags.has(8)) decodePeer(buf) else null
            val peerId = decodePeer(buf)
            if (flags.has(28)) decodePeer(buf) // saved_peer_id
            if (flags.has(3)) TlSkip.skipReplyTo(buf) // reply_to
            val date = buf.int32()
            val action = decodeMessageAction(buf)
            if (flags.has(20)) TlSkip.skipReactions(buf) // reactions
            if (flags.has(25)) buf.int32() // ttl_period
            return MessageService(id, peerId, date, out, fromId, action)
        }

        private fun decodeMessageAction(buf: TlBuffer): MessageAction {
            val actionTypeId = buf.int32()
            return when (actionTypeId) {
                0xb5a1ce5a.toInt() -> { // chatEditTitle
                    MessageActionChatEditTitle(buf.string())
                }
                0x15cefd00.toInt() -> { // chatAddUser
                    buf.int32() // vector
                    val count = buf.int32()
                    val users = (0 until count).map { buf.int64() }
                    MessageActionChatAddUser(users)
                }
                0xa43f30cc.toInt() -> { // chatDeleteUser (old - int)
                    MessageActionChatDeleteUser(buf.int32().toLong())
                }
                0xc7848867.toInt() -> { // chatDeleteUser (new - long)
                    MessageActionChatDeleteUser(buf.int64())
                }
                0x7fcb13a8.toInt() -> { // chatEditPhoto
                    TlSkip.skipBoxedType(buf) // photo
                    MessageActionChatEditPhoto
                }
                0x95e3fbef.toInt() -> { // chatDeletePhoto
                    MessageActionChatDeletePhoto
                }
                0xbd47cbad.toInt() -> { // chatCreate (old)
                    val title = buf.string()
                    buf.int32() // vector
                    val count = buf.int32()
                    val users = (0 until count).map { buf.int32().toLong() }
                    MessageActionChatCreate(title, users)
                }
                0x992a3a2b.toInt() -> { // chatCreate (new, with long user ids)
                    val title = buf.string()
                    buf.int32() // vector
                    val count = buf.int32()
                    val users = (0 until count).map { buf.int64() }
                    MessageActionChatCreate(title, users)
                }
                0xe1037f92.toInt() -> { // chatMigrateTo
                    MessageActionChatMigrateTo(buf.int64())
                }
                0x3c134d7b.toInt() -> { // setMessagesTTL
                    val f = Fields.decode(buf)
                    val period = buf.int32()
                    if (f.has(0)) buf.int64() // auto_setting_from
                    MessageActionSetMessagesTTL(period)
                }
                0x80e11a7f.toInt() -> { // phoneCall
                    val pFlags = Fields.decode(buf)
                    buf.int64() // call_id
                    val reason = if (pFlags.has(0)) buf.int32() else 0
                    val duration = if (pFlags.has(1)) buf.int32() else 0
                    val video = pFlags.has(2)
                    MessageActionPhoneCall(video, duration, reason)
                }
                0x031224c3.toInt() -> { // chatJoinedByLink (old)
                    MessageActionChatJoinedByLink(buf.int32().toLong())
                }
                0x99d79498.toInt() -> { // chatJoinedByLink (new)
                    MessageActionChatJoinedByLink(buf.int64())
                }
                0x94bd38ed.toInt() -> { // pinMessage
                    MessageActionPinMessage
                }
                0x95d2ac92.toInt() -> { // channelCreate
                    buf.string() // title
                    MessageActionChannelCreate
                }
                0x0d999256.toInt() -> { // topicCreate
                    val tFlags = Fields.decode(buf)
                    val title = buf.string()
                    buf.int32() // icon_color
                    if (tFlags.has(0)) buf.int64() // icon_emoji_id
                    MessageActionTopicCreate(title)
                }
                0x7a0d7f42.toInt() -> { // groupCall
                    val gFlags = Fields.decode(buf)
                    TlSkip.skipBoxedType(buf) // call
                    val duration = if (gFlags.has(0)) buf.int32() else 0
                    MessageActionGroupCall(duration)
                }
                else -> {
                    TlSkip.skipMessageAction(buf, actionTypeId)
                    MessageActionUnknown(actionTypeId)
                }
            }
        }
    }
}
