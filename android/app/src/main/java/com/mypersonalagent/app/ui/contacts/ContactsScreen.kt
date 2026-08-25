package com.mypersonalagent.app.ui.contacts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.mypersonalagent.app.data.remote.ContactDto
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(viewModel: ContactsViewModel = hiltViewModel()) {
    val contacts by viewModel.contacts.collectAsState()
    val error by viewModel.error.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val vcf by viewModel.vcf.collectAsState()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(vcf) {
        val content = vcf ?: return@LaunchedEffect
        if (content.isBlank()) {
            viewModel.clearVcf()
            return@LaunchedEffect
        }
        val file = File(context.cacheDir, "contacts.vcf")
        file.writeText(content)
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrElse {
            Uri.fromFile(file)
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/x-vcard"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Export contacts"))
        viewModel.clearVcf()
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Text("+") },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { message ->
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Couldn't load contacts: $message")
                        Button(onClick = { viewModel.clearError(); viewModel.refresh(query) }) { Text("Retry") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; viewModel.refresh(it) },
                    label = { Text("Search contacts") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.exportVcf() }) { Text("vCard") }
            }

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = { viewModel.refresh(query) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (contacts.isEmpty() && !loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (query.isBlank()) "No contacts yet" else "No matches")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(contacts, key = { it.id ?: it.name }) { contact ->
                            ContactRow(
                                contact = contact,
                                onDial = {
                                    contact.phoneNumber?.takeIf { it.isNotBlank() }?.let { number ->
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                                    }
                                },
                                onEmail = {
                                    contact.email?.takeIf { it.isNotBlank() }?.let { email ->
                                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
                                    }
                                },
                                onDelete = { viewModel.delete(contact.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddContactDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, phone, email ->
                viewModel.save(name, phone, email)
                showAdd = false
            },
        )
    }
}

@Composable
private fun ContactRow(
    contact: ContactDto,
    onDial: () -> Unit,
    onEmail: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
            contact.phoneNumber?.takeIf { it.isNotBlank() }?.let { phone ->
                Text(phone, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable(onClick = onDial))
            }
            contact.email?.takeIf { it.isNotBlank() }?.let { email ->
                Text(email, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable(onClick = onEmail))
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") })
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") })
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name, phone, email) }) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
