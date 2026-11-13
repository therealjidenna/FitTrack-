package com.example.fittrack.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val steps: Int,
    val distanceMeters: Float,
    /** "lat,lng;lat,lng;..." */
    val route: String
)

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insert(workout: Workout)

    @Query("SELECT * FROM workouts ORDER BY startTime DESC")
    fun all(): Flow<List<Workout>>

    @Delete
    suspend fun delete(workout: Workout)
}

@Database(entities = [Workout::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): WorkoutDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "fittrack.db"
                ).build().also { instance = it }
            }
    }
}
