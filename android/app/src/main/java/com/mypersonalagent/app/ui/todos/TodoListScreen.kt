package com.mypersonalagent.app.ui.todos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mypersonalagent.app.data.local.TodoEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(viewModel: TodoViewModel = hiltViewModel()) {
    val todos by viewModel.todos.collectAsState()
    val error by viewModel.error.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Text("+") },
                text = { Text("Add") },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (todos.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📋", style = MaterialTheme.typography.displayLarge)
                            Text("No to-dos yet", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Pull down to refresh, or tap Add",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(todos, key = { it.id }) { todo ->
                            SwipeableTodoRow(
                                todo = todo,
                                onComplete = { viewModel.completeTodo(todo.id) },
                                onDelete = { viewModel.deleteTodo(todo.id) },
                            )
                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTodoRow(todo: TodoEntity, onComplete: () -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (todo.status != "done") onComplete()
                    false // don't actually remove the composable - list updates from the data change
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    LaunchedEffect(todo.id) {
        // Reset any lingering swipe offset when this row is recomposed for a different/updated todo.
        dismissState.reset()
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val (color, label, alignment) = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(Color(0xFF2E7D32), "Complete", Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(MaterialTheme.colorScheme.error, "Delete", Alignment.CenterEnd)
                SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, "", Alignment.Center)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Text(label, color = Color.White)
            }
        },
    ) {
        TodoRow(todo = todo, onComplete = onComplete)
    }
}

@Composable
private fun TodoRow(todo: TodoEntity, onComplete: () -> Unit) {
    val overdue = isOverdue(todo)
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = if (overdue) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(todo.title, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (todo.project.isNotBlank()) {
                    Text(todo.project, style = MaterialTheme.typography.bodySmall)
                }
                DueDateChip(todo)
            }
            if (todo.status != "done") {
                Button(onClick = onComplete, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun DueDateChip(todo: TodoEntity) {
    val due = todo.snoozeUntil ?: todo.due ?: return
    val overdue = isOverdue(todo)
    val bg = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (overdue) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier = Modifier
            .background(bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(due.take(16).replace("T", " "), style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

private fun isOverdue(todo: TodoEntity): Boolean {
    if (todo.status == "done") return false
    val due = todo.snoozeUntil ?: todo.due ?: return false
    val instant = parseInstant(due) ?: return false
    return instant.isBefore(Instant.now())
}

private fun parseInstant(raw: String): Instant? = try {
    OffsetDateTime.parse(raw).toInstant()
} catch (e: DateTimeParseException) {
    try { Instant.parse(raw) } catch (e2: DateTimeParseException) { null }
}

@Composable
private fun AddTodoDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }

    AlertDialog(
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
