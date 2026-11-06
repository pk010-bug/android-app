package app.privateinbox.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.privateinbox.data.finance.FinanceSummary
import app.privateinbox.domain.TransactionType
import app.privateinbox.security.SecureScreen
import app.privateinbox.ui.EmptyState
import app.privateinbox.ui.common.Format
import app.privateinbox.ui.common.LocalContainer
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun FinanceScreen() {
    val c = LocalContainer.current
    val secure by c.settings.secureScreens.collectAsState(initial = true)
    SecureScreen(enabled = secure)
    val all by remember { c.database.transactionDao().observeAll() }.collectAsState(initial = emptyList())
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    val month = YearMonth.now().minusMonths(monthOffset.toLong())
    val summary = remember(all, month) { FinanceSummary.forMonth(all, month) }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Finance", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { monthOffset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                Text(month.format(DateTimeFormatter.ofPattern("MMM yyyy")))
                IconButton(enabled = monthOffset > 0, onClick = { monthOffset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Spent", Format.money(summary.spentMinor), MaterialTheme.colorScheme.error, Modifier.weight(1f))
                StatCard("Received", Format.money(summary.receivedMinor), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }
        }
        if (summary.topMerchants.isNotEmpty()) {
            item { Text("Where it went", style = MaterialTheme.typography.titleMedium) }
            items(summary.topMerchants) { (merchant, amount) ->
                val frac = if (summary.spentMinor > 0) amount.toFloat() / summary.spentMinor else 0f
                Column {
                    Row { Text(merchant, Modifier.weight(1f)); Text(Format.money(amount)) }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
        item { Text("Transactions", style = MaterialTheme.typography.titleMedium) }
        if (summary.transactions.isEmpty()) {
            item { EmptyState("No bank or UPI transactions found for this month.\nThey're read from your bank SMS, on this phone only.") }
        }
        items(summary.transactions, key = { it.id }) { t ->
            ListItem(
                headlineContent = { Text(t.merchant ?: t.sender) },
                supportingContent = {
                    Text(listOfNotNull(Format.shortTime(t.timestamp), t.accountHint?.let { "a/c ··$it" }, t.sender).joinToString(" · "))
                },
                trailingContent = {
                    Text(
                        (if (t.type == TransactionType.DEBIT) "−" else "+") + Format.money(t.amountMinor),
                        color = if (t.type == TransactionType.DEBIT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        }
    }
}
