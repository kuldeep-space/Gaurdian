package com.ai.guardian.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ai.guardian.data.dao.AppLockDao
import com.ai.guardian.data.dao.AuditEventDao
import com.ai.guardian.data.dao.ApprovalRequestDao
import com.ai.guardian.data.dao.DeviceSettingsDao
import com.ai.guardian.data.dao.FaceDao
import com.ai.guardian.data.dao.RecognitionHistoryDao
import com.ai.guardian.data.dao.PairedDeviceDao
import com.ai.guardian.data.entity.AppLockEntity
import com.ai.guardian.data.entity.AuditEventEntity
import com.ai.guardian.data.entity.ApprovalRequestEntity
import com.ai.guardian.data.entity.DeviceSettingsEntity
import com.ai.guardian.data.entity.FaceProfileEntity
import com.ai.guardian.data.entity.FaceTemplateEntity
import com.ai.guardian.data.entity.PairedDeviceEntity
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
        RecognitionHistoryEntity::class,
        PairedDeviceEntity::class,
        AuditEventEntity::class,
        ApprovalRequestEntity::class
    ],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun faceDao(): FaceDao
    abstract fun deviceSettingsDao(): DeviceSettingsDao
    abstract fun appLockDao(): AppLockDao
    abstract fun recognitionHistoryDao(): RecognitionHistoryDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun approvalRequestDao(): ApprovalRequestDao

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

                val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN matchingThreshold REAL NOT NULL DEFAULT 0.75")
                    }
                }

                val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `paired_devices` (`uuid` TEXT NOT NULL, `deviceName` TEXT NOT NULL, `pairingKey` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`uuid`))")
                    }
                }

                val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE app_lock_rules ADD COLUMN lastServerTimestamp INTEGER NOT NULL DEFAULT 0")
                    }
                }

                val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `audit_events` (`id` TEXT NOT NULL, `eventType` TEXT NOT NULL, `detail` TEXT NOT NULL DEFAULT '', `timestamp` INTEGER NOT NULL, `isSynced` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `approval_requests` (`requestId` TEXT NOT NULL, `action` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING', `requestedAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `tokenNonce` TEXT NOT NULL, PRIMARY KEY(`requestId`))")
                    }
                }

                val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // 0 = false (this is a Child device we manage), 1 = true (this is the Parent above us)
                        db.execSQL("ALTER TABLE paired_devices ADD COLUMN isParentDevice INTEGER NOT NULL DEFAULT 0")
                    }
                }

                val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN configurationVersion INTEGER NOT NULL DEFAULT 1")
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN isRemotelyLocked INTEGER NOT NULL DEFAULT 0")
                    }
                }

                val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN securityPinHash TEXT")
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN securityPinIv TEXT")
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN securityPinSalt TEXT")
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN isPinConfigured INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN pinVersion INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN pinUpdatedAt INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE device_settings ADD COLUMN pinResetRequired INTEGER NOT NULL DEFAULT 0")
                    }
                }

                val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE paired_devices ADD COLUMN parentPublicKeyId TEXT")
                        db.execSQL("ALTER TABLE paired_devices ADD COLUMN parentPublicKeyPem TEXT")
                        db.execSQL("ALTER TABLE paired_devices ADD COLUMN parentKeyVersion INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE paired_devices ADD COLUMN parentKeyCreatedAt INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE paired_devices ADD COLUMN parentKeyExpiresAt INTEGER")
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
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
