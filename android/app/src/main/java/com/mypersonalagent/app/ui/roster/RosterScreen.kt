package com.mypersonalagent.app.ui.roster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mypersonalagent.app.data.local.AssistantEntity

private val palette = listOf("#7C5CFF", "#4DA3FF", "#3DDC97", "#FF7A59", "#FFB020", "#FF5C8A", "#22D3EE", "#F97316")

@Composable
fun RosterScreen(onOpen: (String) -> Unit, viewModel: RosterViewModel = hiltViewModel()) {
    val roster by viewModel.roster.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    val people = roster.filter { !it.isGroup }
    val rooms = roster.filter { it.isGroup }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Crew", style = MaterialTheme.typography.headlineMedium)
                Text("AI teammates on one API key. Add as many as you want.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }
            if (people.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(people, key = { it.id }) { bot ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { onOpen(bot.id) }) {
                                BotAvatar(bot, 52)
                                Spacer(Modifier.height(6.dp))
                                Text(bot.name.split(" ").first(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (rooms.isNotEmpty()) {
                item { Text("Rooms", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rooms, key = { it.id }) { bot -> RosterRow(bot) { onOpen(bot.id) } }
            }
            item { Text("Assistants", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(people, key = { "row-${it.id}" }) { bot -> RosterRow(bot) { onOpen(bot.id) } }
            item { Spacer(Modifier.height(72.dp)) }
        }
        FloatingActionButton(onClick = { showCreate = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Add assistant")
        }
    }
    if (showCreate) {
        CreateAssistantDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, title, instr, color, shape ->
                viewModel.create(name, title, instr, color, shape) { id ->
                    showCreate = false
                    onOpen(id)
                }
            },
        )
    }
}

@Composable
private fun RosterRow(bot: AssistantEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(bot, 44)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bot.isGroup) {
                    Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(bot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            Text(bot.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun BotAvatar(bot: AssistantEntity, size: Int) {
    val color = runCatching { Color(android.graphics.Color.parseColor(bot.colorHex)) }.getOrDefault(MaterialTheme.colorScheme.primary)
    val shape = when (bot.shape) {
        "hex" -> RoundedCornerShape(10.dp)
        "drop" -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp)
        else -> CircleShape
    }
    Box(modifier = Modifier.size(size.dp).clip(shape).background(color), contentAlignment = Alignment.Center) {
        Text(bot.emoji.ifBlank { bot.name.take(1).uppercase() }, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CreateAssistantDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(palette[0]) }
    var shape by remember { mutableStateOf("round") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New assistant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text("Job / title") }, singleLine = true)
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Standing instructions") }, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { hex ->
                        Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(hex))).clickable { color = hex })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("round", "drop", "hex").forEach { s ->
                        FilledTonalButton(onClick = { shape = s }) { Text(s) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, title, instructions, color, shape) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
