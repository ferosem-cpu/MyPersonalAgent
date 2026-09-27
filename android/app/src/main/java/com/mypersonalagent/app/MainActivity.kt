package com.mypersonalagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mypersonalagent.app.data.repo.FileInboxRepository
import com.mypersonalagent.app.data.repo.IncomingShareBus
import com.mypersonalagent.app.notifications.ReminderScheduler
import com.mypersonalagent.app.ui.AppShellViewModel
import com.mypersonalagent.app.ui.chat.ChatScreen
import com.mypersonalagent.app.ui.contacts.ContactsScreen
import com.mypersonalagent.app.ui.files.FilesScreen
import com.mypersonalagent.app.ui.log.QuickLogScreen
import com.mypersonalagent.app.ui.memory.MemoryScreen
import com.mypersonalagent.app.ui.roster.RosterScreen
import com.mypersonalagent.app.ui.settings.SettingsScreen
import com.mypersonalagent.app.ui.theme.MyPersonalAgentTheme
import com.mypersonalagent.app.ui.todos.TodoListScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var fileInbox: FileInboxRepository
    @Inject lateinit var shareBus: IncomingShareBus

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onResume() {
        super.onResume()
        reminderScheduler.requestImmediateCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
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
        handleIncoming(intent)
        setContent { MyPersonalAgentApp() }
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> extraUri(intent)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> extraUriList(intent).let { uris.addAll(it) }
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            uris.forEach { uri ->
                runCatching { fileInbox.ingestUri(uri, source = "share") }
                    .onFailure { shareBus.emit("Could not save shared file: ${it.message}") }
            }
        }
    }

    private fun extraUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extraUriList(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}

private enum class BottomNavDestination(val route: String, val label: String, val icon: ImageVector) {
    Crew("crew", "Crew", Icons.Filled.Groups),
    Todos("todos", "Todos", Icons.Filled.FormatListBulleted),
    Log("log", "Log", Icons.Filled.History),
    Memory("memory", "Memory", Icons.Filled.Lightbulb),
    Contacts("contacts", "Contacts", Icons.Filled.Contacts),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPersonalAgentApp(shellViewModel: AppShellViewModel = hiltViewModel()) {
    MyPersonalAgentTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route ?: BottomNavDestination.Crew.route
            val showBottomBar = currentRoute != "settings" && currentRoute != "files" && currentRoute?.startsWith("chat") != true

            LaunchedEffect(Unit) {
                shellViewModel.shareEvents.collect {
                    navController.navigate("files") { launchSingleTop = true }
                }
            }

            Scaffold(
                topBar = {
                    if (currentRoute?.startsWith("chat") != true) {
                        TopAppBar(
                            title = { Text(if (currentRoute == "crew") "Crew" else currentRoute?.replaceFirstChar { it.uppercase() } ?: "Crew", style = MaterialTheme.typography.titleLarge) },
                            actions = {
                                IconButton(onClick = { navController.navigate("files") }) {
                                    Icon(Icons.Filled.Folder, contentDescription = "Files")
                                }
                                IconButton(onClick = { navController.navigate("settings") }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        )
                    }
                },
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            BottomNavDestination.entries.forEach { dest ->
                                NavigationBarItem(
                                    selected = currentRoute == dest.route,
                                    onClick = {
                                        if (currentRoute != dest.route) {
                                            navController.navigate(dest.route) {
                                                popUpTo(BottomNavDestination.Crew.route) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                    label = { Text(dest.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(navController = navController, startDestination = BottomNavDestination.Crew.route, modifier = Modifier.padding(padding)) {
                    composable(BottomNavDestination.Crew.route) {
                        RosterScreen(onOpen = { id -> navController.navigate("chat/$id") })
                    }
                    composable(
                        route = "chat/{assistantId}",
                        arguments = listOf(navArgument("assistantId") { type = NavType.StringType }),
                    ) {
                        ChatScreen(onBack = { navController.popBackStack() })
                    }
                    composable(BottomNavDestination.Todos.route) { TodoListScreen() }
                    composable(BottomNavDestination.Log.route) { QuickLogScreen() }
                    composable(BottomNavDestination.Memory.route) { MemoryScreen() }
                    composable(BottomNavDestination.Contacts.route) { ContactsScreen() }
                    composable("files") { FilesScreen() }
                    composable("settings") { SettingsScreen() }
                }
            }
        }
    }
}
