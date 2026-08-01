package com.mypersonalagent.app.ui.contacts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.mypersonalagent.app.data.remote.ContactDto

@Composable
fun ContactsScreen(viewModel: ContactsViewModel = hiltViewModel()) {
    val contacts by viewModel.contacts.collectAsState()
    val error by viewModel.error.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
            error?.let { message ->
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Couldn't load contacts: $message")
                        Button(onClick = { viewModel.clearError(); viewModel.refresh(query) }) { Text("Retry") }
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; viewModel.refresh(it) },
                label = { Text("Search contacts...") },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )

            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

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
                                    context.startActivity(
                                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                                    )
                                }
                            },
                            onEmail = {
                                contact.email?.takeIf { it.isNotBlank() }?.let { email ->
                                    context.startActivity(
                                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
}

@Composable
private fun ContactRow(contact: ContactDto, onDial: () -> Unit, onEmail: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
            contact.phoneNumber?.takeIf { it.isNotBlank() }?.let { phone ->
                Text(
                    phone,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onDial),
                )
            }
            contact.email?.takeIf { it.isNotBlank() }?.let { email ->
                Text(
                    email,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onEmail),
                )
            }
        }
    }
}
