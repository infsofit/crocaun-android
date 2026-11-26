package com.infsofit.crocaun

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.infsofit.crocaun.chat.LanChatClient
import com.infsofit.crocaun.transferui.CrocTransferController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrocaunTheme {
                CrocaunApp()
            }
        }
    }
}

private enum class Destination(val labelResource: Int, val icon: ImageVector) {
    Send(R.string.nav_send, Icons.AutoMirrored.Filled.Send),
    Receive(R.string.nav_receive, Icons.Filled.Download),
    Chat(R.string.nav_chat, Icons.Filled.ChatBubbleOutline),
    History(R.string.nav_history, Icons.Filled.History),
    Settings(R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
private fun CrocaunApp() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var networkRevision by remember { mutableIntStateOf(0) }
    var startupError by remember { mutableStateOf<String?>(null) }
    val chatClient = remember(context) {
        LanChatClient(context) { mainHandler.post { networkRevision++ } }
    }
    val transferController = remember(context) { CrocTransferController(context) }
    val transferState = remember(transferController) { CrocTransferUiState() }
    DisposableEffect(chatClient) {
        val start = chatClient.start()
        startupError = start.exceptionOrNull()?.message
        if (start.isSuccess) {
            val commonRoom = chatClient.addRoom("Common", "crocau-common")
            if (commonRoom.isFailure) startupError = commonRoom.exceptionOrNull()?.message
        }
        onDispose { chatClient.close() }
    }
    DisposableEffect(transferController) {
        onDispose { transferController.close() }
    }

    var selected by rememberSaveable { mutableStateOf(Destination.Send.name) }
    val currentDestination = Destination.valueOf(selected)

    Scaffold(
        containerColor = Color(0xFFF5F9FC),
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination == destination,
                        onClick = { selected = destination.name },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = stringResource(destination.labelResource),
                            )
                        },
                        label = { Text(stringResource(destination.labelResource)) },
                    )
                }
            }
        },
    ) { contentPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (currentDestination) {
                Destination.Send -> CrocSendScreen(transferController, transferState)
                Destination.Receive -> CrocReceiveScreen(transferController, transferState)
                Destination.Chat -> ChatScreen(chatClient, startupError, networkRevision)
                Destination.History -> ChatHistoryScreen(chatClient, networkRevision)
                Destination.Settings -> CrocSettingsScreen(transferController, transferState)
            }
        }
    }
}

@Composable
private fun CrocaunTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1769C2),
            onPrimary = Color.White,
            secondary = Color(0xFFFFE500),
            onSecondary = Color(0xFF263746),
            background = Color(0xFFF5F9FC),
            surface = Color.White,
            onSurface = Color(0xFF263746),
            onSurfaceVariant = Color(0xFF526371),
        ),
        content = content,
    )
}
