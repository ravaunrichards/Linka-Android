package com.linka.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linka.android.core.identity.LocalDeviceIdentity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LinkaApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkaApp() {
    val identity = remember { LocalDeviceIdentity.create() }
    var number by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Offline nearby") }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("LINKA", fontWeight = FontWeight.Bold) }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Talk. Connect. Anywhere.", style = MaterialTheme.typography.titleMedium)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your offline device ID", style = MaterialTheme.typography.labelMedium)
                        Text(identity.shortId, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Username and QR pairing will be added in the identity milestone.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(number.ifEmpty { "Enter a number or LINKA ID" }, style = MaterialTheme.typography.headlineSmall)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(keys) { key ->
                        OutlinedButton(
                            onClick = { number += key },
                            modifier = Modifier.height(56.dp)
                        ) { Text(key, style = MaterialTheme.typography.titleLarge) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { number = number.dropLast(1) }) { Text("Delete") }
                    Button(onClick = { mode = "Call engine not connected yet" }) { Text("Call") }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Calling status", fontWeight = FontWeight.SemiBold)
                        Text(mode, style = MaterialTheme.typography.bodyMedium)
                        Text("Offline voice transport is not enabled in this foundation build.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.size(2.dp))
                Text("Jamaica-first • Digicel & FLOW compatibility planned", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
