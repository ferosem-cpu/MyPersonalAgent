package com.mypersonalagent.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.mypersonalagent.app.notifications.ReminderScheduler
import com.mypersonalagent.app.ui.AppShellViewModel
import com.mypersonalagent.app.ui.chat.ChatScreen
import com.mypersonalagent.app.ui.contacts.ContactsScreen
import com.mypersonalagent.app.ui.log.QuickLogScreen
import com.mypersonalagent.app.ui.memory.MemoryScreen
import com.mypersonalagent.app.ui.settings.SettingsScreen
import com.mypersonalagent.app.ui.theme.MyPersonalAgentTheme
import com.mypersonalagent.app.ui.todos.TodoListScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var reminderScheduler: ReminderScheduler

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onResume() {
        super.onResume()
        reminderScheduler.requestImmediateCheck()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

private enum class BottomNavDestination(val route: String, val label: String, val iconText: String) {
    Chat("chat", "Chat", "💬"),
    Todos("todos", "Todos", "📝"),
    Log("log", "Log", "⏱️"),
    Memory("memory", "Memory", "🧠"),
    Contacts("contacts", "Contacts", "📇"),
    Settings("settings", "Settings", "⚙️"),
}

private val routeTitles = mapOf(
    "chat" to "Agent Chat",
    "todos" to "To-Do List",
    "log" to "Work Log",
    "memory" to "Memory & Notes",
    "contacts" to "Contacts",
    "settings" to "App Settings",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPersonalAgentApp(shellViewModel: AppShellViewModel = hiltViewModel()) {
    MyPersonalAgentTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route ?: BottomNavDestination.Chat.route
            val avatarUri by shellViewModel.avatarUri.collectAsState()

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                routeTitles[currentRoute] ?: "MyPersonalAgent",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        actions = {
                            AvatarActionButton(
                                avatarUri = avatarUri,
                                onAvatarPicked = shellViewModel::setAvatarUri,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        BottomNavDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (currentRoute != dest.route) {
                                        navController.navigate(dest.route) {
                                            popUpTo(BottomNavDestination.Chat.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Text(dest.iconText) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = BottomNavDestination.Chat.route,
                    modifier = Modifier.padding(padding),
                ) {
                    composable(BottomNavDestination.Chat.route) { ChatScreen() }
                    composable(BottomNavDestination.Todos.route) { TodoListScreen() }
                    composable(BottomNavDestination.Log.route) { QuickLogScreen() }
                    composable(BottomNavDestination.Memory.route) { MemoryScreen() }
                    composable(BottomNavDestination.Contacts.route) { ContactsScreen() }
                    composable(BottomNavDestination.Settings.route) { SettingsScreen() }
                }
            }
        }
    }
}

@Composable
private fun AvatarActionButton(avatarUri: String?, onAvatarPicked: (String) -> Unit) {
    val context = LocalContext.current
    val pickMedia = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { onAvatarPicked(it.toString()) } }

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
            .padding(end = 12.dp)
            .size(36.dp)
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
            Text("👤", style = MaterialTheme.typography.bodySmall)
        }
    }
}
