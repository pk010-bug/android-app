package app.privateinbox.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.privateinbox.domain.Category
import app.privateinbox.ui.EmptyState
import app.privateinbox.ui.ScreenColumn

/** Placeholder rows until the inbox is wired to Room. */
private data class PreviewThread(val id: Long, val sender: String, val snippet: String, val category: Category)

private val sampleThreads = listOf(
    PreviewThread(1, "Mom", "Dinner at 8?", Category.PERSONAL),
    PreviewThread(2, "AX-BANKOTP", "Your OTP is 482913. Do not share it.", Category.OTP),
    PreviewThread(3, "VM-SHOPCO", "Flat 50% off this weekend only!", Category.PROMOTIONAL),
    PreviewThread(4, "JD-ELECTR", "Your electricity bill of Rs. 1,240 is due on 12 Nov.", Category.IMPORTANT),
)

@Composable
fun InboxScreen(onOpenConversation: (Long) -> Unit) {
    // null == "All"
    var selected by rememberSaveable { mutableStateOf<Category?>(null) }
    val threads = sampleThreads.filter { selected == null || it.category == selected }

    ScreenColumn(title = "Inbox") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text("All") })
            }
            items(Category.entries) { category ->
                FilterChip(
                    selected = selected == category,
                    onClick = { selected = category },
                    label = { Text(category.label) },
                )
            }
        }

        if (threads.isEmpty()) {
            EmptyState("No ${selected?.label ?: ""} messages yet.")
        } else {
            LazyColumn {
                items(threads, key = { it.id }) { thread ->
                    ListItem(
                        headlineContent = { Text(thread.sender) },
                        supportingContent = {
                            Text(thread.snippet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = { Text(thread.category.label) },
                        modifier = Modifier.clickable { onOpenConversation(thread.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
