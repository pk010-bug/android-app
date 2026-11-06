package app.privateinbox.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.privateinbox.navigation.DeepLinks
import app.privateinbox.ui.alerts.AlertsScreen
import app.privateinbox.ui.compose.ComposeScreen
import app.privateinbox.ui.compose.Draft
import app.privateinbox.ui.conversation.ConversationScreen
import app.privateinbox.ui.finance.FinanceScreen
import app.privateinbox.ui.inbox.InboxScreen
import app.privateinbox.ui.settings.SettingsScreen

/** Top-level bottom-nav destinations. */
enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    INBOX("inbox", "Inbox", Icons.Outlined.Inbox),
    FINANCE("finance", "Finance", Icons.Outlined.AccountBalanceWallet),
    ALERTS("alerts", "Alerts", Icons.Outlined.NotificationsActive),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings),
}

object Routes {
    const val COMPOSE = "compose"
    const val CONVERSATION = "conversation/{conversationId}"
    fun conversation(id: Long) = "conversation/$id"
}

/**
 * @param pendingDraft a draft from another app (already triaged); opens the composer, never sends.
 * @param pendingRoute a route from a notification tap, already validated by [DeepLinks].
 */
@Composable
fun PrivateInboxNavHost(
    pendingDraft: Draft? = null,
    pendingRoute: String? = null,
    onExternalConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(Draft()) }

    LaunchedEffect(pendingDraft, pendingRoute) {
        if (pendingDraft != null) {
            draft = pendingDraft
            navController.navigate(Routes.COMPOSE) { launchSingleTop = true }
            onExternalConsumed()
        } else if (pendingRoute != null) {
            DeepLinks.validate(pendingRoute)?.let { navController.navigate(it) { launchSingleTop = true } }
            onExternalConsumed()
        }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val showBottomBar = TopLevel.entries.any { tl -> current?.hierarchy?.any { it.route == tl.route } == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevel.entries.forEach { dest ->
                        val selected = current?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevel.INBOX.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevel.INBOX.route) {
                InboxScreen(onOpenConversation = { navController.navigate(Routes.conversation(it)) })
            }
            composable(TopLevel.FINANCE.route) { FinanceScreen() }
            composable(TopLevel.ALERTS.route) { AlertsScreen() }
            composable(TopLevel.SETTINGS.route) { SettingsScreen() }
            composable(Routes.COMPOSE) {
                ComposeScreen(draft = draft, onBack = {
                    draft = Draft()
                    navController.popBackStack()
                })
            }
            composable(
                Routes.CONVERSATION,
                arguments = listOf(navArgument("conversationId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("conversationId") ?: 0L
                if (id <= 0L) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    ConversationScreen(conversationId = id, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

private val DraftSaver = androidx.compose.runtime.saveable.listSaver<Draft, Any>(
    save = { listOf(it.recipients, it.body, it.attachmentNames, it.notices) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        Draft(it[0] as List<String>, it[1] as String, it[2] as List<String>, it[3] as List<String>)
    },
)
