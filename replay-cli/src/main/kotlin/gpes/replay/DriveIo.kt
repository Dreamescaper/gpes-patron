package gpes.replay

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gpes.core.io.DriveJson
import gpes.core.model.DriveRecord
import gpes.core.model.RecordOrder
import gpes.recording.DriveReader
import gpes.recording.DriveWriter
import gpes.recording.db.DriveDatabase
import java.io.File

object DriveIo {
    /** Load all records of a drive from a `.db` bundle or a `.jsonl` export, in canonical order. */
    fun load(file: File): List<DriveRecord> {
        require(file.exists()) { "no such file: $file" }
        return when {
            file.name.endsWith(".jsonl") -> file.bufferedReader().use { r -> DriveJson.read(r).toList() }.sortedWith(RecordOrder.comparator)
            else -> openDb(file).use { DriveReader(it.db).allRecords() }
        }
    }

    fun save(records: List<DriveRecord>, file: File) {
        file.parentFile?.mkdirs()
        if (file.name.endsWith(".jsonl")) {
            file.bufferedWriter().use { DriveJson.write(records.asSequence(), it) }
        } else {
            file.delete()
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            DriveDatabase.Schema.create(driver)
            val w = DriveWriter(DriveDatabase(driver))
            records.forEach(w::write)
            w.flush()
            driver.close()
        }
    }

    private data class Opened(val driver: JdbcSqliteDriver, val db: DriveDatabase) : AutoCloseable {
        override fun close() = driver.close()
    }

    private fun openDb(file: File): Opened {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        return Opened(driver, DriveDatabase(driver))
    }
}
