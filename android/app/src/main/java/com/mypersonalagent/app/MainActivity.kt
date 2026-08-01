package com.mypersonalagent.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mypersonalagent.app.sync.SyncScheduler
import com.mypersonalagent.app.ui.AppShellViewModel
import com.mypersonalagent.app.ui.chat.ChatScreen
import com.mypersonalagent.app.ui.contacts.ContactsScreen
import com.mypersonalagent.app.ui.log.QuickLogScreen
import com.mypersonalagent.app.ui.memory.MemoryScreen
import com.mypersonalagent.app.ui.settings.SettingsScreen
import com.mypersonalagent.app.ui.todos.TodoListScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncScheduler: SyncScheduler

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onResume() {
        super.onResume()
        syncScheduler.requestExpedited()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MyPersonalAgentApp()
        }
    }
}

/** Routes reachable only from the hamburger drawer - Chat is the default screen, Settings has its own gear icon. */
private enum class DrawerDestination(val route: String, val label: String) {
    Todos("todos", "Todos"),
    Log("log", "Log"),
    Memory("memory", "Memory"),
    Contacts("contacts", "Contacts"),
}

private const val CHAT_ROUTE = "chat"
private const val SETTINGS_ROUTE = "settings"

private val routeTitles = mapOf(
    CHAT_ROUTE to "Agent",
    SETTINGS_ROUTE to "Settings",
) + DrawerDestination.entries.associate { it.route to it.label }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPersonalAgentApp(shellViewModel: AppShellViewModel = hiltViewModel()) {
    MaterialTheme {
        Surface(modifier = Modifier) {
            val navController = rememberNavController()
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route ?: CHAT_ROUTE
            val avatarUri by shellViewModel.avatarUri.collectAsState()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        AvatarHeader(avatarUri = avatarUri, onAvatarPicked = shellViewModel::setAvatarUri)
                        HorizontalDivider()
                        DrawerDestination.entries.forEach { dest ->
                            NavigationDrawerItem(
                                label = { Text(dest.label) },
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) { launchSingleTop = true }
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                },
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(routeTitles[currentRoute] ?: "Agent") },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Text("☰") // hamburger
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true }
                                }) {
                                    Text("⚙") // gear
                                }
                            },
                        )
                    },
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = CHAT_ROUTE,
                        modifier = Modifier.padding(padding),
                    ) {
                        composable(CHAT_ROUTE) { ChatScreen() }
                        composable(DrawerDestination.Todos.route) { TodoListScreen() }
                        composable(DrawerDestination.Log.route) { QuickLogScreen() }
                        composable(DrawerDestination.Memory.route) { MemoryScreen() }
                        composable(DrawerDestination.Contacts.route) { ContactsScreen() }
                        composable(SETTINGS_ROUTE) { SettingsScreen() }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarHeader(avatarUri: String?, onAvatarPicked: (String) -> Unit) {
    val context = LocalContext.current
    val pickMedia = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { onAvatarPicked(it.toString()) } }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        val bitmap = remember(avatarUri) {
            avatarUri?.let { uriString ->
                runCatching {
                    context.contentResolver.openInputStream(android.net.Uri.parse(uriString))?.use {
                        android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable {
                    pickMedia.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(painter = BitmapPainter(bitmap), contentDescription = "Avatar")
            } else {
                Text("+")
            }
        }
        Text(
            "Tap to change photo",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
