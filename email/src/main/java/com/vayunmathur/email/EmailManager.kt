package com.vayunmathur.email

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.MimeMultipart
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.BodyTerm

class EmailManager {

    sealed class AuthType {
        data class Password(val value: String) : AuthType()
        data class OAuth2(val accessToken: String) : AuthType()
    }

    private fun getSession(auth: AuthType, host: String): Session {
        val properties = Properties()
        properties["mail.store.protocol"] = "imaps"
        properties["mail.imaps.host"] = host
        properties["mail.imaps.port"] = "993"
        properties["mail.imaps.ssl.enable"] = "true"

        if (auth is AuthType.OAuth2) {
            properties["mail.imaps.auth.mechanisms"] = "XOAUTH2"
        }
        return Session.getInstance(properties)
    }

    private suspend fun <T> withStore(host: String, user: String, auth: AuthType, block: (Store) -> T): T = withContext(Dispatchers.IO) {
        val session = getSession(auth, host)
        val store = session.getStore("imaps")
        try {
            when (auth) {
                is AuthType.Password -> store.connect(host, user, auth.value)
                is AuthType.OAuth2 -> store.connect(host, user, auth.accessToken)
            }
            block(store)
        } finally {
            store.close()
        }
    }

    suspend fun fetchFolders(host: String, user: String, auth: AuthType): List<EmailFolder> = withStore(host, user, auth) { store ->
        val allFolders = mutableListOf<EmailFolder>()
        
        fun listRecursive(folder: Folder) {
            val children = folder.list()
            for (child in children) {
                allFolders.add(EmailFolder(
                    fullName = child.fullName,
                    name = child.name,
                    parentFullName = folder.fullName.takeIf { it.isNotEmpty() },
                    delimiter = child.separator.toString()
                ))
                if ((child.type and Folder.HOLDS_FOLDERS) != 0) {
                    try {
                        listRecursive(child)
                    } catch (e: Exception) {
                        // Some folders might not be listable
                    }
                }
            }
        }
        
        listRecursive(store.defaultFolder)
        allFolders
    }

    suspend fun fetchMessages(
        host: String,
        user: String,
        auth: AuthType,
        folderName: String,
        limit: Int,
        offset: Int,
        fetchBodies: Boolean = false
    ): List<EmailMessage> = withStore(host, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val totalMessages = folder.messageCount
            if (totalMessages == 0) return@withStore emptyList<EmailMessage>()

            val end = (totalMessages - offset).coerceAtLeast(1)
            val start = (end - limit + 1).coerceAtLeast(1)
            
            if (end < 1) return@withStore emptyList<EmailMessage>()

            val messages = folder.getMessages(start, end)
            val uidFolder = folder as? UIDFolder
            messages.reversedArray().map { msg ->
                val (body, isHtml) = if (fetchBodies) getTextFromMessage(msg) else null to false
                EmailMessage(
                    id = uidFolder?.getUID(msg) ?: -1L,
                    folderName = folderName,
                    subject = msg.subject ?: "(No Subject)",
                    from = msg.from?.firstOrNull()?.toString() ?: "Unknown",
                    date = msg.sentDate?.toString() ?: "",
                    body = body,
                    isHtml = isHtml
                )
            }
        } finally {
            folder.close(false)
        }
    }

    suspend fun fetchMessageDetail(host: String, user: String, auth: AuthType, folderName: String, uid: Long): EmailMessage = withStore(host, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val msg = if (folder is UIDFolder) {
                folder.getMessageByUID(uid)
            } else {
                null
            } ?: throw Exception("Message not found or folder does not support UID")

            val (body, isHtml) = getTextFromMessage(msg)
            EmailMessage(
                id = uid,
                folderName = folderName,
                subject = msg.subject ?: "(No Subject)",
                from = msg.from?.firstOrNull()?.toString() ?: "Unknown",
                date = msg.sentDate?.toString() ?: "",
                body = body,
                isHtml = isHtml
            )
        } finally {
            folder.close(false)
        }
    }

    suspend fun searchMessages(host: String, user: String, auth: AuthType, folderName: String, query: String): List<EmailMessage> = withStore(host, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val searchTerm = OrTerm(
                arrayOf(
                    SubjectTerm(query),
                    FromStringTerm(query),
                    BodyTerm(query)
                )
            )
            val messages = folder.search(searchTerm)
            val uidFolder = folder as? UIDFolder
            messages.reversedArray().map { msg ->
                EmailMessage(
                    id = uidFolder?.getUID(msg) ?: -1L,
                    folderName = folderName,
                    subject = msg.subject ?: "(No Subject)",
                    from = msg.from?.firstOrNull()?.toString() ?: "Unknown",
                    date = msg.sentDate?.toString() ?: ""
                )
            }
        } finally {
            folder.close(false)
        }
    }

    private fun getTextFromMessage(message: Message): Pair<String, Boolean> {
        return try {
            if (message.isMimeType("text/plain")) {
                message.content.toString() to false
            } else if (message.isMimeType("text/html")) {
                message.content.toString() to true
            } else if (message.isMimeType("multipart/*")) {
                getTextFromMimeMultipart(message.content as MimeMultipart)
            } else {
                "" to false
            }
        } catch (e: Exception) {
            "Error loading content: ${e.message}" to false
        }
    }

    private fun getTextFromMimeMultipart(mimeMultipart: MimeMultipart): Pair<String, Boolean> {
        var plainText = ""
        var htmlText = ""
        val count = mimeMultipart.count
        for (i in 0 until count) {
            val bodyPart = mimeMultipart.getBodyPart(i)
            if (bodyPart.isMimeType("text/plain")) {
                plainText += bodyPart.content
            } else if (bodyPart.isMimeType("text/html")) {
                htmlText += bodyPart.content
            } else if (bodyPart.content is MimeMultipart) {
                val (nestedText, nestedIsHtml) = getTextFromMimeMultipart(bodyPart.content as MimeMultipart)
                if (nestedIsHtml) htmlText += nestedText else plainText += nestedText
            }
        }
        return if (htmlText.isNotEmpty()) htmlText to true else plainText to false
    }
}
