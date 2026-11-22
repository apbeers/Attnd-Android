package com.arcu.attnd.unit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The assistant eval in router mode (attnd-web docs/assistant_eval/README.md; iOS `AssistantEvalTests`); the runner is in
 * src/evalShared, model mode is androidTest's `AssistantEvalModelTest` (a phone with Gemini Nano).
 * - [catalogueMatchesTheFixture] (always): the catalogue is well formed and every entity it names is in the DEMO bundle.
 * - [criticalQuestionsRouter] (always, fast): the `critical` questions (safety, injection, invented facts, core finding)
 *   pass on the rule-based router.
 * - [routerEval] (opt-in: `./gradlew testDebugUnitTest -PassistantEval --tests '*AssistantEvalTest*'`, or the
 *   environment variable ATTND_ASSISTANT_EVAL=router): every question, a JSON report (ATTND_ASSISTANT_EVAL_OUT, else
 *   app/build/assistant-eval/), the summary on stdout; never fails on a wrong answer (the report is the result).
 *   ATTND_ASSISTANT_EVAL_ONLY=<ids or categories> narrows it.
 */
class AssistantEvalTest {

    private val runner = AssistantEvalRunner()
    private val grader = AssistantEvalGrader(runner.bundle, runner.now, runner.bundleJson)

    @Test
    fun catalogueMatchesTheFixture() {
        val catalogue = runner.catalogue
        assertEquals(1, catalogue.version)
        assertTrue(catalogue.questions.size >= 150)
        val known = runner.bundle.locations.map { it.name }.toSet() + runner.bundle.schedule.map { it.name } +
            runner.bundle.notifications.map { it.title }
        val ids = mutableSetOf<String>()
        for (q in catalogue.questions) {
            assertTrue("duplicate id ${q.id}", ids.add(q.id))
            q.follows.forEach { assertTrue("${q.id} follows unknown $it", catalogue.question(it) != null) }
            for (e in listOfNotNull(q.expect, q.expectModel, q.expectRouter)) {
                e.names.forEach { assertTrue("${q.id}: $it is not in the DEMO bundle", it in known) }
                e.rules.forEach { assertTrue("${q.id}: unknown rule $it", it in AssistantEvalGrader.RULES) }
            }
        }
        runner.baseContext() // the fixture's My schedule entries exist
    }

    @Test
    fun criticalQuestionsRouter() {
        val failures = runner.catalogue.questions.filter { it.critical }.mapNotNull { q ->
            val outcome = runner.run(q)
            val grade = grader.grade(q, outcome)
            if (grade.passed) null
            else "${q.id} \"${q.text}\": ${grade.failures.joinToString("; ")} — answer: ${grader.answerText(outcome)}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun routerEval() {
        val mode = (System.getProperty("attnd.assistantEval") ?: System.getenv("ATTND_ASSISTANT_EVAL")).orEmpty().lowercase()
        assumeTrue("Run with -PassistantEval (or ATTND_ASSISTANT_EVAL=router) for the full router eval.", mode in setOf("router", "all", "1"))
        val only = System.getenv("ATTND_ASSISTANT_EVAL_ONLY").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val report = AssistantEvalReport()
        for (q in runner.catalogue.questions) {
            if (only.isNotEmpty() && q.id !in only && q.category !in only) continue
            val outcome = runner.run(q)
            report.add(q, outcome, grader.grade(q, outcome), grader)
        }
        val dir = (System.getenv("ATTND_ASSISTANT_EVAL_OUT") ?: System.getProperty("attnd.assistantEvalOut"))
            ?.takeIf { it.isNotEmpty() }?.let(::File) ?: File("build/assistant-eval")
        val file = report.write(runner.catalogue.fixture.now, dir)
        println(report.summary + "\nReport: ${file.absolutePath}")
    }
}
