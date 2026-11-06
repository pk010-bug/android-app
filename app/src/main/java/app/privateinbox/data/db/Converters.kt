package app.privateinbox.data.db

import androidx.room.TypeConverter
import app.privateinbox.domain.Category
import app.privateinbox.domain.MessageStatus
import app.privateinbox.domain.TransactionType

class Converters {
    @TypeConverter fun categoryToString(value: Category?): String? = value?.name
    @TypeConverter fun stringToCategory(value: String?): Category? = value?.let { Category.fromName(it) ?: Category.UNKNOWN }

    @TypeConverter fun txTypeToString(value: TransactionType): String = value.name
    @TypeConverter fun stringToTxType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter fun schedToString(value: ScheduledStatus): String = value.name
    @TypeConverter fun stringToSched(value: String): ScheduledStatus =
        runCatching { ScheduledStatus.valueOf(value) }.getOrDefault(ScheduledStatus.CANCELLED)

    @TypeConverter fun statusToString(value: MessageStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): MessageStatus =
        runCatching { MessageStatus.valueOf(value) }.getOrDefault(MessageStatus.RECEIVED)
}
