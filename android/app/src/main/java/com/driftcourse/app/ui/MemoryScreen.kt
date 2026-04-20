package com.driftcourse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private data class Layer(val key: String, val label: String)

private val LAYERS = listOf(
    Layer("char", "キャラ"),
    Layer("recent", "直近"),
    Layer("mid", "中期"),
    Layer("long", "長期"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    conversationId: String,
    onBack: () -> Unit,
    vm: MemoryVM = viewModel(),
) {
    val memory by vm.memory.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf(0) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    var hydrated by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) { vm.load(conversationId) }

    LaunchedEffect(memory) {
        if (!hydrated) {
            drafts["char"] = memory.char
            drafts["recent"] = memory.recent
            drafts["mid"] = memory.mid
            drafts["long"] = memory.long
            hydrated = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("記憶") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            TabRow(selectedTabIndex = selected) {
                LAYERS.forEachIndexed { idx, layer ->
                    Tab(
                        selected = selected == idx,
                        onClick = { selected = idx },
                        text = { Text(layer.label) },
                    )
                }
            }

            val current = LAYERS[selected]
            val value = drafts[current.key].orEmpty()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { drafts[current.key] = it },
                    label = { Text("${current.label} 層") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                )

                Button(
                    onClick = { vm.save(current.key, drafts[current.key].orEmpty()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${current.label}を保存")
                }

                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
