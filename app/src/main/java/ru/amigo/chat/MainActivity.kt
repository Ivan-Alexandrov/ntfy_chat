package ru.amigo.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import ru.amigo.chat.ui.DeepLinkViewModel
import ru.amigo.chat.ui.SessionViewModel
import ru.amigo.chat.ui.chat.ChatScreen
import ru.amigo.chat.ui.chats.ChatListScreen
import ru.amigo.chat.ui.setup.SetupScreen
import ru.amigo.chat.ui.system.SystemScreen
import ru.amigo.chat.ui.theme.AmigoTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val deepLinkViewModel: DeepLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkViewModel.handleUri(intent?.data?.toString())
        enableEdgeToEdge()
        setContent {
            AmigoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val session: SessionViewModel = hiltViewModel()
                    val configured by session.configured.collectAsStateWithLifecycle()

                    when (configured) {
                        null -> LoadingScreen()
                        false -> SetupScreen(onDone = {})
                        true -> ChatNavHost(deepLinkViewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkViewModel.handleUri(intent.data?.toString())
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ChatNavHost(deepLinkViewModel: DeepLinkViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val openChat by deepLinkViewModel.openChat.collectAsStateWithLifecycle()
    val deepLinkError by deepLinkViewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { deepLinkViewModel.process() }

    LaunchedEffect(openChat) {
        val chat = openChat ?: return@LaunchedEffect
        navController.navigate("chat/${chat.topic}?title=${Uri.encode(chat.title)}")
        deepLinkViewModel.consumeOpen()
    }

    LaunchedEffect(deepLinkError) {
        val message = deepLinkError ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        deepLinkViewModel.clearError()
    }

    NavHost(navController = navController, startDestination = "chats") {
        composable("chats") {
            ChatListScreen(
                onOpenChat = { chat ->
                    val title = Uri.encode(chat.title)
                    navController.navigate("chat/${chat.topic}?title=$title")
                },
                onOpenSystem = { navController.navigate("system") },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable(
            route = "chat/{topic}?title={title}",
            arguments = listOf(
                navArgument("topic") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SetupScreen(
                onDone = { navController.popBackStack() },
                showReset = true,
            )
        }
        composable("system") {
            SystemScreen(onBack = { navController.popBackStack() })
        }
    }
}
