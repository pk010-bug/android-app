package app.privateinbox.data.db

import androidx.room.TypeConverter
import app.privateinbox.domain.Category
import app.privateinbox.domain.TransactionType

class Converters {
    @TypeConverter fun categoryToString(value: Category): String = value.name
    @TypeConverter fun stringToCategory(value: String): Category =
        Category.fromName(value) ?: Category.UNKNOWN

    @TypeConverter fun txTypeToString(value: TransactionType): String = value.name
    @TypeConverter fun stringToTxType(value: String): TransactionType = TransactionType.valueOf(value)
}
