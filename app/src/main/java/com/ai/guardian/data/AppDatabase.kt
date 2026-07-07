package com.ai.guardian.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ai.guardian.data.dao.AppLockDao
import com.ai.guardian.data.dao.DeviceSettingsDao
import com.ai.guardian.data.dao.FaceDao
import com.ai.guardian.data.dao.RecognitionHistoryDao
import com.ai.guardian.data.entity.AppLockEntity
import com.ai.guardian.data.entity.DeviceSettingsEntity
import com.ai.guardian.data.entity.FaceProfileEntity
import com.ai.guardian.data.entity.FaceTemplateEntity
import com.ai.guardian.data.entity.RecognitionHistoryEntity

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        FaceProfileEntity::class,
        FaceTemplateEntity::class,
        DeviceSettingsEntity::class,
        AppLockEntity::class,
        RecognitionHistoryEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun faceDao(): FaceDao
    abstract fun deviceSettingsDao(): DeviceSettingsDao
    abstract fun appLockDao(): AppLockDao
    abstract fun recognitionHistoryDao(): RecognitionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                SQLiteDatabase.loadLibs(context.applicationContext)
                
                val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN showLockScreenOverlay INTEGER NOT NULL DEFAULT 1")
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN trustedAuthDurationMinutes INTEGER NOT NULL DEFAULT 1")
                    }
                }
                
                val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        try {
                            db.execSQL("CREATE TABLE IF NOT EXISTS `face_templates_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` INTEGER NOT NULL, `embeddingData` BLOB NOT NULL, FOREIGN KEY(`profileId`) REFERENCES `face_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_templates_new_profileId` ON `face_templates_new` (`profileId`)")
                            
                            val cursor = db.query("SELECT id, profileId, embeddingData FROM face_templates")
                            if (cursor.moveToFirst()) {
                                val stmt = db.compileStatement("INSERT INTO face_templates_new (id, profileId, embeddingData) VALUES (?, ?, ?)")
                                do {
                                    val id = cursor.getLong(0)
                                    val profileId = cursor.getLong(1)
                                    val dataStr = cursor.getString(2)
                                    val floatArr = dataStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                                    val byteBuf = java.nio.ByteBuffer.allocate(floatArr.size * 4)
                                    floatArr.forEach { byteBuf.putFloat(it) }
                                    
                                    stmt.bindLong(1, id)
                                    stmt.bindLong(2, profileId)
                                    stmt.bindBlob(3, byteBuf.array())
                                    stmt.executeInsert()
                                } while (cursor.moveToNext())
                            }
                            cursor.close()
                            
                            db.execSQL("DROP TABLE face_templates")
                            db.execSQL("ALTER TABLE face_templates_new RENAME TO face_templates")
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_templates_profileId` ON `face_templates` (`profileId`)")
                        } catch (e: Exception) {
                            // If migration fails (e.g. malformed data), we drop the templates rather than bricking the app.
                            // The user will need to re-enroll their face, but the app remains usable.
                            db.execSQL("DROP TABLE IF EXISTS face_templates_new")
                            db.execSQL("DELETE FROM face_templates")
                            db.execSQL("CREATE TABLE IF NOT EXISTS `face_templates_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` INTEGER NOT NULL, `embeddingData` BLOB NOT NULL, FOREIGN KEY(`profileId`) REFERENCES `face_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                            db.execSQL("DROP TABLE IF EXISTS face_templates")
                            db.execSQL("ALTER TABLE face_templates_new RENAME TO face_templates")
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_templates_profileId` ON `face_templates` (`profileId`)")
                        }
                    }
                }

                val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE face_profiles ADD COLUMN enrollmentLuminance INTEGER NOT NULL DEFAULT 130")
                        db.execSQL("ALTER TABLE face_profiles ADD COLUMN enrollmentBlurScore INTEGER NOT NULL DEFAULT 30")
                    }
                }

                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val sharedPreferences = EncryptedSharedPreferences.create(
                    context,
                    "guardian_secure_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                var dbPassphrase = sharedPreferences.getString("db_passphrase", null)
                val isNewKey = dbPassphrase == null
                if (isNewKey) {
                    dbPassphrase = java.util.UUID.randomUUID().toString()
                    sharedPreferences.edit().putString("db_passphrase", dbPassphrase).apply()
                }

                // SECURITY FIX: Prevent crash if an old unencrypted database exists, or if the encryption key was lost.
                // An unencrypted SQLite database starts with "SQLite format 3". 
                // SQLCipher encrypted databases start with random salt.
                val dbFile = context.getDatabasePath("guardian_database")
                if (dbFile.exists()) {
                    try {
                        val header = ByteArray(16)
                        java.io.FileInputStream(dbFile).use { it.read(header) }
                        val isPlaintext = String(header).startsWith("SQLite format 3")
                        
                        // Delete if it's plaintext (from older app version) or if we just generated a new key (old encrypted DB is unrecoverable)
                        if (isPlaintext || isNewKey) {
                            dbFile.delete()
                            context.getDatabasePath("guardian_database-wal").delete()
                            context.getDatabasePath("guardian_database-shm").delete()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val factory = SupportFactory(dbPassphrase.toByteArray())

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "guardian_database"
                )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
