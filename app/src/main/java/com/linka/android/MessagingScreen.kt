package com.linka.android

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.style.TextOverflow
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.linka.android.account.LinkaAccountClient
import com.linka.android.account.LinkaAccountProfile
import com.linka.android.core.identity.LocalDeviceIdentity
import com.linka.android.messaging.AndroidMessagingKeyManager
import com.linka.android.messaging.MessagingDevicePublicKey
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class ConversationSummary(
    val conversationId: String,
    val kind: String,
    val title: String,
    val memberCount: Int,
    val lastMessageAt: String?
)

private data class ConversationAttachment(
    val attachmentId: String,
    val filename: String,
    val mediaType: String,
    val sizeBytes: Int
)

private data class PendingAttachmentSave(
    val bytes: ByteArray,
    val filename: String,
    val mediaType: String
)

private data class ConversationMessage(
    val id: String,
    val sender: String,
    val plaintext: String,
    val sentAt: String,
    val receiptDevices: Int? = null,
    val deliveredDevices: Int? = null,
    val readDevices: Int? = null,
    val decryptedSuccessfully: Boolean = false,
    val attachments: List<ConversationAttachment> = emptyList()
)

private const val MAX_ANDROID_ATTACHMENT_BYTES = 4 * 1024 * 1024

private fun attachmentDisplayName(context: android.content.Context, uri: Uri): String {
    val candidate = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }.orEmpty()
    val safe = candidate.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
        .trim()
        .take(180)
    return safe.ifBlank { "linka-attachment" }
}

private fun attachmentMimeType(context: android.content.Context, uri: Uri): String =
    context.contentResolver.getType(uri)
        ?.takeIf { it.matches(Regex("^[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+$")) }
        ?: "application/octet-stream"

private fun readEncryptedAttachmentSource(context: android.content.Context, uri: Uri): ByteArray {
    val output = ByteArrayOutputStream()
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_ANDROID_ATTACHMENT_BYTES) { "Choose a file no larger than 4 MiB." }
            output.write(buffer, 0, count)
        }
    } ?: throw IllegalArgumentException("LINKA could not open the selected file.")
    return output.toByteArray().also {
        require(it.isNotEmpty()) { "Choose a file that is not empty." }
    }
}

@Composable
fun LinkaMessagingScreen(
    accountClient: LinkaAccountClient,
    identity: LocalDeviceIdentity,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyManager = remember(context, identity.deviceId) {
        AndroidMessagingKeyManager(context, identity)
    }
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<LinkaAccountProfile?>(null) }
    var conversations by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }
    var selectedConversationId by remember { mutableStateOf<String?>(null) }
    var activeDevices by remember { mutableStateOf<List<MessagingDevicePublicKey>>(emptyList()) }
    var messages by remember { mutableStateOf<List<ConversationMessage>>(emptyList()) }
    var participantIds by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var selectedAttachmentUri by remember { mutableStateOf<Uri?>(null) }
    var selectedAttachmentName by remember { mutableStateOf<String?>(null) }
    var selectedAttachmentSize by remember { mutableStateOf<Int?>(null) }
    var pendingAttachmentSave by remember { mutableStateOf<PendingAttachmentSave?>(null) }
    var status by remember { mutableStateOf("Sign in to use encrypted LINKA messaging.") }
    var loading by remember { mutableStateOf(false) }

    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            runCatching {
                val bytes = readEncryptedAttachmentSource(context, uri)
                selectedAttachmentUri = uri
                selectedAttachmentName = attachmentDisplayName(context, uri)
                selectedAttachmentSize = bytes.size
                status = "Encrypted attachment selected: ${selectedAttachmentName} (${bytes.size} bytes)."
            }.onFailure { error ->
                selectedAttachmentUri = null
                selectedAttachmentName = null
                selectedAttachmentSize = null
                status = error.message ?: "Could not read the selected attachment."
            }
        }
    }

    val attachmentSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val pending = pendingAttachmentSave
        if (destination != null && pending != null) {
            scope.launch {
                loading = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            output.write(pending.bytes)
                            output.flush()
                        } ?: throw IllegalStateException("Android could not create the selected download file.")
                    }
                    status = "Decrypted attachment saved as ${pending.filename}."
                }.onFailure { error ->
                    status = error.message ?: "Could not save the decrypted attachment."
                }
                pendingAttachmentSave = null
                loading = false
            }
        } else {
            pendingAttachmentSave = null
        }
    }

    suspend fun loadConversation(conversationId: String, currentProfile: LinkaAccountProfile) {
        val directory = accountClient.listConversationEncryptionDevices(conversationId)
        activeDevices = directory
        val missing = directory.count {
            it.algorithm != AndroidMessagingKeyManager.ALGORITHM ||
                it.keyVersion == null ||
                it.publicKeyBase64.isNullOrBlank()
        }
        val history = accountClient.listInternalMessages(conversationId)
        val decoded = (0 until history.length()).mapNotNull { index ->
            val item = history.optJSONObject(index) ?: return@mapNotNull null
            val messageId = item.optString("messageId")
            val senderAccountId = item.optString("senderAccountId")
            val version = item.optString("encryptionVersion")
            val envelope = item.optJSONObject("keyEnvelope")
            val (plaintext, decryptedSuccessfully) = when {
                version != AndroidMessagingKeyManager.ENCRYPTION_VERSION ->
                    "[This message uses an unsupported or legacy encryption version]" to false
                envelope == null ->
                    "[No encrypted key envelope is available for this Android device]" to false
                else -> runCatching {
                    keyManager.decryptMessage(
                        ciphertextBase64 = item.getString("ciphertext"),
                        encryptionVersion = version,
                        conversationId = conversationId,
                        senderAccountId = senderAccountId,
                        keyEnvelope = envelope
                    ).let { it to true }
                }.getOrElse {
                    "[Unable to decrypt this message on this Android device]" to false
                }
            }
            val attachmentManifest = item.optJSONArray("attachmentManifest") ?: JSONArray()
            val attachments = (0 until attachmentManifest.length()).mapNotNull { attachmentIndex ->
                val attachment = attachmentManifest.optJSONObject(attachmentIndex)
                    ?: return@mapNotNull null
                val attachmentId = attachment.optString("attachmentId")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: return@mapNotNull null
                ConversationAttachment(
                    attachmentId = attachmentId,
                    filename = "Encrypted attachment",
                    mediaType = "application/octet-stream",
                    sizeBytes = 0
                )
            }
            ConversationMessage(
                id = messageId,
                sender = if (senderAccountId == currentProfile.accountId) "You" else
                    directory.firstOrNull { it.accountId == senderAccountId }?.accountId?.take(8)
                        ?.let { "LINKA member $it" } ?: "LINKA member",
                plaintext = plaintext,
                sentAt = item.optString("sentAt"),
                receiptDevices = item.optJSONObject("receiptSummary")?.optInt("recipientDevices"),
                deliveredDevices = item.optJSONObject("receiptSummary")?.optInt("deliveredDevices"),
                readDevices = item.optJSONObject("receiptSummary")?.optInt("readDevices"),
                decryptedSuccessfully = decryptedSuccessfully,
                attachments = attachments
            )
        }.asReversed()
        messages = decoded
        val incoming = decoded.filter { it.sender != "You" }.takeLast(100)
        val incomingIds = incoming.map { it.id }.filter { it.isNotBlank() }
        if (incomingIds.isNotEmpty()) {
            accountClient.acknowledgeInternalMessages(
                conversationId = conversationId,
                messageIds = incomingIds,
                state = "delivered"
            )
        }
        val readableIds = incoming
            .filter { it.decryptedSuccessfully }
            .map { it.id }
            .filter { it.isNotBlank() }
        if (readableIds.isNotEmpty()) {
            accountClient.acknowledgeInternalMessages(
                conversationId = conversationId,
                messageIds = readableIds,
                state = "read"
            )
        }
        status = if (missing == 0 && directory.isNotEmpty()) {
            "End-to-end encryption ready on ${directory.size} active device(s). Private keys remain on each device."
        } else {
            "$missing active device(s) must sign in and register an ECDH key before sending."
        }
    }

    suspend fun refreshConversationList(preferredId: String? = selectedConversationId) {
        val currentProfile = profile ?: return
        val response = accountClient.listConversations()
        val next = (0 until response.length()).mapNotNull { index ->
            val item = response.optJSONObject(index) ?: return@mapNotNull null
            val members = item.optJSONArray("members")
            val names = (0 until (members?.length() ?: 0)).mapNotNull { memberIndex ->
                members?.optJSONObject(memberIndex)?.optString("displayName")
                    ?.takeIf { !it.isNullOrBlank() }
            }
            ConversationSummary(
                conversationId = item.optString("conversationId"),
                kind = item.optString("kind"),
                title = names.joinToString(", ").ifBlank { "LINKA conversation" },
                memberCount = item.optInt("memberCount", names.size),
                lastMessageAt = item.optString("lastMessageAt").takeIf { it.isNotBlank() && it != "null" }
            )
        }
        conversations = next
        val chosen = preferredId?.takeIf { id -> next.any { it.conversationId == id } }
            ?: next.firstOrNull()?.conversationId
        selectedConversationId = chosen
        if (chosen == null) {
            activeDevices = emptyList()
            messages = emptyList()
            status = "No conversations yet. Start a direct or group conversation using LINKA account IDs."
        } else {
            loadConversation(chosen, currentProfile)
        }
    }

    LaunchedEffect(profile?.accountId, profile?.deviceId) {
        val currentProfile = profile
        if (currentProfile == null) {
            conversations = emptyList()
            selectedConversationId = null
            activeDevices = emptyList()
            messages = emptyList()
            status = "Sign in to use encrypted LINKA messaging."
        } else {
            loading = true
            status = "Registering this device's encryption key…"
            runCatching {
                accountClient.registerMessagingEncryptionKey(keyManager.createRegistrationBody())
                refreshConversationList(null)
            }.onFailure { error ->
                status = error.message ?: "This device could not register its E2EE key."
            }
            loading = false
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "LINKA MESSAGES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Private device-to-device conversations.",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Message content is encrypted on this device. Every active conversation device must register its own key before sending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            LinkaAccountPanel(
                accountClient = accountClient,
                onProfileChanged = { profile = it }
            )
        }

        if (profile != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("START A CONVERSATION", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = participantIds,
                            onValueChange = { participantIds = it.take(1800) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Other LINKA account ID(s)") },
                            placeholder = { Text("Account UUID; use commas for a group") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            minLines = 1,
                            maxLines = 3
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    loading = true
                                    runCatching {
                                        val ids = participantIds.split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .distinct()
                                        require(ids.size in 1..49) {
                                            "Enter between 1 and 49 other LINKA account IDs."
                                        }
                                        val created = accountClient.createConversation(ids)
                                        val id = created.optString("conversationId")
                                        require(id.isNotBlank()) { "LINKA returned an invalid conversation." }
                                        participantIds = ""
                                        refreshConversationList(id)
                                        status = "Conversation ready. Register an encryption key on every active device."
                                    }.onFailure { error ->
                                        status = error.message ?: "Could not create this conversation."
                                    }
                                    loading = false
                                }
                            },
                            enabled = !loading && participantIds.isNotBlank()
                        ) {
                            Text("Create / open conversation")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("YOUR CONVERSATIONS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        loading = true
                                        runCatching { refreshConversationList() }
                                            .onFailure { status = it.message ?: "Unable to refresh conversations." }
                                        loading = false
                                    }
                                },
                                enabled = !loading
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Spacer(Modifier.size(5.dp))
                                Text("Refresh")
                            }
                        }
                    }
                }
            }

            if (conversations.isEmpty()) {
                item {
                    Text(
                        "No conversations yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(conversations, key = { it.conversationId }) { conversation ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selectedConversationId = conversation.conversationId
                        scope.launch {
                            loading = true
                            runCatching { loadConversation(conversation.conversationId, profile!!) }
                                .onFailure { status = it.message ?: "Unable to load this conversation." }
                            loading = false
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedConversationId == conversation.conversationId)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(conversation.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (conversation.kind == "group") "Group" else "Direct") +
                                " · ${conversation.memberCount} member(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (selectedConversationId != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                Text("ENCRYPTED MESSAGE HISTORY", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (activeDevices.any {
                                    it.algorithm != AndroidMessagingKeyManager.ALGORITHM ||
                                        it.keyVersion == null || it.publicKeyBase64.isNullOrBlank()
                                }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (messages.isEmpty()) {
                    item {
                        Text(
                            "No messages in this conversation yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(messages, key = { it.id }) { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(message.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(message.plaintext, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                message.sentAt,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (message.sender == "You" && message.receiptDevices != null &&
                                message.receiptDevices > 0) {
                                Text(
                                    "Delivered ${message.deliveredDevices ?: 0}/${message.receiptDevices} · " +
                                        "Read ${message.readDevices ?: 0}/${message.receiptDevices}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            message.attachments.forEach { attachment ->
                                OutlinedButton(
                                    onClick = {
                                        val conversationId = selectedConversationId ?: return@OutlinedButton
                                        scope.launch {
                                            loading = true
                                            runCatching {
                                                val remote = accountClient.downloadEncryptedAttachment(
                                                    conversationId = conversationId,
                                                    attachmentId = attachment.attachmentId
                                                )
                                                val envelope = remote.optJSONObject("keyEnvelope")
                                                    ?: throw IllegalStateException("This device has no attachment key envelope.")
                                                val bytes = keyManager.decryptAttachment(
                                                    ciphertextBase64 = remote.getString("ciphertextBase64"),
                                                    encryptionVersion = remote.getString("encryptionVersion"),
                                                    conversationId = conversationId,
                                                    keyEnvelope = envelope
                                                )
                                                val filename = "LINKA-attachment-${attachment.attachmentId.take(8)}.bin"
                                                pendingAttachmentSave = PendingAttachmentSave(
                                                    bytes = bytes,
                                                    filename = filename,
                                                    mediaType = "application/octet-stream"
                                                )
                                                attachmentSaver.launch(filename)
                                                status = "Attachment decrypted on this device. Choose where to save it."
                                            }.onFailure { error ->
                                                pendingAttachmentSave = null
                                                status = error.message ?: "Could not download and decrypt this attachment."
                                            }
                                            loading = false
                                        }
                                    },
                                    enabled = !loading
                                ) {
                                    Text("Download encrypted attachment")
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it.take(6000) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Write an encrypted message") },
                                minLines = 2,
                                maxLines = 5
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                                    enabled = !loading
                                ) {
                                    Text("Attach file (4 MiB max)")
                                }
                                if (selectedAttachmentName != null) {
                                    Text(
                                        "${selectedAttachmentName} · ${selectedAttachmentSize ?: 0} bytes",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            selectedAttachmentUri = null
                                            selectedAttachmentName = null
                                            selectedAttachmentSize = null
                                        },
                                        enabled = !loading
                                    ) {
                                        Text("Remove")
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    val conversationId = selectedConversationId ?: return@Button
                                    val currentProfile = profile ?: return@Button
                                    val attachmentUri = selectedAttachmentUri
                                    scope.launch {
                                        loading = true
                                        var uploadedAttachmentId: String? = null
                                        try {
                                            val directory = accountClient.listConversationEncryptionDevices(conversationId)
                                            val missing = directory.filter {
                                                it.algorithm != AndroidMessagingKeyManager.ALGORITHM ||
                                                    it.keyVersion == null ||
                                                    it.publicKeyBase64.isNullOrBlank()
                                            }
                                            require(missing.isEmpty()) {
                                                "Sending is blocked until all ${missing.size} device(s) register ECDH keys."
                                            }
                                            val caption = draft.ifBlank { "Shared an encrypted attachment" }
                                            if (attachmentUri != null) {
                                                val sourceBytes = withContext(Dispatchers.IO) {
                                                    readEncryptedAttachmentSource(context, attachmentUri)
                                                }
                                                val encryptedAttachment = keyManager.encryptAttachmentForDevices(
                                                    plaintext = sourceBytes,
                                                    conversationId = conversationId,
                                                    devices = directory
                                                )
                                                val uploaded = accountClient.uploadEncryptedAttachment(
                                                    conversationId = conversationId,
                                                    body = encryptedAttachment
                                                )
                                                uploadedAttachmentId = uploaded.optString("attachmentId")
                                                    .takeIf { it.isNotBlank() && it != "null" }
                                                    ?: throw IllegalStateException("LINKA did not return an attachment ID.")
                                            }
                                            val payload = keyManager.encryptForDevices(
                                                plaintext = caption,
                                                conversationId = conversationId,
                                                senderAccountId = currentProfile.accountId,
                                                devices = directory
                                            )
                                            if (uploadedAttachmentId != null) {
                                                payload.put(
                                                    "attachmentManifest",
                                                    JSONArray().put(JSONObject().put("attachmentId", uploadedAttachmentId))
                                                )
                                            }
                                            accountClient.sendEncryptedMessage(conversationId, payload)
                                            uploadedAttachmentId = null
                                            draft = ""
                                            selectedAttachmentUri = null
                                            selectedAttachmentName = null
                                            selectedAttachmentSize = null
                                            loadConversation(conversationId, currentProfile)
                                            status = if (uploadedAttachmentId == null) {
                                                "Encrypted message sent to ${directory.size} active device(s)."
                                            } else {
                                                "Encrypted attachment and message sent to ${directory.size} active device(s)."
                                            }
                                        } catch (error: Exception) {
                                            val failedUploadId = uploadedAttachmentId
                                            if (failedUploadId != null) {
                                                runCatching {
                                                    accountClient.deleteUnsentEncryptedAttachment(
                                                        conversationId = conversationId,
                                                        attachmentId = failedUploadId
                                                    )
                                                }
                                            }
                                            status = error.message ?: "Could not send the encrypted message."
                                        } finally {
                                            loading = false
                                        }
                                    }
                                },
                                enabled = !loading && (draft.isNotBlank() || selectedAttachmentUri != null) &&
                                    activeDevices.isNotEmpty() && activeDevices.none {
                                        it.algorithm != AndroidMessagingKeyManager.ALGORITHM ||
                                            it.keyVersion == null || it.publicKeyBase64.isNullOrBlank()
                                    },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                else {
                                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                                    Spacer(Modifier.size(6.dp))
                                    Text(if (selectedAttachmentUri != null) "Encrypt and send attachment" else "Encrypt and send")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Attachments are encrypted on this device and can be decrypted only by active devices with matching LINKA keys. File names are not shared with the server. Key transparency and account key recovery still require separate security review. Android 12 or later is required for hardware-backed ECDH key agreement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
