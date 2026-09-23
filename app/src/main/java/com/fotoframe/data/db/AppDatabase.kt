package com.fotoframe.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Photo::class], version = 7, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Разрушающего отката здесь нет намеренно: забытая миграция должна
         * упасть при первом запуске у разработчика, а не молча стереть
         * историю показов и найденные лица на приставке.
         *
         * Единственное исключение — версия 1: миграции с неё не сохранилось,
         * а истории показов в ней ещё не было, терять нечего. Без этой
         * строки приставка с самой первой сборкой падала бы при запуске.
         */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fotoframe.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { instance = it }
            }

        /** Признак «EXIF прочитан». */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE photos ADD COLUMN metaDone INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Счётчики попыток дообработки и признак настоящей даты съёмки.
         * У снимков с устройства и Яндекса дата обычно настоящая уже при
         * индексации, но выяснить это задним числом нельзя — флаг для них
         * выставит повторный EXIF-проход и следующий обход индекса.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN takenAtExact INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photos ADD COLUMN metaTries INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photos ADD COLUMN faceTries INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photos ADD COLUMN placeTries INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Скрытые пользователем снимки. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Перцептивный хэш для отсева серий. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN phash INTEGER")
            }
        }

        /** Ручной поворот снимка. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN rotation INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
