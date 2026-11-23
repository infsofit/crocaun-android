package com.infsofit.crocaun

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.infsofit.crocaun.chat.LanChatClient

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
    DisposableEffect(chatClient) {
        val start = chatClient.start()
        startupError = start.exceptionOrNull()?.message
        if (start.isSuccess) {
            val commonRoom = chatClient.addRoom("Common", "crocau-common")
            if (commonRoom.isFailure) startupError = commonRoom.exceptionOrNull()?.message
        }
        onDispose { chatClient.close() }
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
                Destination.Chat -> ChatScreen(chatClient, startupError, networkRevision)
                Destination.History -> ChatHistoryScreen(chatClient, networkRevision)
                else -> PlaceholderScreen(currentDestination)
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(destination: Destination) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(destination.labelResource),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.shell_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F2F8)),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.protocol_status),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
