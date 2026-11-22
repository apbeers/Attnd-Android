package com.arcu.attnd.eval

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arcu.attnd.data.AssistantAvailability
import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.domain.AssistantTools
import com.arcu.attnd.unit.AssistantEvalGrader
import com.arcu.attnd.unit.AssistantEvalReport
import com.arcu.attnd.unit.AssistantEvalRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The assistant eval in model mode (attnd-web docs/assistant_eval/README.md; iOS `AssistantEvalModelTests`): every
 * question through Gemini Nano's JSON tool loop ([AssistantTools.run] on [OnDeviceAssistant.toolRound]), the router
 * on any failure, exactly as `AssistantController.ask`. Opt-in, on a phone with Gemini Nano AVAILABLE:
 *
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.attndAssistantEval=model
 *
 * (`…attndAssistantEvalOnly=<ids or categories, comma-separated>` narrows it). Skipped without the argument or the
 * model. Never fails on a wrong answer: the report (`assistant-eval-android-model-<time>.json`, pulled by AGP into
 * app/build/outputs/connected_android_test_additional_output/) and the summary in logcat (tag AttndEval) are the result.
 */
@RunWith(AndroidJUnit4::class)
class AssistantEvalModelTest {

    @Test
    fun modelEval() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass attndAssistantEval=model for the model eval.", args.getString("attndAssistantEval") == "model")
        val assistant = OnDeviceAssistant()
        assumeTrue("Gemini Nano isn't AVAILABLE on this device.", runBlocking { assistant.availability() } == AssistantAvailability.AVAILABLE)
        val only = args.getString("attndAssistantEvalOnly").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val runner = AssistantEvalRunner { input, ctx, conversation -> AssistantTools.run(input, ctx, conversation, assistant::toolRound) }
        val grader = AssistantEvalGrader(runner.bundle, runner.now, runner.bundleJson)
        val report = AssistantEvalReport(runner.mode)
        for (q in runner.catalogue.questions) {
            if (only.isNotEmpty() && q.id !in only && q.category !in only) continue
            val outcome = runner.run(q)
            report.add(q, outcome, grader.grade(q, outcome), grader)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = args.getString("additionalTestOutputDir")?.let(::File) ?: File(context.getExternalFilesDir(null), "assistant-eval")
        val file = report.write(runner.catalogue.fixture.now, dir)
        (report.summary + "\nReport: ${file.absolutePath}").lines().forEach { Log.i(TAG, it) }
    }

    private companion object {
        const val TAG = "AttndEval"
    }
}
