package ru.amigo.chat.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    onBack: () -> Unit,
    viewModel: SystemViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showPassword by rememberSaveable { mutableStateOf(false) }

    var createPassword by rememberSaveable { mutableStateOf("") }
    var connectTopic by rememberSaveable { mutableStateOf("") }
    var connectPassword by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Системный топик") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "Системный топик хранит зашифрованный паролем список чатов. " +
                    "Он не отображается в общем списке чатов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Где хранить список чатов:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == "local",
                    onClick = { viewModel.setMode("local") },
                    label = { Text("Локально") },
                )
                FilterChip(
                    selected = state.mode == "cloud",
                    onClick = { viewModel.setMode("cloud") },
                    label = { Text("В облаке") },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (state.mode == "cloud") {
                    "Список хранится в системном топике и синхронизируется между устройствами."
                } else {
                    "Список хранится только на этом устройстве."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (!state.configured) {
                OutlinedTextField(
                    value = createPassword,
                    onValueChange = { createPassword = it },
                    label = { Text("Пароль для нового системного топика") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.create(createPassword) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Создать системный топик")
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))

                Text(
                    text = "Или подключиться к существующему:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = connectTopic,
                    onValueChange = { connectTopic = it },
                    label = { Text("Топик (GUID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = connectPassword,
                    onValueChange = { connectPassword = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.connect(connectTopic, connectPassword) },
                    enabled = connectTopic.isNotBlank() && connectPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Подключиться и синхронизировать")
                }
            } else {
                Text(
                    text = "Топик: ${state.topic}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "Пароль: " +
                            if (showPassword) state.password.orEmpty() else "••••••••",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Скрыть" else "Показать")
                    }
                    TextButton(onClick = {
                        state.topic?.let { clipboard.setText(AnnotatedString(it)) }
                    }) {
                        Text("Копировать топик")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::publish) { Text("Опубликовать") }
                    Button(onClick = viewModel::sync) { Text("Синхронизировать") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::disconnect) { Text("Отключить") }

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Чаты в системном топике: ${state.chats.size}")
                    if (state.loading) {
                        Spacer(Modifier.size(8.dp))
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                state.chats.forEach { chat ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(chat.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Топик: ${chat.topic}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Пароль: ${chat.password ?: "(нет)"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
