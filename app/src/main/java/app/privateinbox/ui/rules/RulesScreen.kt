package app.privateinbox.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.privateinbox.data.db.RuleEntity
import app.privateinbox.data.rules.RegexRuleEngine
import app.privateinbox.data.rules.RuleRepository
import app.privateinbox.domain.Category
import app.privateinbox.domain.SmsMessage
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.common.Pill
import app.privateinbox.ui.common.SubScreen
import app.privateinbox.ui.common.categoryColor
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Smart inbox rules manager: your rules (editable, always win) + built-in rules (read-only, shown
 * so sorting is never a black box) + a tester.
 */
@Composable
fun RulesScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val userRules by remember { c.database.ruleDao().observeAll() }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<RuleEntity?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun saveAndApply(block: suspend () -> Unit) = scope.launch {
        block()
        c.messages.recategorizeAll()
    }

    Box(Modifier.fillMaxSize()) {
        SubScreen("Smart inbox rules", onBack, actions = { TextButton(onClick = { testing = true }) { Text("Test") } }) {
            LazyColumn(Modifier.weight(1f)) {
                item { Header("Your rules (checked first)") }
                if (userRules.isEmpty()) {
                    item {
                        Text(
                            "None yet. Tap + to add one, or use \"Move to category\" in a conversation.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(userRules, key = { it.id }) { r ->
                    ListItem(
                        headlineContent = { Text(r.description.ifBlank { r.id }) },
                        supportingContent = { Text(patternSummary(r.senderPatterns, r.bodyPatterns)) },
                        leadingContent = { Pill(r.category.label, categoryColor(r.category)) },
                        trailingContent = {
                            Switch(r.enabled, onCheckedChange = { on -> saveAndApply { c.rules.save(r.copy(enabled = on)) } })
                        },
                        modifier = Modifier.clickable { editing = r },
                    )
                    HorizontalDivider()
                }
                item { Header("Built-in rules") }
                items(c.rules.defaultRules, key = { "d-" + it.id }) { r ->
                    val cat = Category.fromName(r.category) ?: Category.UNKNOWN
                    ListItem(
                        headlineContent = { Text(r.description.ifBlank { r.id }) },
                        supportingContent = { Text(patternSummary(r.senderPatterns.joinToString("\n"), r.bodyPatterns.joinToString("\n"))) },
                        leadingContent = { Pill(cat.label, categoryColor(cat)) },
                        overlineContent = { Text("priority ${r.priority}") },
                    )
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = {
                editing = RuleEntity(id = RuleRepository.USER_PREFIX + UUID.randomUUID().toString().take(8), category = Category.IMPORTANT)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Outlined.Add, contentDescription = "Add rule") }
    }

    editing?.let { r ->
        RuleEditor(
            initial = r,
            isNew = userRules.none { it.id == r.id },
            onDismiss = { editing = null },
            onSave = { saved -> editing = null; saveAndApply { c.rules.save(saved) } },
            onDelete = { editing = null; saveAndApply { c.rules.delete(r.id) } },
        )
    }
    if (testing) RuleTester(onDismiss = { testing = false })
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 12.dp))
}

private fun patternSummary(sender: String, body: String): String = buildString {
    if (sender.isNotBlank()) append("From: ").append(sender.lines().joinToString(" | ")).append('\n')
    if (body.isNotBlank()) append("Text: ").append(body.lines().joinToString(" | "))
}.trim().take(160)

@Composable
private fun RuleEditor(initial: RuleEntity, isNew: Boolean, onDismiss: () -> Unit, onSave: (RuleEntity) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(initial.description) }
    var category by remember { mutableStateOf(initial.category) }
    var sender by remember { mutableStateOf(initial.senderPatterns) }
    var body by remember { mutableStateOf(initial.bodyPatterns) }
    var exclude by remember { mutableStateOf(initial.excludeBodyPatterns) }
    var priority by remember { mutableStateOf(initial.priority.toString()) }
    var catMenu by remember { mutableStateOf(false) }
    val error = RuleRepository.validate(sender, body, exclude)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { catMenu = true }) { Text("Category: ${category.label}") }
                    DropdownMenu(catMenu, { catMenu = false }) {
                        Category.entries.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat.label) }, onClick = { category = cat; catMenu = false })
                        }
                    }
                }
                OutlinedTextField(sender, { sender = it }, label = { Text("Sender patterns (one regex per line)") }, minLines = 2)
                OutlinedTextField(body, { body = it }, label = { Text("Message patterns (one per line)") }, minLines = 2)
                OutlinedTextField(exclude, { exclude = it }, label = { Text("Exclude if message matches") }, minLines = 1)
                OutlinedTextField(priority, { priority = it.filter(Char::isDigit).take(3) }, label = { Text("Priority (0-999)") }, singleLine = true)
                Text("Patterns are case-insensitive regular expressions.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = {
                onSave(
                    initial.copy(
                        description = name.trim(),
                        category = category,
                        senderPatterns = sender.trim(),
                        bodyPatterns = body.trim(),
                        excludeBodyPatterns = exclude.trim(),
                        priority = priority.toIntOrNull()?.coerceIn(0, 999) ?: 0,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun RuleTester(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    var sender by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test rules") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sender, { sender = it }, label = { Text("Sender") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val engine: RegexRuleEngine = c.rules.engine()
                    val r = engine.classify(SmsMessage(address = sender, body = body, timestampMillis = 0))
                    result = "→ ${r.category.label}" + (r.ruleId?.let { " (rule $it)" } ?: " (no rule matched)")
                }
            }) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

