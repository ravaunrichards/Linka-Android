package com.linka.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import java.util.UUID

/**
 * Premium LINKA phone hub.
 *
 * Carrier calling, SMS, contacts and call history are handed to the Android
 * system apps. LINKA deliberately uses ACTION_DIAL rather than CALL_PHONE:
 * the user reviews and confirms any carrier call in the system dialer.
 */
@Composable
fun PhoneHubScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val favoritesStore = remember(context) { PhoneFavoritesStore(context) }
    var favorites by remember { mutableStateOf(favoritesStore.load()) }
    var number by rememberSaveable { mutableStateOf("") }
    var showFavoriteEditor by rememberSaveable { mutableStateOf(false) }
    var favoriteName by rememberSaveable { mutableStateOf("") }
    var favoriteNumber by rememberSaveable { mutableStateOf("") }
    var favoriteError by rememberSaveable { mutableStateOf<String?>(null) }

    fun launchSystemIntent(intent: Intent, unavailableMessage: String) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, unavailableMessage, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, unavailableMessage, Toast.LENGTH_LONG).show()
        }
    }

    fun normalizedNumber(): String =
        number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    fun persistFavorites(updated: List<PhoneFavorite>): Boolean {
        return runCatching { favoritesStore.save(updated) }
            .onSuccess { favorites = updated }
            .onFailure {
                Toast.makeText(
                    context,
                    "LINKA could not securely save favourites on this device.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .isSuccess
    }

    fun openFavoriteEditor(prefillNumber: String = "") {
        favoriteName = ""
        favoriteNumber = prefillNumber
        favoriteError = null
        showFavoriteEditor = true
    }

    fun dialFavorite(favorite: PhoneFavorite) {
        launchSystemIntent(
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", favorite.number, null)),
            "No compatible phone dialer is installed."
        )
    }

    fun messageFavorite(favorite: PhoneFavorite) {
        launchSystemIntent(
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", favorite.number, null)),
            "No SMS app is available on this phone."
        )
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "LINKA PHONE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your calls, your control.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Use your Android phone services or switch to LINKA nearby calling.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "DIAL A NUMBER",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = number,
                        onValueChange = { value ->
                            number = value.filter {
                                it.isDigit() || it == '+' || it == '*' || it == '#'
                            }.take(24)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Phone number") },
                        placeholder = { Text("+1 876 555 0100") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        trailingIcon = {
                            OutlinedButton(
                                onClick = { if (number.isNotEmpty()) number = number.dropLast(1) },
                                enabled = number.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Backspace,
                                    contentDescription = "Delete last digit",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    val keypadRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("*", "0", "#")
                    )
                    keypadRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { digit ->
                                FilledTonalButton(
                                    onClick = { if (number.length < 24) number += digit },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(54.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        digit,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val dial = normalizedNumber()
                                if (dial.isBlank()) {
                                    Toast.makeText(context, "Enter a phone number first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    launchSystemIntent(
                                        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dial, null)),
                                        "No compatible phone dialer is installed."
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Icon(Icons.Outlined.Phone, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Open dialer")
                        }
                        OutlinedButton(
                            onClick = {
                                val smsNumber = normalizedNumber()
                                launchSystemIntent(
                                    Intent(
                                        Intent.ACTION_SENDTO,
                                        Uri.fromParts("smsto", smsNumber, null)
                                    ),
                                    "No SMS app is available on this phone."
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Message")
                        }
                    }
                    Text(
                        "Carrier calls and SMS use your mobile operator and may incur charges. LINKA never places a carrier call automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    OutlinedButton(
                        onClick = { openFavoriteEditor(normalizedNumber()) },
                        enabled = PhoneFavoritesCodec.normalizePhoneNumber(normalizedNumber()) != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Save number to favourites")
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "FAVOURITES",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { openFavoriteEditor() }) {
                        Text("Add favourite")
                    }
                }
                if (favorites.isEmpty()) {
                    Text(
                        "No favourites saved. Add a number you use often; LINKA stores favourites securely on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(favorites, key = { favorite -> "favorite-${favorite.id}" }) { favorite ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(favorite.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        favorite.number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { dialFavorite(favorite) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Phone, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Dial")
                        }
                        OutlinedButton(
                            onClick = { messageFavorite(favorite) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Message")
                        }
                    }
                    TextButton(
                        onClick = {
                            persistFavorites(favorites.filterNot { it.id == favorite.id })
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Remove")
                    }
                }
            }
        }

        item {
            Text(
                "PHONE TOOLS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PhoneToolCard(
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    title = "Recent calls",
                    subtitle = "Open call history",
                    onClick = {
                        launchSystemIntent(
                            Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI),
                            "The phone app could not open call history."
                        )
                    }
                )
                PhoneToolCard(
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Outlined.Contacts, contentDescription = null) },
                    title = "Contacts",
                    subtitle = "Browse saved people",
                    onClick = {
                        launchSystemIntent(
                            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
                            "No contacts app is available."
                        )
                    }
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        "LINKA offline calling",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "To call another LINKA device without SIM service or internet, open Nearby, discover the other phone, verify its identity and connect. A nearby radio link and a completed voice call are separate states.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showFavoriteEditor) {
        AlertDialog(
            onDismissRequest = { showFavoriteEditor = false },
            title = { Text("Add phone favourite") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = favoriteName,
                        onValueChange = { favoriteName = it.take(40) },
                        singleLine = true,
                        label = { Text("Name") },
                        placeholder = { Text("e.g. Family") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = favoriteError != null
                    )
                    OutlinedTextField(
                        value = favoriteNumber,
                        onValueChange = { favoriteNumber = it.take(24) },
                        singleLine = true,
                        label = { Text("Phone number") },
                        placeholder = { Text("+1 876 555 0100") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        isError = favoriteError != null
                    )
                    favoriteError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Saved encrypted on this Android device. No contacts are uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val favorite = PhoneFavoritesCodec.createFavorite(
                            id = UUID.randomUUID().toString(),
                            name = favoriteName,
                            number = favoriteNumber
                        )
                        if (favorite == null) {
                            favoriteError = "Enter a name and a valid phone number."
                        } else if (persistFavorites(PhoneFavoritesCodec.add(favorites, favorite))) {
                            showFavoriteEditor = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFavoriteEditor = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PhoneToolCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(42.dp)
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
