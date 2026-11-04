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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.linka.android.core.audio.VoiceSessionState
import com.linka.android.core.call.CallPhase
import com.linka.android.core.call.NearbyCallController
import com.linka.android.core.call.NearbyCallUiState
import com.linka.android.core.identity.LocalDeviceIdentity
import com.linka.android.core.identity.LinkaQrBitmap
import com.linka.android.core.identity.LinkaQrIdentity
import com.linka.android.core.identity.TrustedDeviceStore
import com.linka.android.core.identity.VerifiedQrIdentity
import com.linka.android.core.nearby.NearbyDirectLink
import com.linka.android.core.nearby.NearbyLinkState
import com.linka.android.core.nearby.NearbyPeer
import com.linka.android.core.nearby.PendingPairing
import com.linka.android.core.mesh.RelayConsentStore

private val LinkaColors = lightColorScheme(
    primary = Color(0xFF2459C4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF15346D),
    secondary = Color(0xFF087E83),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F2EF),
    onSecondaryContainer = Color(0xFF0B4141),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF17253A),
    surface = Color.White,
    onSurface = Color(0xFF17253A),
    surfaceVariant = Color(0xFFEAF0F8),
    onSurfaceVariant = Color(0xFF52627A),
    outline = Color(0xFFD5DFEC)
)

private enum class LinkaDestination(val label: String) {
    HOME("Home"),
    PHONE("Phone"),
    NEARBY("Nearby"),
    IDENTITY("My ID"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    private lateinit var identity: LocalDeviceIdentity
    private lateinit var nearbyLink: NearbyDirectLink
    private lateinit var callController: NearbyCallController
    private lateinit var trustedDevices: TrustedDeviceStore
    private lateinit var relayConsent: RelayConsentStore
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
        trustedDevices = runtime.trustedDevices
        relayConsent = runtime.relayConsent

        setContent {
            LinkaApp(
                identity = identity,
                nearbyLink = nearbyLink,
                callController = callController,
                trustedDevices = trustedDevices,
                relayConsent = relayConsent,
                onStartDiscovery = { withNearbyPermissions { startDiscoveryWhenReady() } },
                onStartAdvertising = { withNearbyPermissions { startAdvertisingWhenReady() } },
                onStartCall = { withMicrophonePermission { callController.startOutgoingCall() } },
                onAcceptCall = { withMicrophonePermission { callController.acceptIncomingCall() } },
                onStartRoutedCall = { deviceId ->
                    withMicrophonePermission { callController.startRoutedCall(deviceId) }
                },
                onScanQr = { callback -> scanLinkaQr(callback) }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Keep nearby discovery and advertising active whenever LINKA returns
        // to the foreground and the user has already granted the required access.
        if (::nearbyLink.isInitialized && hasNearbyPermissions() && radiosReady()) {
            nearbyLink.startNetwork()
        }
    }

    private fun scanLinkaQr(onResult: (String?) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> onResult(barcode.rawValue) }
            .addOnCanceledListener { onResult(null) }
            .addOnFailureListener { error ->
                Toast.makeText(
                    this,
                    error.localizedMessage ?: "LINKA QR scanner failed",
                    Toast.LENGTH_LONG
                ).show()
                onResult(null)
            }
    }

    private fun requiredNearbyPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        // Nearby Connections requires location permission on Android 8–12.
        // LINKA does not derive or store physical location from these radios.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.distinct()

    private fun hasNearbyPermissions(): Boolean =
        requiredNearbyPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun withNearbyPermissions(action: () -> Unit) {
        val missing = requiredNearbyPermissions().filter {
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

    private fun startDiscoveryWhenReady() = activateNetworkWhenReady()

    private fun startAdvertisingWhenReady() = activateNetworkWhenReady()

    private fun activateNetworkWhenReady() {
        if (radiosReady()) {
            nearbyLink.startNetwork()
        } else {
            Toast.makeText(
                this,
                "Turn on Bluetooth or Wi-Fi in Android Quick Settings, then activate the LINKA network again.",
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
    trustedDevices: TrustedDeviceStore,
    relayConsent: RelayConsentStore,
    onScanQr: (onResult: (String?) -> Unit) -> Unit,
    onStartDiscovery: () -> Unit,
    onStartAdvertising: () -> Unit,
    onStartCall: () -> Unit,
    onAcceptCall: () -> Unit,
    onStartRoutedCall: (String) -> Unit
) {
    val linkState by nearbyLink.state.collectAsState()
    val callState by callController.state.collectAsState()
    val voiceState by callController.voiceState.collectAsState()
    var destination by rememberSaveable { mutableStateOf(LinkaDestination.HOME) }

    MaterialTheme(colorScheme = LinkaColors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("LINKA", fontWeight = FontWeight.Bold)
                            Text(
                                "Private nearby communications",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Text(
                                "LOCAL FIRST",
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    LinkaDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
                                when (item) {
                                    LinkaDestination.HOME ->
                                        Icon(Icons.Outlined.Home, contentDescription = null)
                                    LinkaDestination.PHONE ->
                                        Icon(Icons.Outlined.Phone, contentDescription = null)
                                    LinkaDestination.NEARBY ->
                                        Icon(Icons.Outlined.Wifi, contentDescription = null)
                                    LinkaDestination.IDENTITY ->
                                        Icon(Icons.Outlined.QrCode2, contentDescription = null)
                                    LinkaDestination.SETTINGS ->
                                        Icon(Icons.Outlined.Settings, contentDescription = null)
                                }
                            },
                            label = { Text(item.label) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        ) { contentPadding ->
            when (destination) {
                LinkaDestination.HOME -> LinkaHomeScreen(
                    modifier = Modifier.padding(contentPadding),
                    linkState = linkState,
                    callState = callState,
                    voiceState = voiceState,
                    callController = callController,
                    onStartDiscovery = {
                        destination = LinkaDestination.NEARBY
                        onStartDiscovery()
                    },
                    onStartAdvertising = {
                        destination = LinkaDestination.NEARBY
                        onStartAdvertising()
                    },
                    onOpenNearby = { destination = LinkaDestination.NEARBY },
                    onOpenIdentity = { destination = LinkaDestination.IDENTITY },
                    onStartCall = onStartCall,
                    onAcceptCall = onAcceptCall
                )

                LinkaDestination.PHONE -> PhoneHubScreen(
                    modifier = Modifier.padding(contentPadding)
                )

                LinkaDestination.NEARBY -> NearbyScreen(
                    modifier = Modifier.padding(contentPadding),
                    linkState = linkState,
                    callState = callState,
                    voiceState = voiceState,
                    nearbyLink = nearbyLink,
                    callController = callController,
                    trustedDevices = trustedDevices,
                    onStartDiscovery = onStartDiscovery,
                    onStartAdvertising = onStartAdvertising,
                    onStartCall = onStartCall,
                    onAcceptCall = onAcceptCall,
                    onStartRoutedCall = onStartRoutedCall
                )

                LinkaDestination.IDENTITY -> IdentityScreen(
                    modifier = Modifier.padding(contentPadding),
                    identity = identity,
                    trustedDevices = trustedDevices,
                    onScanQr = onScanQr,
                    onTrustChanged = { callController.refreshSecurePairings() }
                )

                LinkaDestination.SETTINGS -> SettingsScreen(
                    modifier = Modifier.padding(contentPadding),
                    relayConsent = relayConsent,
                    onRelayConsentChanged = { callController.refreshSecurePairings() }
                )
            }
        }
    }
}

@Composable
private fun LinkaHomeScreen(
    modifier: Modifier = Modifier,
    linkState: NearbyLinkState,
    callState: NearbyCallUiState,
    voiceState: VoiceSessionState,
    callController: NearbyCallController,
    onStartDiscovery: () -> Unit,
    onStartAdvertising: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenIdentity: () -> Unit,
    onStartCall: () -> Unit,
    onAcceptCall: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "WELCOME TO LINKA",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Stay connected, nearby.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Connect with another LINKA phone using nearby wireless radios.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "NEARBY CONNECTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Find a phone close to you",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Nearby discovery does not need mobile data or Wi-Fi internet. Bluetooth or Wi-Fi must be switched on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Icon(
                        Icons.Outlined.Wifi,
                        contentDescription = "Nearby wireless connection",
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item { ConnectionStatusCard(linkState) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartDiscovery,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Wifi, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Find phones")
                }
                OutlinedButton(
                    onClick = onStartAdvertising,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Restart network")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.QrCode2,
                    title = "My LINKA ID",
                    description = "Show or scan a secure QR",
                    onClick = onOpenIdentity
                )
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Wifi,
                    title = "Nearby devices",
                    description = "Pair and manage a connection",
                    onClick = onOpenNearby
                )
            }
        }

        if (linkState.connectedPeer != null || callState.call.phase != CallPhase.IDLE) {
            item {
                CallControlsCard(
                    state = callState,
                    voiceState = voiceState,
                    controller = callController,
                    peerConnected = linkState.connectedPeer != null,
                    onStartCall = onStartCall,
                    onAcceptCall = onAcceptCall
                )
            }
        }

        item { HowItWorksCard() }

        item {
            Text(
                "LINKA is not an emergency-calling service. Nearby voice calling still requires real-device testing before it can be relied on.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NearbyScreen(
    modifier: Modifier = Modifier,
    linkState: NearbyLinkState,
    callState: NearbyCallUiState,
    voiceState: VoiceSessionState,
    nearbyLink: NearbyDirectLink,
    callController: NearbyCallController,
    trustedDevices: TrustedDeviceStore,
    onStartDiscovery: () -> Unit,
    onStartAdvertising: () -> Unit,
    onStartCall: () -> Unit,
    onAcceptCall: () -> Unit,
    onStartRoutedCall: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeading(
                title = "Nearby devices",
                subtitle = "Find another LINKA phone, confirm who you are connecting to, and manage your nearby call."
            )
        }

        item { ConnectionStatusCard(linkState) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartDiscovery,
                    modifier = Modifier.weight(1f)
                ) { Text("Find phones") }
                OutlinedButton(
                    onClick = onStartAdvertising,
                    modifier = Modifier.weight(1f)
                ) { Text("Restart network") }
            }
        }

        linkState.error?.let { errorMessage ->
            item {
                MessageCard(
                    title = "Connection needs attention",
                    message = errorMessage,
                    isError = true
                )
            }
        }

        linkState.pendingPairings.forEach { pending ->
            item(key = "pending-${pending.endpointId}") {
                PendingPairingCard(pending, nearbyLink)
            }
        }

        if (linkState.connectedPeers.isNotEmpty() || linkState.connectedPeer != null) {
            item {
                ConnectedDevicesCard(
                    state = linkState,
                    nearbyLink = nearbyLink,
                    canSelect = callState.call.phase !in setOf(
                        CallPhase.OUTGOING_RINGING,
                        CallPhase.INCOMING_RINGING,
                        CallPhase.ACTIVE
                    )
                )
            }
        }

        item {
            ScreenHeading(
                title = "Phones found",
                subtitle = if (linkState.peers.isEmpty()) {
                    "Phones that are discoverable will appear here."
                } else {
                    "${linkState.peers.size} nearby device(s) found. Connect only to a phone you recognize."
                }
            )
        }

        if (linkState.peers.isEmpty()) {
            item {
                MessageCard(
                    title = "No phones found yet",
                    message = "Ask the other person to open LINKA and tap Be discoverable. Keep Bluetooth or Wi-Fi switched on, then tap Find phones."
                )
            }
        } else {
            items(linkState.peers, key = { it.endpointId }) { peer ->
                PeerCard(peer = peer, onConnect = { nearbyLink.requestConnection(peer) })
            }
        }

        item {
            ScreenHeading(
                title = "Trusted offline destinations",
                subtitle = "Find a live path through consenting relay phones. LINKA will verify the destination identity end-to-end before showing the Call action. Each relay phone must also trust the source and destination identities."
            )
        }

        val savedDestinations = trustedDevices.list()
        if (savedDestinations.isEmpty()) {
            item {
                MessageCard(
                    title = "No trusted destinations",
                    message = "Scan and trust another LINKA identity QR on the My ID screen before requesting an offline route."
                )
            }
        } else {
            items(savedDestinations, key = { "route-${it.deviceId}" }) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val routeHops = callController.routeHopCount(device.deviceId)
                        val routeSecure = callController.isRoutedPeerAuthenticated(device.deviceId)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                device.fingerprint.take(16) + "…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                when {
                                    routeSecure -> "ACTIVE ROUTE • End-to-end verified • ${routeHops ?: 1} hop(s)"
                                    routeHops != null -> "Route found • securing destination • $routeHops hop(s)"
                                    else -> "No active route • waiting for consenting relay links"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (routeSecure) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                callController.discoverMeshRoute(device.deviceId)
                            }) { Text("Find route") }
                            if (routeSecure) {
                                Button(onClick = {
                                    onStartRoutedCall(device.deviceId)
                                }) { Text("Call") }
                            }
                        }
                    }
                }
            }
        }

        item {
            CallControlsCard(
                state = callState,
                voiceState = voiceState,
                controller = callController,
                peerConnected = linkState.connectedPeer != null,
                onStartCall = onStartCall,
                onAcceptCall = onAcceptCall
            )
        }

        item {
            OutlinedButton(
                onClick = { nearbyLink.stopDiscoveryAndAdvertising() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop nearby discovery")
            }
        }

        item {
            Text(
                "A nearby radio connection is not the same as a completed voice call. LINKA voice calling is still awaiting full two-phone validation.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IdentityScreen(
    modifier: Modifier = Modifier,
    identity: LocalDeviceIdentity,
    trustedDevices: TrustedDeviceStore,
    onScanQr: (onResult: (String?) -> Unit) -> Unit,
    onTrustChanged: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeading(
                title = "Your LINKA ID",
                subtitle = "Use your device identity to recognize and verify another LINKA phone."
            )
        }
        item { IdentityCard(identity) }
        item { IdentityQrControls(identity, trustedDevices, onScanQr, onTrustChanged) }
        item {
            MessageCard(
                title = "Keep your identity safe",
                message = "Only trust a device after checking its fingerprint with the owner in person. Revoke a trusted device if you no longer recognize it."
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    relayConsent: RelayConsentStore,
    onRelayConsentChanged: () -> Unit
) {
    var relayEnabled by rememberSaveable { mutableStateOf(relayConsent.isEnabled()) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeading(
                title = "Settings & help",
                subtitle = "Clear guidance about how LINKA works on your Android phone."
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Allow this phone to relay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "When enabled, LINKA may forward opaque encrypted network packets for another trusted phone. This can use battery. Turn it off at any time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = relayEnabled,
                        onCheckedChange = { enabled ->
                            relayEnabled = enabled
                            relayConsent.setEnabled(enabled)
                            onRelayConsentChanged()
                        }
                    )
                }
            }
        }
        item {
            MessageCard(
                title = "Bluetooth and Wi-Fi",
                message = "Android requires you to switch Bluetooth or Wi-Fi on before nearby discovery. Open Quick Settings, enable at least one radio, then return to LINKA."
            )
        }
        item {
            MessageCard(
                title = "Microphone access",
                message = "LINKA asks for microphone permission when you start or answer a voice call. You can manage app permissions at any time in Android Settings."
            )
        }
        item {
            MessageCard(
                title = "Available calling modes",
                message = "LINKA currently implements direct nearby voice and an opt-in encrypted mesh-call path. Internet calling and carrier calling are not part of this offline build. LINKA is not an emergency-calling service."
            )
        }
        item {
            MessageCard(
                title = "Your privacy and safety",
                message = "Pair with people you recognize and compare the confirmation code on both phones. LINKA is not an emergency service, and nearby range depends on each phone and its surroundings."
            )
        }
        item {
            Text(
                "LINKA for Android • Early testing build",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectionStatusCard(state: NearbyLinkState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    Icons.Outlined.Wifi,
                    contentDescription = null,
                    modifier = Modifier.padding(11.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "CONNECTION STATUS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    state.status,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        state.isAdvertising && state.isDiscovering ->
                            "Network active: discoverable and scanning for nearby LINKA phones"
                        state.isAdvertising -> "Discoverable, but not scanning for neighbours"
                        state.isDiscovering -> "Scanning, but this phone is not discoverable"
                        else -> "Network inactive"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.isAdvertising && state.isDiscovering) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (state.connectedPeers.isNotEmpty()) {
                    Text(
                        "${state.connectedPeers.size} nearby link(s) connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                state.connectedPeer?.let { peer ->
                    Text(
                        "Selected for direct calls: ${peer.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(25.dp)
            )
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Get started in 3 steps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HowToStep(number = "1", title = "Switch on a nearby radio", detail = "Enable Bluetooth or Wi-Fi on both phones.")
            HowToStep(number = "2", title = "Find and confirm", detail = "One phone searches; the other becomes discoverable. Compare the pairing code.")
            HowToStep(number = "3", title = "Connect and test", detail = "Connect to the phone you verified. Voice calling still needs real-device validation.")
        }
    }
}

@Composable
private fun HowToStep(number: String, title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(100.dp),
            modifier = Modifier.size(28.dp)
        ) {
            Text(
                number,
                modifier = Modifier.padding(top = 5.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MessageCard(title: String, message: String, isError: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IdentityCard(identity: LocalDeviceIdentity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "YOUR DEVICE ID",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                identity.shortId,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "Saved on this phone. Fingerprint: ${identity.publicKeyFingerprint.take(20)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PeerCard(peer: NearbyPeer, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    Icons.Outlined.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    "Nearby LINKA phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

@Composable
private fun PendingPairingCard(pending: PendingPairing, nearbyLink: NearbyDirectLink) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                "Confirm this phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(pending.peerName, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                pending.authenticationDigits,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                "Compare the code with the other phone. Accept only when both codes match.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nearbyLink.acceptPairing(pending.endpointId) }) {
                    Text("I confirm")
                }
                OutlinedButton(onClick = { nearbyLink.rejectPairing(pending.endpointId) }) {
                    Text("Reject")
                }
            }
        }
    }
}

@Composable
private fun ConnectedDevicesCard(
    state: NearbyLinkState,
    nearbyLink: NearbyDirectLink,
    canSelect: Boolean
) {
    val peers = state.connectedPeers.ifEmpty { listOfNotNull(state.connectedPeer) }
    if (peers.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Connected nearby phones (${peers.size})",
                fontWeight = FontWeight.Bold
            )
            Text(
                "Each entry is a direct radio link to a neighbour. Multi-hop long-distance calling still needs the separate encrypted relay-routing layer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            peers.forEach { peer ->
                val selected = state.connectedPeer?.endpointId == peer.endpointId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (selected) "Selected for direct calls" else "Connected neighbour",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!selected && canSelect) {
                        TextButton(onClick = { nearbyLink.selectPeer(peer.endpointId) }) {
                            Text("Use")
                        }
                    }
                    TextButton(onClick = { nearbyLink.disconnectPeer(peer.endpointId) }) {
                        Text("Disconnect")
                    }
                }
            }
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Voice call", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Direct calls need a securely paired nearby phone. Routed calls need a verified offline route and consenting relays. Both phones need microphone permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Text(if (peerConnected) "Start call" else "Connect a phone to call")
                    }
                }
                CallPhase.OUTGOING_RINGING -> {
                    Text("Waiting for the other phone to accept…")
                    OutlinedButton(onClick = { controller.endCall() }) { Text("Cancel call") }
                }
                CallPhase.INCOMING_RINGING -> {
                    Text("Incoming LINKA call")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAcceptCall) { Text("Accept") }
                        OutlinedButton(onClick = { controller.rejectIncomingCall() }) { Text("Decline") }
                    }
                }
                CallPhase.ACTIVE -> {
                    Text(if (voiceState.isRunning) "Voice stream is running." else "Call accepted. Voice status: ${voiceState.status}.")
                    OutlinedButton(onClick = { controller.endCall() }) { Text("End call") }
                }
                CallPhase.ENDED, CallPhase.FAILED -> {
                    OutlinedButton(onClick = { controller.reset() }) { Text("Clear call") }
                    Button(onClick = onStartCall, enabled = peerConnected) { Text("Call again") }
                }
            }
        }
    }
}

@Composable
private fun IdentityQrControls(
    identity: LocalDeviceIdentity,
    trustedDevices: TrustedDeviceStore,
    onScanQr: (onResult: (String?) -> Unit) -> Unit,
    onTrustChanged: () -> Unit
) {
    var showQr by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pendingIdentity by remember { mutableStateOf<VerifiedQrIdentity?>(null) }
    var status by remember { mutableStateOf("QR identity exchange is ready") }
    val savedDevices = remember { mutableStateOf(trustedDevices.list()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("Trusted devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Exchange a signed, expiring public identity QR in person. QR codes never contain private keys or call secrets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching {
                        qrBitmap = LinkaQrBitmap.create(LinkaQrIdentity.create(identity))
                        showQr = true
                        status = "Your signed identity QR expires after two minutes"
                    }.onFailure {
                        status = it.localizedMessage ?: "Could not create identity QR"
                    }
                }) {
                    Text("Show my QR")
                }
                OutlinedButton(onClick = {
                    onScanQr { rawValue ->
                        if (rawValue.isNullOrBlank()) {
                            status = "QR scan canceled"
                        } else {
                            runCatching { LinkaQrIdentity.verify(rawValue) }
                                .onSuccess { verified ->
                                    if (verified.deviceId == identity.deviceId) {
                                        status = "That QR belongs to this phone"
                                    } else {
                                        pendingIdentity = verified
                                    }
                                }
                                .onFailure {
                                    status = it.localizedMessage ?: "QR identity verification failed"
                                }
                        }
                    }
                }) {
                    Text("Scan QR")
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                "Saved trusted phones: ${savedDevices.value.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            savedDevices.value.take(4).forEach { device ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            device.fingerprint.take(24) + "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        trustedDevices.revoke(device.deviceId)
                        savedDevices.value = trustedDevices.list()
                        onTrustChanged()
                        status = "Revoked ${device.displayName}"
                    }) {
                        Text("Revoke")
                    }
                }
            }
            if (savedDevices.value.size > 4) {
                Text(
                    "And ${savedDevices.value.size - 4} more trusted devices",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showQr && qrBitmap != null) {
        AlertDialog(
            onDismissRequest = {
                showQr = false
                qrBitmap = null
            },
            title = { Text("Your LINKA identity QR") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Signed LINKA device identity QR",
                        modifier = Modifier.size(240.dp)
                    )
                    Text("Device ID: ${identity.shortId}")
                    Text(
                        "This public identity code expires in two minutes. Generate a fresh one before scanning.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Fingerprint: ${identity.publicKeyFingerprint.chunked(4).joinToString(" ")}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showQr = false
                    qrBitmap = null
                }) { Text("Close") }
            }
        )
    }

    pendingIdentity?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pendingIdentity = null },
            title = { Text("Trust this LINKA device?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Device: ${candidate.deviceId}")
                    Text("Verify the fingerprint with the owner of the other phone before trusting it.")
                    Text(
                        candidate.fingerprint.chunked(4).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { trustedDevices.trust(candidate) }
                        .onSuccess { saved ->
                            savedDevices.value = trustedDevices.list()
                            onTrustChanged()
                            status = "Trusted ${saved.displayName}"
                        }
                        .onFailure {
                            status = it.localizedMessage ?: "Could not save trusted device"
                        }
                    pendingIdentity = null
                }) { Text("Trust device") }
            },
            dismissButton = {
                TextButton(onClick = { pendingIdentity = null }) { Text("Cancel") }
            }
        )
    }
}
