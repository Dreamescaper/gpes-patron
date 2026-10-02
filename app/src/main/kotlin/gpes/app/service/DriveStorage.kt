package gpes.app.service

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import gpes.recording.DriveReader
import gpes.recording.Exporters
import gpes.recording.db.DriveDatabase
import java.io.File

/** Where drive bundles live, and how to open or export them. */
object DriveStorage {
    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "drives").also { it.mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".db") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Delete a drive with its SQLite WAL/SHM sidecars. */
    fun delete(file: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
    }

    fun open(context: Context, file: File): AndroidSqliteDriver = AndroidSqliteDriver(
        schema = DriveDatabase.Schema,
        context = context,
        name = file.absolutePath,
        callback = object : AndroidSqliteDriver.Callback(DriveDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.enableWriteAheadLogging()
            }
        },
    )

    /** Export a drive to JSONL and GnssLogger text in the cache dir (for sharing). Loads the whole drive in memory. */
    fun export(context: Context, file: File): List<File> {
        val outDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val driver = open(context, file)
        try {
            val records = DriveReader(DriveDatabase(driver)).allRecords()
            val jsonl = File(outDir, file.nameWithoutExtension + ".jsonl")
            jsonl.bufferedWriter().use { Exporters.jsonl(records, it) }
            val gl = File(outDir, file.nameWithoutExtension + ".gnsslogger.txt")
            gl.bufferedWriter().use { Exporters.gnssLogger(records, it) }
            return listOf(jsonl, gl)
        } finally {
            driver.close()
        }
    }
}
