package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_17_18_Test {
    private val testDb = "migration-17-18-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate17To18_preservesExistingDataAndCreatesRemoteImageTable() {
        val conversationId = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        val messages = "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"text\":\"[[aurora_draw tag=1girl]]\"}]}]"

        helper.createDatabase(testDb, 17).apply {
            insert(
                "ConversationEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", conversationId)
                    put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
                    put("title", "Aurora legacy")
                    put("nodes", "[]")
                    put("create_at", Instant.now().toEpochMilli())
                    put("update_at", Instant.now().toEpochMilli())
                    put("suggestions", "[]")
                    put("is_pinned", 0)
                },
            )
            insert(
                "message_node",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", nodeId)
                    put("conversation_id", conversationId)
                    put("node_index", 0)
                    put("messages", messages)
                    put("select_index", 0)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 18, true)

        db.query("SELECT title FROM ConversationEntity WHERE id = ?", arrayOf(conversationId)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Aurora legacy", cursor.getString(0))
        }
        db.query("SELECT messages FROM message_node WHERE id = ?", arrayOf(nodeId)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(messages, cursor.getString(0))
        }
        db.query("PRAGMA table_info(markdown_remote_images)").use { cursor ->
            val columns = buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertEquals(setOf("id", "source_url", "relative_path", "created_at"), columns)
        }
        db.query("PRAGMA index_list(markdown_remote_images)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var foundUniqueSourceUrl = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "index_markdown_remote_images_source_url") {
                    foundUniqueSourceUrl = cursor.getInt(cursor.getColumnIndex("unique")) == 1
                }
            }
            assertTrue(foundUniqueSourceUrl)
        }
        db.close()
    }
}
