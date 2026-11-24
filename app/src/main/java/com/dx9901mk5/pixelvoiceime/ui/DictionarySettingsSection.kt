package com.dx9901mk5.pixelvoiceime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.R
import com.dx9901mk5.pixelvoiceime.data.CustomDictionaryStore

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionarySettingsSection(
    dictionaryStore: CustomDictionaryStore
) {
    val state by dictionaryStore.state.collectAsState()
    var newWordsInput by remember { mutableStateOf("") }
    var repFromInput by remember { mutableStateOf("") }
    var repToInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dict_learning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.dict_hints_title), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.dict_hints_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.useAsHintsEnabled,
                        onCheckedChange = { dictionaryStore.setUseAsHintsEnabled(it) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.dict_autolearn_title), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.dict_autolearn_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.autoLearnEnabled,
                        onCheckedChange = { dictionaryStore.setAutoLearnEnabled(it) }
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dict_words_title, state.customWords.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.dict_words_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = newWordsInput,
                    onValueChange = { newWordsInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dict_words_label)) },
                    placeholder = { Text(stringResource(R.string.dict_words_placeholder)) },
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        dictionaryStore.addCustomWordsBulk(newWordsInput)
                        newWordsInput = ""
                    },
                    enabled = newWordsInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.dict_words_add_btn))
                }

                if (state.customWords.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.customWords.forEach { word ->
                            FilterChip(
                                selected = true,
                                onClick = { dictionaryStore.removeCustomWord(word) },
                                label = { Text("$word ✕") }
                            )
                        }
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dict_rep_title, state.replacements.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.dict_rep_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = repFromInput,
                        onValueChange = { repFromInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.dict_rep_from)) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = repToInput,
                        onValueChange = { repToInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.dict_rep_to)) },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                Button(
                    onClick = {
                        dictionaryStore.addReplacement(repFromInput, repToInput)
                        repFromInput = ""
                        repToInput = ""
                    },
                    enabled = repFromInput.isNotBlank() && repToInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.dict_rep_add_btn))
                }

                state.replacements.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${rule.from}  →  ${rule.to}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { dictionaryStore.removeReplacement(rule.from) }) {
                            Text(stringResource(R.string.common_delete))
                        }
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dict_learned_title, state.learnedPhrases.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.learnedPhrases.isNotEmpty()) {
                        TextButton(onClick = { dictionaryStore.clearLearnedPhrases() }) {
                            Text(stringResource(R.string.dict_learned_clear))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.dict_learned_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.learnedPhrases.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dict_learned_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.learnedPhrases.take(30).forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (item.sourceWord.isNotBlank()) {
                                        "${item.sourceWord} → ${item.phrase}"
                                    } else {
                                        item.phrase
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.dict_learned_count, item.count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (item.sourceWord.isNotBlank()) {
                                            dictionaryStore.promoteLearnedToReplacement(item)
                                        } else {
                                            dictionaryStore.promoteLearnedToCustomWord(item.phrase)
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.dict_learned_pin))
                                }
                                TextButton(onClick = { dictionaryStore.removeAndBlockLearned(item.phrase) }) {
                                    Text(stringResource(R.string.dict_learned_block))
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}
