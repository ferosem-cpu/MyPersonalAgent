package com.mypersonalagent.app.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mypersonalagent.app.data.local.EntryEntity

@Composable
fun QuickLogScreen(viewModel: LogViewModel = hiltViewModel()) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    val status by viewModel.status.collectAsState()
    val error by viewModel.error.collectAsState()
    val entries by viewModel.entries.collectAsState()

    LaunchedEffect(status) {
        if (status != null) {
            title = ""; desc = ""; project = ""; minutes = ""
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") })
            OutlinedTextField(value = project, onValueChange = { project = it }, label = { Text("Project") })
            OutlinedTextField(value = minutes, onValueChange = { minutes = it }, label = { Text("Minutes") })
            Button(onClick = {
                if (title.isNotBlank()) {
                    viewModel.logWork(title, desc, project, minutes.toIntOrNull() ?: 0)
                }
            }) {
                Text("Log work")
            }
            status?.let { Text(it) }
        }

        Text(
            "Recent entries",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )

        error?.let { message ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Couldn't sync: $message")
                    Button(onClick = { viewModel.clearError(); viewModel.refresh() }) { Text("Retry") }
                }
            }
        }

        if (entries.isEmpty()) {
            Text("No entries yet")
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.id }) { entry -> EntryRow(entry) }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: EntryEntity) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            val meta = listOfNotNull(
                entry.project.takeIf { it.isNotBlank() },
                if (entry.minutes > 0) "${entry.minutes} min" else null,
                entry.ts.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall)
            }
            if (entry.desc.isNotBlank()) {
                Text(entry.desc, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
