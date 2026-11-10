package com.linka.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linka.android.account.LinkaAccountClient
import com.linka.android.account.LinkaAccountDevice
import com.linka.android.account.LinkaAccountException
import com.linka.android.account.LinkaAccountProfile
import kotlinx.coroutines.launch

@Composable
fun LinkaAccountPanel(
    accountClient: LinkaAccountClient,
    modifier: Modifier = Modifier,
    onProfileChanged: (LinkaAccountProfile?) -> Unit = {}
) {
    var serverUrl by rememberSaveable { mutableStateOf(accountClient.serverUrl()) }
    var email by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf<LinkaAccountProfile?>(null) }
    var devices by remember { mutableStateOf<List<LinkaAccountDevice>>(emptyList()) }
    var deviceToRevoke by remember { mutableStateOf<LinkaAccountDevice?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var createMode by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accountClient) {
        if (accountClient.hasLocalSession()) {
            loading = true
            runCatching { accountClient.restoreSession() }
                .onSuccess { restored ->
                    profile = restored
                    onProfileChanged(restored)
                    email = restored?.email.orEmpty()
                    if (restored == null) message = "Your saved account session has expired. Sign in again."
                }
                .onFailure { error ->
                    message = error.message ?: "Unable to restore the LINKA account session."
                }
            loading = false
        }
    }

    LaunchedEffect(profile) {
        if (profile == null) {
            devices = emptyList()
        } else {
            loading = true
            runCatching { accountClient.listAccountDevices() }
                .onSuccess { devices = it }
                .onFailure { error ->
                    message = error.message ?: "Unable to load LINKA devices."
                }
            loading = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "LINKA ACCOUNT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your number and account, across your LINKA devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = profile == null && !loading,
                singleLine = true,
                label = { Text("LINKA API address") },
                placeholder = { Text("https://api.your-linka-domain") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    Text("The default 10.0.2.2 address is for the Android emulator. Use HTTPS for a real phone.")
                }
            )

            if (profile == null) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    singleLine = true,
                    label = { Text("Email address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                if (createMode) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(100) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading,
                        singleLine = true,
                        label = { Text("Display name") }
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                message = null
                                runCatching {
                                    if (createMode) {
                                        accountClient.register(email, password, displayName, serverUrl)
                                    } else {
                                        accountClient.login(email, password, serverUrl)
                                    }
                                }.onSuccess { result ->
                                    profile = result
                                    onProfileChanged(result)
                                    password = ""
                                    message = if (result.emailVerified) {
                                        "Your LINKA account is signed in."
                                    } else {
                                        "Signed in. Email verification is not enabled yet."
                                    }
                                }.onFailure { error ->
                                    message = error.message ?: "LINKA account request failed."
                                }
                                loading = false
                            }
                        },
                        enabled = !loading &&
                            email.isNotBlank() &&
                            password.isNotBlank() &&
                            serverUrl.isNotBlank() &&
                            (!createMode || displayName.isNotBlank())
                    ) {
                        if (loading) CircularProgressIndicator()
                        else Text(if (createMode) "Create account" else "Sign in")
                    }
                    OutlinedButton(
                        onClick = {
                            createMode = !createMode
                            message = null
                        },
                        enabled = !loading
                    ) {
                        Text(if (createMode) "I have an account" else "Create account")
                    }
                }
            } else {
                Text(profile!!.displayName.ifBlank { profile!!.email }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(profile!!.email, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (profile!!.emailVerified) "Email verified" else "Email verification is not enabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Device: " + profile!!.deviceId.take(8).uppercase(), style = MaterialTheme.typography.labelSmall)
                Text("Session expires: " + profile!!.expiresAt, style = MaterialTheme.typography.labelSmall)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Your devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                scope.launch {
                                    loading = true
                                    runCatching { accountClient.listAccountDevices() }
                                        .onSuccess { devices = it }
                                        .onFailure { error ->
                                            message = error.message ?: "Unable to refresh LINKA devices."
                                        }
                                    loading = false
                                }
                            },
                            enabled = !loading
                        ) { Text("Refresh") }
                    }
                    if (devices.isEmpty()) {
                        Text(
                            "No registered devices were returned. Refresh to check your account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    devices.forEach { device ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    device.deviceName?.takeIf { it.isNotBlank() }
                                        ?: device.platform.replaceFirstChar { it.uppercase() } + " device",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "ID: " + device.deviceId.take(8).uppercase() +
                                        if (device.current) " • This device" else "",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    "Status: " + device.status +
                                        " • Active sessions: " + device.activeSessionCount,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Last seen: " + (device.lastSeenAt ?: "Not recorded"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!device.current && device.status == "active") {
                                    TextButton(
                                        onClick = { deviceToRevoke = device },
                                        enabled = !loading
                                    ) { Text("Revoke device") }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            message = null
                            runCatching { accountClient.logout() }
                                .onSuccess {
                                    profile = null
                                    onProfileChanged(null)
                                    password = ""
                                    message = "You are signed out of your LINKA account."
                                }
                                .onFailure { error -> message = error.message ?: "Unable to sign out." }
                            loading = false
                        }
                    },
                    enabled = !loading
                ) {
                    Text("Sign out")
                }
            }

            if (loading) CircularProgressIndicator()
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            deviceToRevoke?.let { selected ->
                AlertDialog(
                    onDismissRequest = { deviceToRevoke = null },
                    title = { Text("Revoke this device?") },
                    text = {
                        Text(
                            "This signs out " +
                                (selected.deviceName?.takeIf { it.isNotBlank() } ?: selected.platform + " device") +
                                ", revokes its active encryption key and prevents it receiving new LINKA messages."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                deviceToRevoke = null
                                scope.launch {
                                    loading = true
                                    message = null
                                    runCatching { accountClient.revokeAccountDevice(selected.deviceId) }
                                        .onSuccess { result ->
                                            message = if (result.optBoolean("alreadyRevoked")) {
                                                "This device was already revoked."
                                            } else {
                                                "Device revoked. Its active sessions and messaging key are disabled."
                                            }
                                            runCatching { accountClient.listAccountDevices() }
                                                .onSuccess { devices = it }
                                        }
                                        .onFailure { error ->
                                            message = error.message ?: "Unable to revoke this device."
                                        }
                                    loading = false
                                }
                            },
                            enabled = !loading
                        ) { Text("Revoke") }
                    },
                    dismissButton = {
                        TextButton(onClick = { deviceToRevoke = null }) { Text("Cancel") }
                    }
                )
            }
            TextButton(
                onClick = { message = "Password recovery is not available until LINKA email delivery is configured." },
                enabled = profile == null && !loading
            ) {
                Text("Forgot password?")
            }
        }
    }
}
