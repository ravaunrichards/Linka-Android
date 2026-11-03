package com.linka.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.linka.android.core.identity.LocalDeviceIdentity
import com.linka.android.core.call.CallPhase
import com.linka.android.core.call.NearbyCallController
import com.linka.android.core.call.NearbyCallUiState
import com.linka.android.core.audio.VoiceSessionState
import com.linka.android.core.nearby.NearbyDirectLink
import com.linka.android.core.nearby.NearbyLinkState
import com.linka.android.core.nearby.NearbyPeer

class MainActivity : ComponentActivity() {
    private lateinit var identity: LocalDeviceIdentity
    private lateinit var nearbyLink: NearbyDirectLink
    private lateinit var callController: NearbyCallController
    private var actionAfterPermissions: (() -> Unit)? = null
    private var actionAfterMicrophonePermission: (() -> Unit)? = null

    private val nearbyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.all { it }
            val action = actionAfterPermissions
            actionAfterPermissions = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(
                    this,
                    "LINKA needs nearby-device permissions to discover and connect phones.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = actionAfterMicrophonePermission
            actionAfterMicrophonePermission = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(
                    this,
                    "LINKA needs microphone access to place or answer a voice call.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = application as LinkaApplication
        identity = runtime.identity
        nearbyLink = runtime.nearbyLink
        callController = runtime.callController

        setContent {
            LinkaApp(
                identity = identity,
                nearbyLink = nearbyLink,
                callController = callController,
                onStartDiscovery = { withNearbyPermissions { startDiscoveryWhenReady() } },
                onStartAdvertising = { withNearbyPermissions { startAdvertisingWhenReady() } },
                onStartCall = { withMicrophonePermission { callController.startOutgoingCall() } },
                onAcceptCall = { withMicrophonePermission { callController.acceptIncomingCall() } }
            )
        }
    }

    private fun withNearbyPermissions(action: () -> Unit) {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.distinct()

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            action()
        } else {
            actionAfterPermissions = action
            nearbyPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun withMicrophonePermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            actionAfterMicrophonePermission = action
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun radiosReady(): Boolean {
        val bluetoothEnabled = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        val wifiEnabled = runCatching {
            getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(false)
        return bluetoothEnabled || wifiEnabled
    }

    private fun startDiscoveryWhenReady() {
        if (radiosReady()) {
            nearbyLink.startDiscovery()
        } else {
            Toast.makeText(
                this,
                "Turn on Bluetooth or Wi-Fi in Android Quick Settings, then try again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startAdvertisingWhenReady() {
        if (radiosReady()) {
            nearbyLink.startAdvertising()
        } else {
            Toast.makeText(
                this,
                "Turn on Bluetooth or Wi-Fi in Android Quick Settings, then try again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkaApp(
    identity: LocalDeviceIdentity,
    nearbyLink: NearbyDirectLink,
    callController: NearbyCallController,
    onStartDiscovery: () -> Unit,
    onStartAdvertising: () -> Unit,
    onStartCall: () -> Unit,
    onAcceptCall: () -> Unit
) {
    val linkState by nearbyLink.state.collectAsState()
    val callState by callController.state.collectAsState()
    val voiceState by callController.voiceState.collectAsState()

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("LINKA", fontWeight = FontWeight.Bold) }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Talk. Connect. Anywhere.",
                    style = MaterialTheme.typography.titleMedium
                )

                IdentityCard(identity)

                Text("Offline nearby", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Find another compatible LINKA phone using nearby radios. No SIM, airtime, mobile data, or Wi-Fi internet is used for this local discovery connection.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartDiscovery,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Find phones")
                    }
                    OutlinedButton(
                        onClick = onStartAdvertising,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Be discoverable")
                    }
                }

                Text(linkState.status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                linkState.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                PendingPairingCard(linkState, nearbyLink)
                ConnectedDeviceCard(linkState, nearbyLink)
                CallControlsCard(callState, voiceState, callController, linkState.connectedPeer != null, onStartCall, onAcceptCall)

                Text("Nearby devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (linkState.peers.isEmpty()) {
                    Text(
                        "No phones found yet. Both phones must have LINKA open, nearby permissions granted, and Bluetooth or Wi-Fi enabled.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(linkState.peers, key = { it.endpointId }) { peer ->
                            PeerCard(peer = peer, onConnect = { nearbyLink.requestConnection(peer) })
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                OutlinedButton(
                    onClick = { nearbyLink.stopDiscoveryAndAdvertising() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop nearby discovery")
                }

                Text(
                    "Nearby direct voice is implemented in code and awaiting two-phone validation. Multi-hop mesh is not implemented.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IdentityCard(identity: LocalDeviceIdentity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("Your LINKA device ID", style = MaterialTheme.typography.labelMedium)
            Text(
                identity.shortId,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Saved on this phone. Identity key fingerprint: ${identity.publicKeyFingerprint.take(20)}…",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PeerCard(peer: NearbyPeer, onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                Text("Nearby LINKA endpoint", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

@Composable
private fun PendingPairingCard(state: NearbyLinkState, nearbyLink: NearbyDirectLink) {
    val pending = state.pendingPairing ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Confirm this phone", fontWeight = FontWeight.Bold)
            Text(pending.peerName)
            Text(
                pending.authenticationDigits,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Compare the four-digit code with the other phone. Accept only when both codes match; reject unexpected requests.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nearbyLink.acceptPairing() }) { Text("I confirm") }
                OutlinedButton(onClick = { nearbyLink.rejectPairing() }) { Text("Reject") }
            }
        }
    }
}

@Composable
private fun ConnectedDeviceCard(state: NearbyLinkState, nearbyLink: NearbyDirectLink) {
    val peer = state.connectedPeer ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Nearby device connected", fontWeight = FontWeight.Bold)
            Text(peer.displayName)
            Text(
                "The nearby radio connection is established. A direct voice stream can start after the other phone accepts a call; physical-device testing is still required.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { nearbyLink.disconnect() }) { Text("Disconnect") }
        }
    }
}


@Composable
private fun CallControlsCard(
    state: NearbyCallUiState,
    voiceState: VoiceSessionState,
    controller: NearbyCallController,
    peerConnected: Boolean,
    onStartCall: () -> Unit,
    onAcceptCall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("LINKA call controls", fontWeight = FontWeight.Bold)
            Text(state.status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Direct voice uses the microphone and speaker over a Nearby stream. Both phones must grant microphone access and keep the app available during this initial test.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(voiceState.status, style = MaterialTheme.typography.bodySmall)
            voiceState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            when (state.call.phase) {
                CallPhase.IDLE -> {
                    Button(onClick = onStartCall, enabled = peerConnected) {
                        Text("Start call")
                    }
                }
                CallPhase.OUTGOING_RINGING -> {
                    Text("Waiting for the other phone to accept…")
                    OutlinedButton(onClick = { controller.endCall() }) { Text("Cancel call") }
                }
                CallPhase.INCOMING_RINGING -> {
                    Text("Incoming nearby call")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAcceptCall) { Text("Accept") }
                        OutlinedButton(onClick = { controller.rejectIncomingCall() }) { Text("Decline") }
                    }
                }
                CallPhase.ACTIVE -> {
                    Text(if (voiceState.isRunning) "Voice stream is running." else "Call accepted; voice stream status: ${voiceState.status}.")
                    OutlinedButton(onClick = { controller.endCall() }) { Text("End call") }
                }
                CallPhase.ENDED, CallPhase.FAILED -> {
                    OutlinedButton(onClick = { controller.reset() }) { Text("Clear call") }
                    Button(
                        onClick = onStartCall,
                        enabled = peerConnected
                    ) { Text("Call again") }
                }
            }
        }
    }
}
