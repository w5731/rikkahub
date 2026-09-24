package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MarkdownRemoteImageEntity

@Dao
interface MarkdownRemoteImageDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MarkdownRemoteImageEntity): Long

    @Query("SELECT * FROM markdown_remote_images WHERE id = :id")
    suspend fun getById(id: String): MarkdownRemoteImageEntity?

    @Query("SELECT * FROM markdown_remote_images WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MarkdownRemoteImageEntity>

    @Query("SELECT * FROM markdown_remote_images ORDER BY created_at DESC")
    fun observeAll(): Flow<List<MarkdownRemoteImageEntity>>

    @Query(
        "SELECT * FROM markdown_remote_images " +
            "WHERE source_url LIKE '%/api/aurora/regex-image?tag=%' ORDER BY created_at DESC"
    )
    suspend fun getAuroraImages(): List<MarkdownRemoteImageEntity>

    @Query("DELETE FROM markdown_remote_images WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
