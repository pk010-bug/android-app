package app.privateinbox.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.privateinbox.MainActivity
import app.privateinbox.PrivateInboxApp
import app.privateinbox.R
import app.privateinbox.domain.Category
import app.privateinbox.navigation.DeepLinks
import app.privateinbox.notifications.OtpNotifier
import app.privateinbox.security.PendingIntents
import app.privateinbox.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Guarded by BIND_APPWIDGET in the manifest; only reacts to APPWIDGET_UPDATE. */
class InboxWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { InboxWidget.render(context.applicationContext, ids) } finally { pending.finish() }
        }
    }
}

object InboxWidget {
    /** Re-renders every placed widget; no-op (no DB access) when none are placed. */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        val ids = runCatching {
            AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, InboxWidgetProvider::class.java))
        }.getOrNull() ?: return
        if (ids.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch { render(app, ids) }
    }

    suspend fun render(context: Context, ids: IntArray) {
        try {
            val c = (context as PrivateInboxApp).container
            val locked = c.settings.appLockEnabled.first()
            val showNames = c.settings.widgetShowNames.first()
            val byCategory = c.database.conversationDao().observeUnreadByCategory().first()
                .filter { it.category != Category.SPAM }
                .associate { it.category to it.unread }
            val names = if (!locked && showNames) {
                c.database.conversationDao().recentUnread(3).map { conv ->
                    conv.displayName ?: if (conv.isGroup) "Group" else "••" + conv.address.filter(Char::isDigit).takeLast(4)
                }
            } else emptyList()
            val model = WidgetContent.build(locked, showNames, byCategory, names)
            val views = RemoteViews(context.packageName, R.layout.widget_inbox).apply {
                setTextViewText(R.id.widget_total, model.total)
                setTextViewText(R.id.widget_categories, model.categories)
                setViewVisibility(R.id.widget_recent, if (model.recent != null) View.VISIBLE else View.GONE)
                setTextViewText(R.id.widget_recent, model.recent.orEmpty())
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntents.activity(
                        context, PendingIntents.Kind.WIDGET_OPEN, 0L,
                        Intent(context, MainActivity::class.java)
                            .setAction(OtpNotifier.ACTION_OPEN_ROUTE)
                            .putExtra(DeepLinks.EXTRA_ROUTE, "inbox")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    ),
                )
            }
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        } catch (e: Exception) {
            SafeLog.e("InboxWidget", "widget render failed", e)
        }
    }
}
