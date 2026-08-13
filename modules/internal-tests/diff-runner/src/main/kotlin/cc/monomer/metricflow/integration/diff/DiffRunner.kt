package cc.monomer.metricflow.integration.diff

import java.io.File
import kotlin.system.exitProcess

/**
 * W10 diff-runner entry point.
 *
 * Iterates `corpus/<case>/`, invokes the Kotlin engine in-process (no gRPC —
 * the wire shape is tested separately by the engine module's smoke tests),
 * and prints per-case `PASS | FAIL | UNIMPLEMENTED | ERROR`.
 *
 * Exit code:
 * - `0` when no case ends in `FAIL` or `ERROR`. `UNIMPLEMENTED` is acceptable
 *   because the explain path is deferred to a post-W10 wave.
 * - non-zero when any case ends in `FAIL` or `ERROR`.
 */
fun main(args: Array<String>) {
    val corpusDir = File("corpus").canonicalFile
    if (!corpusDir.isDirectory) {
        System.err.println("ERROR: corpus directory not found at $corpusDir")
        exitProcess(2)
    }

    val onlySubcommand = args.firstOrNull { it.startsWith("--subcommand=") }
        ?.removePrefix("--subcommand=")
    val onlyCaseId = args.firstOrNull { it.startsWith("--case=") }
        ?.removePrefix("--case=")

    val cases = CorpusLoader.load(corpusDir).let { all ->
        all.filter { case ->
            (onlySubcommand == null || case.subcommand == onlySubcommand) &&
                (onlyCaseId == null || case.caseId == onlyCaseId)
        }
    }
    println("Loaded ${cases.size} corpus cases from $corpusDir")

    val runner = CaseRunner()
    val results = cases.map { runner.run(it) }

    printReport(results)

    val hasFailure = results.any { it.outcome == CaseOutcome.FAIL || it.outcome == CaseOutcome.ERROR }
    exitProcess(if (hasFailure) 1 else 0)
}

private fun printReport(results: List<CaseResult>) {
    val byOutcome = results.groupingBy { it.outcome }.eachCount()
    val passes = byOutcome[CaseOutcome.PASS] ?: 0
    val fails = byOutcome[CaseOutcome.FAIL] ?: 0
    val unimpl = byOutcome[CaseOutcome.UNIMPLEMENTED] ?: 0
    val errors = byOutcome[CaseOutcome.ERROR] ?: 0

    // Per-subcommand pass rate.
    val bySubcommand = results.groupBy { it.caseId.substringBefore("__") }
    println()
    println("== Per-subcommand ==")
    for ((sub, entries) in bySubcommand.toSortedMap()) {
        val p = entries.count { it.outcome == CaseOutcome.PASS }
        val f = entries.count { it.outcome == CaseOutcome.FAIL }
        val u = entries.count { it.outcome == CaseOutcome.UNIMPLEMENTED }
        val e = entries.count { it.outcome == CaseOutcome.ERROR }
        println("  %-32s pass=%-3d fail=%-3d unimpl=%-3d error=%-3d total=%d".format(sub, p, f, u, e, entries.size))
    }

    println()
    println("== Summary ==")
    println("PASS          : $passes")
    println("FAIL          : $fails")
    println("UNIMPLEMENTED : $unimpl")
    println("ERROR         : $errors")
    println("TOTAL         : ${results.size}")

    if (fails > 0 || errors > 0) {
        println()
        println("== First ${MAX_FAILURE_SAMPLE} failures / errors ==")
        results.asSequence()
            .filter { it.outcome == CaseOutcome.FAIL || it.outcome == CaseOutcome.ERROR }
            .take(MAX_FAILURE_SAMPLE)
            .forEach { println("  [${it.outcome}] ${it.caseId}: ${it.detail}") }
    }
    // Optional diagnostic — set `DIFF_SHOW_UNIMPL=1` to list every UNIMPLEMENTED case with its
    // deferral message. Useful when triaging which wave-related stubs remain.
    if (System.getenv("DIFF_SHOW_UNIMPL") == "1" && unimpl > 0) {
        println()
        println("== UNIMPLEMENTED cases ==")
        results.asSequence()
            .filter { it.outcome == CaseOutcome.UNIMPLEMENTED }
            .forEach { println("  [UNIMPL] ${it.caseId}: ${it.detail.take(160)}") }
    }
}

private const val MAX_FAILURE_SAMPLE: Int = 15
