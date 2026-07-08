package com.ai.guardian.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.guardian.data.entity.PairedDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY addedAt DESC")
    fun getAllPairedDevices(): Flow<List<PairedDeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPairedDevice(device: PairedDeviceEntity)

    @Delete
    suspend fun deletePairedDevice(device: PairedDeviceEntity)

    @Query("DELETE FROM paired_devices WHERE uuid = :uuid")
    suspend fun deleteDeviceByUuid(uuid: String)

    @Query("SELECT * FROM paired_devices WHERE uuid = :uuid LIMIT 1")
    suspend fun getDeviceByUuid(uuid: String): PairedDeviceEntity?

    @Query("SELECT * FROM paired_devices")
    suspend fun getAllPairedDevicesSynchronous(): List<PairedDeviceEntity>
    @Query("SELECT * FROM paired_devices WHERE isParentDevice = 1 LIMIT 1")
    suspend fun getParentDevice(): PairedDeviceEntity?
}
