package gpes.core.io

import gpes.core.model.DriveRecord
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.Writer

/**
 * JSONL drive format: one [DriveRecord] per line, with a `"type"` discriminator, in time order.
 * It is used for export and analysis, and is also accepted as replay input.
 */
object DriveJson {
    val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    fun encode(r: DriveRecord): String = json.encodeToString(DriveRecord.serializer(), r)

    fun decode(line: String): DriveRecord = json.decodeFromString(DriveRecord.serializer(), line)

    fun write(records: Sequence<DriveRecord>, out: Writer) {
        for (r in records) { out.write(encode(r)); out.write("\n") }
        out.flush()
    }

    fun read(reader: BufferedReader): Sequence<DriveRecord> =
        reader.lineSequence().filter { it.isNotBlank() }.map { decode(it) }
}
