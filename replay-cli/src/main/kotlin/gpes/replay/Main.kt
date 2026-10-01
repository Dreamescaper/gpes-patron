package gpes.replay

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import gpes.core.io.DriveJson
import gpes.core.model.Measurement
import gpes.core.replay.Metrics
import gpes.core.replay.ReplayResult
import gpes.core.replay.ReplayRunner
import gpes.core.replay.ReplaySummary
import gpes.core.replay.Scenario
import gpes.core.replay.TruthTrack
import gpes.core.replay.Variant
import gpes.core.sim.DriveSimulator
import gpes.core.sim.SimConfig
import gpes.recording.Exporters
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

fun main(args: Array<String>) = Replay().subcommands(Simulate(), Export(), Run(), Matrix(), CompassReportCmd()).main(args)

class Replay : CliktCommand(name = "replay") {
    override fun help(context: Context) =
        "Offline tooling for GNSS-resilient location: simulate drives, export recordings, and replay them with injected GNSS faults and metrics."
    override fun run() = Unit
}

class Simulate : CliktCommand(name = "simulate") {
    override fun help(context: Context) = "Generate a synthetic drive (.db or .jsonl) for testing the replay flow without a device."
    private val out by option("--out", help = "output .db or .jsonl").file().required()
    private val config by option("--config", help = "SimConfig JSON (optional)").file(mustExist = true)
    private val seed by option("--seed").long().default(1)
    private val network by option("--network-period", help = "seconds between synthetic network fixes (0 = none)").double().default(0.0)

    override fun run() {
        val base = config?.let { DriveJson.json.decodeFromString(SimConfig.serializer(), it.readText()) } ?: SimConfig()
        val drive = DriveSimulator.generate(base.copy(seed = seed, networkPeriodS = if (network > 0) network else base.networkPeriodS))
        DriveIo.save(drive.records, out)
        val truthFile = File(out.absoluteFile.parentFile, out.nameWithoutExtension + ".truth.json")
        truthFile.writeText(DriveJson.json.encodeToString(ListSerializer(gpes.core.sim.TruthSample.serializer()), drive.truth))
        echo("wrote truth to $truthFile")
        echo("wrote ${drive.records.size} records (${"%.0f".format((drive.truth.last().tNs - drive.truth.first().tNs) / 1e9)} s) to $out")
    }
}

class Export : CliktCommand(name = "export") {
    override fun help(context: Context) = "Export a drive to JSONL, per-table CSV, or GnssLogger text format."
    private val drive by option("--drive").file(mustExist = true).required()
    private val format by option("--format").choice("jsonl", "csv", "gnsslogger").default("jsonl")
    private val out by option("--out", help = "output file (jsonl/gnsslogger) or directory (csv)").file().required()

    override fun run() {
        val records = DriveIo.load(drive)
        out.absoluteFile.parentFile?.mkdirs()
        when (format) {
            "jsonl" -> out.bufferedWriter().use { Exporters.jsonl(records, it) }
            "csv" -> Exporters.csv(records, out).forEach { echo("wrote $it") }
            "gnsslogger" -> out.bufferedWriter().use { Exporters.gnssLogger(records, it) }
        }
        echo("exported ${records.size} records from $drive as $format")
    }
}

private fun loadScenarios(files: List<File>): List<Scenario> =
    files.flatMap { f -> if (f.isDirectory) f.listFiles { x -> x.name.endsWith(".json") }!!.sorted() else listOf(f) }
        .map { DriveJson.json.decodeFromString(Scenario.serializer(), it.readText()) }
        .ifEmpty { listOf(Scenario("clean")) }

private fun loadVariants(file: File?, names: List<String>): List<Variant> {
    val all = file?.let { DriveJson.json.decodeFromString(ListSerializer(Variant.serializer()), it.readText()) } ?: Variant.standard()
    return if (names.isEmpty()) all else names.map { n -> all.firstOrNull { it.name == n } ?: error("unknown variant '$n' (have ${all.map { it.name }})") }
}

private fun writeResult(r: ReplayResult, s: ReplaySummary, dir: File) {
    dir.mkdirs()
    val rows = Metrics.rows(r)
    File(dir, "summary.json").writeText(pretty.encodeToString(ReplaySummary.serializer(), s))
    File(dir, "ticks.csv").bufferedWriter().use { w ->
        w.write("t_s,est_lat,est_lon,r68_m,mode,truth_lat,truth_lon,err_m,heading_err_deg,degraded,since_degraded_s,est_speed_mps,est_heading_deg,truth_speed_mps,truth_heading_deg\n")
        for ((x, e) in rows.zip(r.estimates)) {
            val tr = r.truth.at(e.tNs)
            w.write(
                listOf(
                    x.tS, x.estLat, x.estLon, x.r68, x.mode, x.truthLat, x.truthLon, x.errM, x.headingErrDeg, x.degradedKind, x.sinceDegradedStartS,
                    e.speedMps, e.headingRad?.let { Math.toDegrees(it) }, tr?.speedMps, tr?.bearingDeg?.takeIf { !it.isNaN() },
                ).joinToString(",") { it?.toString() ?: "" } + "\n",
            )
        }
    }
    File(dir, "error_vs_time.csv").bufferedWriter().use { w ->
        w.write("since_degraded_s,p50_err_m,p95_err_m,n\n")
        for (b in Metrics.errorVsTimeSinceDegraded(rows)) w.write(b.joinToString(",") + "\n")
    }
    File(dir, "trust.csv").bufferedWriter().use { w ->
        val labels = r.labels.associateBy { it.tNs to it.provider }
        w.write("t_s,source,provider,state,confidence,reasons,nis,implied_speed,injected_offset_m\n")
        for (a in r.trust) w.write(
            listOf((a.tNs - r.t0Ns) / 1e9, a.source, a.provider, a.state, a.confidence, a.reasons.joinToString("|"), a.innovationNis, a.impliedSpeedMps, labels[a.tNs to a.provider]?.offsetM)
                .joinToString(",") { it?.toString() ?: "" } + "\n",
        )
    }
}

private val pretty = kotlinx.serialization.json.Json(DriveJson.json) { prettyPrint = true; encodeDefaults = true }

private fun fmt(d: Double?, digits: Int = 1) = d?.let { "%.${digits}f".format(it) } ?: "–"

private fun truthOrNull(truth: File?): TruthTrack? = truth?.let {
    TruthTrack(DriveJson.json.decodeFromString(ListSerializer(gpes.core.sim.TruthSample.serializer()), it.readText()))
}

class Run : CliktCommand(name = "run") {
    override fun help(context: Context) = "Replay one drive with one or more scenarios and variants; write per-run metrics."
    private val drive by option("--drive").file(mustExist = true).required()
    private val scenarios by option("--scenario", help = "scenario JSON file or directory (repeatable)").file(mustExist = true).multiple()
    private val variantsFile by option("--variants", help = "variants JSON list (default: standard ladder)").file(mustExist = true)
    private val variantNames by option("--variant", help = "variant name(s) to run (default: all)").multiple()
    private val out by option("--out").file().default(File("replay-out"))
    private val truthFile by option("--truth", help = "optional truth JSON (list of TruthSample), e.g. from simulate").file(mustExist = true)

    override fun run() {
        val ms = DriveIo.load(drive).filterIsInstance<Measurement>()
        echo("loaded ${ms.size} measurements from $drive")
        val runner = ReplayRunner()
        val truth = truthOrNull(truthFile) ?: runner.truthFrom(ms)
        echo("truth: ${truth.size} samples")
        val summaries = ArrayList<ReplaySummary>()
        for (sc in loadScenarios(scenarios)) for (v in loadVariants(variantsFile, variantNames)) {
            val r = runner.run(ms, sc, v, truth)
            val s = Metrics.summarize(r)
            summaries += s
            writeResult(r, s, File(out, "${sc.name}__${v.name}"))
            echo("${sc.name} / ${v.name}: p50=${fmt(s.p50M)} p95=${fmt(s.p95M)} max=${fmt(s.maxM)} m, degraded p95=${fmt(s.degradedP95M)} m, within68=${fmt(s.within68, 2)}")
        }
        writeComparison(summaries, out)
    }
}

class Matrix : CliktCommand(name = "matrix") {
    override fun help(context: Context) = "Run every scenario × variant over one or more drives and write a comparison table."
    private val drives by option("--drive").file(mustExist = true).multiple(required = true)
    private val scenarios by option("--scenario").file(mustExist = true).multiple()
    private val variantsFile by option("--variants").file(mustExist = true)
    private val out by option("--out").file().default(File("replay-out"))
    private val truthFile by option("--truth", help = "optional truth JSON (list of TruthSample), for simulated drives").file(mustExist = true)

    override fun run() {
        val summaries = ArrayList<ReplaySummary>()
        val runner = ReplayRunner()
        for (d in drives) {
            val ms = DriveIo.load(d).filterIsInstance<Measurement>()
            val truth = truthOrNull(truthFile) ?: runner.truthFrom(ms)
            for (sc in loadScenarios(scenarios)) for (v in loadVariants(variantsFile, emptyList())) {
                val r = runner.run(ms, sc, v, truth)
                val s = Metrics.summarize(r)
                summaries += s
                writeResult(r, s, File(out, "${d.nameWithoutExtension}/${sc.name}__${v.name}"))
            }
            echo("done ${d.name}")
        }
        writeComparison(summaries, out)
    }
}

private fun writeComparison(summaries: List<ReplaySummary>, out: File) {
    out.mkdirs()
    val header = listOf(
        "scenario", "variant", "rmse_m", "p50_m", "p95_m", "max_m", "degraded_rmse_m", "degraded_p95_m", "heading_p95_deg",
        "within68", "within95", "t_exceed_100m_s", "t_exceed_500m_s", "recovery_s", "false_reject_rate", "missed_detect_rate", "detect_latency_s",
    )
    val rows = summaries.map { s ->
        val w = s.windows.firstOrNull()
        listOf(
            s.scenario, s.variant, fmt(s.rmseM), fmt(s.p50M), fmt(s.p95M), fmt(s.maxM), fmt(s.degradedRmseM), fmt(s.degradedP95M), fmt(s.headingP95Deg),
            fmt(s.within68, 2), fmt(s.within95, 2), fmt(w?.timeToExceedS?.get("100m")), fmt(w?.timeToExceedS?.get("500m")), fmt(w?.recoveryS),
            fmt(s.trust.falseRejectionRate, 3), fmt(s.trust.missedDetectionRate, 3), fmt(s.trust.detectionLatencyS.firstOrNull()),
        )
    }
    File(out, "comparison.csv").writeText((listOf(header) + rows).joinToString("\n") { it.joinToString(",") } + "\n")
    File(out, "comparison.md").writeText(
        buildString {
            append("| ").append(header.joinToString(" | ")).append(" |\n")
            append("|").append(header.joinToString("|") { "---" }).append("|\n")
            rows.forEach { append("| ").append(it.joinToString(" | ")).append(" |\n") }
        },
    )
    File(out, "summaries.json").writeText(pretty.encodeToString(ListSerializer(ReplaySummary.serializer()), summaries))
}

class CompassReportCmd : CliktCommand(name = "compass-report") {
    override fun help(context: Context) =
        "Assess the magnetometer on this mount: usable / marginal / unusable, why, and held-out heading accuracy vs trusted GNSS course."
    private val drive by option("--drive").file(mustExist = true).required()
    private val truthFile by option("--truth", help = "optional truth JSON (simulated drives)").file(mustExist = true)
    private val out by option("--out").file().default(File("replay-out/compass"))

    override fun run() {
        val records = DriveIo.load(drive)
        val ms = records.filterIsInstance<Measurement>()
        val truth = truthOrNull(truthFile) ?: ReplayRunner().truthFrom(ms)
        val (summary, rows) = gpes.core.replay.CompassReport.analyze(ms, truth, records.filterIsInstance<gpes.core.model.SensorInfo>())
        out.mkdirs()
        File(out, "compass_report.json").writeText(pretty.encodeToString(gpes.core.replay.CompassReportSummary.serializer(), summary))
        File(out, "compass_timeline.csv").bufferedWriter().use { w ->
            w.write("t_s,verdict,mode,bearing_deg,sigma_deg,truth_course_deg,err_deg,tilt_rate_rms,horizontal_accel,raw_norm_ut,plug\n")
            for (r in rows) w.write(
                listOf(r.tS, r.verdict, r.mode, r.bearingDeg, r.sigmaDeg, r.truthCourseDeg, r.errDeg, r.tiltRateRms, r.horizontalAccel, r.rawNormUt, r.plug)
                    .joinToString(",") { it?.toString() ?: "" } + "\n",
            )
        }
        val q = summary.quality
        echo("verdict: ${q.verdict} ${q.reasons}")
        echo("fit: ${q.fit}, octants ${q.octants}, radius/expected ${fmt(q.radiusRatio, 2)}, scatter ${fmt(q.scatterDeg)}°, hard iron ${fmt(q.hardIronUt)} µT, centre drift ${fmt(q.centerDriftRatio, 2)}")
        echo("disturbed ${fmt(q.dirtyFraction, 2)}, shaky ${fmt(q.shakyFraction, 2)}, tilt-rate RMS ${fmt(q.tiltRateRmsMean, 3)} rad/s, saturated ${q.saturated}, near full scale ${summary.nearFullScale}, wireless charging ${fmt(summary.wirelessChargingSeconds, 0)} s")
        echo("held-out heading error: p50 ${fmt(summary.heldOutAll.p50Deg)}°, p95 ${fmt(summary.heldOutAll.p95Deg)}°, within 2σ ${fmt(summary.heldOutAll.within2SigmaFraction, 2)}, availability ${fmt(summary.availability, 2)}")
        for ((mode, st) in summary.heldOutByMode) echo("  $mode: n=${st.n} p50 ${fmt(st.p50Deg)}° p95 ${fmt(st.p95Deg)}° within 2σ ${fmt(st.within2SigmaFraction, 2)}")
        echo("wrote ${File(out, "compass_report.json")} and compass_timeline.csv")
    }
}
