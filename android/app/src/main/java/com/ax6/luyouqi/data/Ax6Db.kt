package com.ax6.luyouqi.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ax6.luyouqi.domain.HistoryPoint

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val t: Long,
    val down: Long,
    val up: Long,
)

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history WHERE t >= :since ORDER BY t ASC")
    suspend fun since(since: Long): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE t < :before")
    suspend fun prune(before: Long)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = false)
abstract class Ax6Db : RoomDatabase() {
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var instance: Ax6Db? = null

        fun get(context: Context): Ax6Db = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                Ax6Db::class.java,
                "ax6.db",
            ).build().also { instance = it }
        }
    }
}

/**
 * 速率历史只保留最近 1 小时（与网页版 history 接口的 3600 秒窗口一致），
 * 每 200 条采样剪一次，避免每个 tick 都删表。
 */
class HistoryStore(private val dao: HistoryDao) {

    private var inserts = 0

    suspend fun append(point: HistoryPoint) {
        dao.insert(HistoryEntity(point.t, point.down, point.up))
        inserts += 1
        if (inserts >= 200) {
            inserts = 0
            dao.prune(System.currentTimeMillis() - 4L * 3600 * 1000)
        }
    }

    suspend fun since(seconds: Int): List<HistoryPoint> {
        val since = System.currentTimeMillis() - seconds.toLong() * 1000
        return dao.since(since).map { HistoryPoint(it.t, it.down, it.up) }
    }
}
