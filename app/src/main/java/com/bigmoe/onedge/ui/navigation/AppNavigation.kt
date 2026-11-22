package com.bigmoe.onedge.ui.navigation

import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import com.bigmoe.onedge.core.EngineState
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.animate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.bigmoe.onedge.ui.chat.ChatScreen
import com.bigmoe.onedge.ui.chat.ChatViewModel
import com.bigmoe.onedge.ui.history.HistoryViewModel
import com.bigmoe.onedge.ui.files.FileViewModel
import com.bigmoe.onedge.ui.files.FileViewerScreen
import com.bigmoe.onedge.ui.files.WorkspaceScreen
import com.bigmoe.onedge.ui.home.HomeScreen
import com.bigmoe.onedge.ui.home.ChatHistoryPanel
import com.bigmoe.onedge.ui.models.ModelScreen
import com.bigmoe.onedge.ui.models.ModelViewModel
import com.bigmoe.onedge.ui.settings.DeviceTuneScreen
import com.bigmoe.onedge.ui.settings.LogScreen
import com.bigmoe.onedge.ui.settings.DeviceTuneViewModel
import com.bigmoe.onedge.ui.settings.SettingsScreen
import com.bigmoe.onedge.ui.settings.SettingsViewModel
import com.bigmoe.onedge.ui.theme.appBackgroundModifier

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Models : Screen("models")
    data object Settings : Screen("settings")
    data object Workspace : Screen("workspace")
    data object AutoSettings : Screen("autosettings")
    data object Log : Screen("log")
    data object Hub : Screen("hub")
    data object PerfLog : Screen("perflog")
    data object File : Screen("file/{path}") {
        fun createRoute(path: String): String = "file/${Uri.encode(path)}"
    }
    data object Chat : Screen("chat?sessionId={sessionId}") {
        fun createRoute(sessionId: String? = null): String =
            if (sessionId != null) "chat?sessionId=$sessionId" else "chat"
    }
}

/**
 * Four top-level pages (Chats = Home, Models, Settings, Log), each reached by the buttons in its top bar
 * ([MainNavButtons]), and the Chat page on top of them. The app always starts on Home; it never jumps straight into the
 * last chat. Within a session the Chats button
 * returns to the chat that was open before Models or Settings, or to the chat list if that was where the person was.
 */
@Composable
fun AppNavigation(
    chatViewModel: ChatViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    modelViewModel: ModelViewModel,
    autoTuneViewModel: DeviceTuneViewModel,
    fileViewModel: FileViewModel,
    initialSessionId: String? = null,
    shared: com.bigmoe.onedge.ui.share.SharedContent? = null,
    onShareHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val backStack by navController.currentBackStackEntryAsState()
    var chatHistoryPaneVisible by rememberSaveable { mutableStateOf(true) }
    val route = backStack?.destination?.route

    fun goTo(target: TopTab) {
        val dest = when (target) {
            TopTab.CHATS -> Screen.Home.route
            TopTab.MODELS -> Screen.Models.route
            TopTab.SETTINGS -> Screen.Settings.route
            TopTab.LOG -> Screen.Log.route
            TopTab.HUB -> Screen.Hub.route
        }
        if (dest == route) return
        navController.navigate(dest) {
            popUpTo(Screen.Home.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    // Where the Chats tab returns to: the chat that was open when Models or Settings was opened, or the chat list.
    var resumeChat by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(route) {
        when {
            route == Screen.Home.route -> resumeChat = false
            route?.startsWith("chat") == true -> resumeChat = true
        }
    }

    /** The Chats button of every page except the chat itself: back into the chat that was open, else the chat list. */
    fun goToChats() {
        if (!resumeChat) return goTo(TopTab.CHATS)
        navController.navigate(Screen.Chat.createRoute(chatViewModel.uiState.value.currentSession?.id)) {
            popUpTo(Screen.Home.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    // Performance log -> "Ask a model": the model to answer and the prompt, until the model is loading or loaded.
    var pendingAsk by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(pendingAsk) {
        val (path, prompt) = pendingAsk ?: return@LaunchedEffect
        var sawDialog = false
        // Loading or loaded: the prompt goes into a new chat (queued while the model loads). A load that asks first
        // (warnings) shows its dialog on the Models page; we go there and wait for the user's answer.
        val started = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) {
            kotlinx.coroutines.flow.combine(chatViewModel.uiState, modelViewModel.uiState) { c, m ->
                val s = c.engineState
                when {
                    (s is EngineState.Ready && s.info.path == path) || (s is EngineState.Loading && s.path == path) -> true
                    m.pendingLoad != null -> { if (!sawDialog) { sawDialog = true; goTo(TopTab.MODELS) }; null }
                    else -> null
                }
            }.first { it != null }
        }
        pendingAsk = null
        if (started == true) {
            chatViewModel.newChatAndSend(prompt)
            navController.navigate(Screen.Chat.createRoute(null)) { launchSingleTop = true }
        }
    }

    // Shared from another app: pick the chat (and a model when none is loaded), then the content goes into the box.
    if (shared != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val sessions by historyViewModel.sessionsState.collectAsState()
        val modelsUi by modelViewModel.uiState.collectAsState()
        val chatUi by chatViewModel.uiState.collectAsState()
        val engine = chatUi.engineState
        val noModel = engine !is EngineState.Ready && engine !is EngineState.Loading
        val shareScope = androidx.compose.runtime.rememberCoroutineScope()
        com.bigmoe.onedge.ui.share.ShareTargetDialog(
            shared = shared,
            sessions = sessions,
            models = modelsUi.models.filter { !com.bigmoe.onedge.core.ModelManager.isProjectorName(it.path) },
            askForModel = noModel,
            onDismiss = onShareHandled,
            onDone = { sessionId, modelPath ->
                onShareHandled()
                chatViewModel.receiveShare(sessionId, shared.text, shared.files, context.contentResolver, shared.links)
                navController.navigate(Screen.Chat.createRoute(sessionId)) { launchSingleTop = true }
                if (modelPath != null) {
                    modelViewModel.requestLoad(modelPath)
                    // A load that asks first shows its dialog on the Models page.
                    shareScope.launch {
                        val asks = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                            kotlinx.coroutines.flow.combine(chatViewModel.uiState, modelViewModel.uiState) { c, m ->
                                when {
                                    m.pendingLoad != null -> true
                                    c.engineState is EngineState.Loading || c.engineState is EngineState.Ready -> false
                                    else -> null
                                }
                            }.first { it != null }
                        }
                        if (asks == true) goTo(TopTab.MODELS)
                    }
                }
            }
        )
    }

    fun openChat(sessionId: String?) {
        if (sessionId == null) chatViewModel.newChat() else chatViewModel.loadSession(sessionId)
        navController.navigate(Screen.Chat.createRoute(sessionId)) { launchSingleTop = true }
    }

    LaunchedEffect(initialSessionId) {
        if (!initialSessionId.isNullOrBlank()) openChat(initialSessionId)
    }

    // The buttons in every top bar. From the chat itself "Chats" shows the chat list; from the other pages it returns
    // to the chat that was open.
    val mainNav = MainNav { target ->
        when {
            target == TopTab.CHATS && route?.startsWith("chat") != true -> goToChats()
            else -> goTo(target)
        }
    }

    CompositionLocalProvider(LocalMainNav provides mainNav) {
    Box(modifier = modifier.fillMaxSize().then(appBackgroundModifier())) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.fillMaxSize(),
            // Switching between Chats / Models / Settings / Log / a chat is instant: no fade, no slide.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable(route = Screen.Home.route) {
                HomeScreen(viewModel = historyViewModel, onOpenChat = { id -> openChat(id) }, onNewChat = { openChat(null) })
            }
            composable(route = Screen.Models.route) {
                ModelScreen(viewModel = modelViewModel)
            }
            composable(route = Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenModels = { goTo(TopTab.MODELS) },
                    onOpenWorkspace = { navController.navigate(Screen.Workspace.route) },
                    onOpenAutoSettings = { navController.navigate(Screen.AutoSettings.route) { launchSingleTop = true } },
                    onOpenPerfLog = { navController.navigate(Screen.PerfLog.route) { launchSingleTop = true } },
                    onOpenLog = { goTo(TopTab.LOG) }
                )
            }
            composable(route = Screen.Hub.route) {
                val modelsUi by modelViewModel.uiState.collectAsState()
                com.bigmoe.onedge.ui.models.HuggingFaceScreen(modelsUi.modelFolder.orEmpty()) { modelViewModel.refresh() }
            }
            composable(route = Screen.Log.route) {
                LogScreen(viewModel = settingsViewModel)
            }
            composable(route = Screen.PerfLog.route) {
                val modelsUi by modelViewModel.uiState.collectAsState()
                com.bigmoe.onedge.ui.settings.PerfLogScreen(
                    models = modelsUi.models,
                    onAsk = { path, prompt ->
                        val s = chatViewModel.uiState.value.engineState
                        val ready = (s is EngineState.Ready && s.info.path == path) || (s is EngineState.Loading && s.path == path)
                        if (!ready) modelViewModel.requestLoad(path)
                        pendingAsk = path to prompt
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(route = Screen.AutoSettings.route) {
                DeviceTuneScreen(
                    viewModel = autoTuneViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(route = Screen.Workspace.route) {
                WorkspaceScreen(
                    viewModel = fileViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFile = { path -> navController.navigate(Screen.File.createRoute(path)) }
                )
            }
            composable(
                route = Screen.File.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { entry ->
                val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                FileViewerScreen(
                    viewModel = fileViewModel,
                    path = path,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    navArgument("sessionId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                val sessionIdArg = entry.arguments?.getString("sessionId")
                // Rotation / process recreation: re-open the route's original chat only when the VM has
                // no already-selected session. Wide-screen chat switching deliberately keeps the VM
                // session independent from this route argument.
                LaunchedEffect(sessionIdArg) {
                    if (sessionIdArg != null && chatViewModel.uiState.value.currentSession == null) {
                        chatViewModel.loadSession(sessionIdArg)
                    }
                }
                val chatUiState by chatViewModel.uiState.collectAsState()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 840.dp
                    if (wide) {
                        // The history pane is a slider: drag the handle left/right to hide/show it (it follows the
                        // finger and snaps open or shut on release); tapping the handle or the menu button toggles it too.
                        val density = LocalDensity.current
                        val paneWidth = 344.dp
                        val paneWidthPx = with(density) { paneWidth.toPx() }
                        val paneScope = rememberCoroutineScope()
                        var reveal by remember { mutableStateOf(if (chatHistoryPaneVisible) 1f else 0f) }
                        var dragging by remember { mutableStateOf(false) }
                        LaunchedEffect(chatHistoryPaneVisible) {
                            val target = if (chatHistoryPaneVisible) 1f else 0f
                            if (!dragging) animate(reveal, target, animationSpec = tween(240)) { v, _ -> if (!dragging) reveal = v }
                        }
                        val dragState = rememberDraggableState { delta ->
                            reveal = (reveal + delta / paneWidthPx).coerceIn(0f, 1f)
                        }
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(paneWidth * reveal).fillMaxHeight().clipToBounds()) {
                                if (reveal > 0.001f) {
                                    // Anchored to the pane's trailing edge, so it slides out to the left instead of squashing.
                                    ChatHistoryPanel(
                                        viewModel = historyViewModel,
                                        selectedSessionId = chatUiState.currentSession?.id,
                                        onNewChat = { chatViewModel.newChat() },
                                        onOpenChat = { chatViewModel.loadSession(it) },
                                        modifier = Modifier.wrapContentWidth(Alignment.End, unbounded = true)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier.width(28.dp).fillMaxHeight()
                                    .draggable(
                                        state = dragState,
                                        orientation = Orientation.Horizontal,
                                        onDragStarted = { dragging = true },
                                        onDragStopped = { velocity ->
                                            dragging = false
                                            val target = when {
                                                velocity > 700f -> 1f
                                                velocity < -700f -> 0f
                                                reveal > 0.5f -> 1f
                                                else -> 0f
                                            }
                                            val open = target == 1f
                                            if (open != chatHistoryPaneVisible) chatHistoryPaneVisible = open
                                            else paneScope.launch { animate(reveal, target, animationSpec = tween(200)) { v, _ -> reveal = v } }
                                        }
                                    )
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        chatHistoryPaneVisible = !chatHistoryPaneVisible
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    Modifier.width(5.dp).height(52.dp).clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                                )
                            }
                            ChatScreen(
                                viewModel = chatViewModel,
                                historyViewModel = historyViewModel,
                                onNewChat = { openChat(null) },
                                onOpenChat = { id -> openChat(id) },
                                onNavigateHome = { goTo(TopTab.CHATS) },
                                onNavigateToModels = { goTo(TopTab.MODELS) },
                                onNavigateToSettings = { goTo(TopTab.SETTINGS) },
                                onOpenFile = { path -> navController.navigate(Screen.File.createRoute(path)) },
                                modelViewModel = modelViewModel,
                                wideMode = true,
                                historyPaneVisible = chatHistoryPaneVisible,
                                onToggleHistoryPane = { chatHistoryPaneVisible = !chatHistoryPaneVisible },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        ChatScreen(
                            viewModel = chatViewModel,
                            historyViewModel = historyViewModel,
                            onNewChat = { openChat(null) },
                            onOpenChat = { id -> openChat(id) },
                            onNavigateHome = { goTo(TopTab.CHATS) },
                            onNavigateToModels = { goTo(TopTab.MODELS) },
                            onNavigateToSettings = { goTo(TopTab.SETTINGS) },
                            onOpenFile = { path -> navController.navigate(Screen.File.createRoute(path)) },
                            modelViewModel = modelViewModel
                        )
                    }
                }
            }
        }
        // Translation mode (Settings → Language): a button that translates the texts of the current page.
        val translateModels by modelViewModel.uiState.collectAsState()
        com.bigmoe.onedge.ui.settings.TranslateOverlay(
            routeKey = backStack?.destination?.route + "|" + backStack?.arguments?.toString(),
            models = translateModels.models,
            onLoad = { path -> modelViewModel.requestLoad(path) }
        )
    }
    }
}
