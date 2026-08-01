package com.mypersonalagent.app.ui.memory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mypersonalagent.app.data.remote.NoteDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(viewModel: MemoryViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsState()
    val error by viewModel.error.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val shoppingItems by viewModel.shoppingItems.collectAsState()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
            error?.let { message ->
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Couldn't reach memory: $message")
                        Button(onClick = { viewModel.clearError(); viewModel.refresh() }) { Text("Retry") }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                OutlinedTextField(
                    value = newNote,
                    onValueChange = { newNote = it },
                    label = { Text("Remember something...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.remember(newNote); newNote = "" },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Save") }
            }

            // Shopping list is just notes tagged "shopping" (add via chat: "add milk to my
            // shopping list") - this button is the local deep-link/clipboard fallback from
            // PLAN_V2 Task 6.3, purely client-side, no server round-trip.
            if (shoppingItems.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Shopping list (${shoppingItems.size} items)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            shoppingItems.joinToString(", ") { it.text },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = {
                                val listText = shoppingItems.joinToString(", ") { it.text }
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Shopping list", listText))
                                Toast.makeText(context, "List copied - paste in search", Toast.LENGTH_LONG).show()
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://blinkit.com")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("🛒 Order groceries") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; viewModel.search(it) },
                    label = { Text("Recall...") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (notes.isEmpty() && !loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (query.isBlank()) "No notes yet" else "No matches")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(notes, key = { it.id ?: it.text }) { note -> NoteRow(note) }
                    }
                }
            }
        }
}

@Composable
private fun NoteRow(note: NoteDto) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(note.text, style = MaterialTheme.typography.bodyLarge)
            if (note.tags.isNotEmpty()) {
                Text(note.tags.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
