package com.mypersonalagent.app.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mypersonalagent.app.data.local.TodoEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(viewModel: TodoViewModel = hiltViewModel()) {
    val todos by viewModel.todos.collectAsState()
    val error by viewModel.error.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { viewModel.refresh() }) { Text("Refresh") }
            }
            // Local Room data (todos) is the source of truth for the UI and is ALWAYS
            // shown, whether or not the last network sync succeeded - that's the point
            // of offline-first. A sync failure is just a small dismissible banner, never
            // something that hides your already-saved local data.
            error?.let { message ->
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Couldn't sync: $message")
                        Button(onClick = { viewModel.clearError(); viewModel.refresh() }) { Text("Retry") }
                    }
                }
            }

            if (todos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No to-dos yet")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(todos, key = { it.id }) { todo ->
                        TodoRow(todo = todo, onComplete = { viewModel.completeTodo(todo.id) })
                    }
                }
            }
        }

        if (showAddDialog) {
            AddTodoDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { title, project ->
                    viewModel.addTodo(title, project, due = null)
                    showAddDialog = false
                },
            )
        }
    }
}

@Composable
private fun TodoRow(todo: TodoEntity, onComplete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(todo.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (todo.project.isNotBlank()) {
                Text(todo.project, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            if (todo.status != "done") {
                Button(onClick = onComplete, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun AddTodoDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to-do") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
                OutlinedTextField(value = project, onValueChange = { project = it }, label = { Text("Project") })
            }
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank()) onConfirm(title, project) }) {
                Text("Add")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
