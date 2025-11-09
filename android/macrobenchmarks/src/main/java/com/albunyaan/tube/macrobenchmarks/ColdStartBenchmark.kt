package com.albunyaan.tube.macrobenchmarks

import android.os.Build
import android.os.Trace
import android.util.Log
import androidx.benchmark.InstrumentationResults
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.ServiceLocator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@LargeTest
@RunWith(AndroidJUnit4::class)
class ColdStartBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Before
    fun disableImages() {
        ServiceLocator.setImagesEnabledForTesting(false)
    }

    @After
    fun resetImages() {
        ServiceLocator.setImagesEnabledForTesting(null)
        ServiceLocator.setImageLoaderForTesting(null)
    }

    @Test
    @OptIn(ExperimentalMetricApi::class)
    fun coldStart() {
        val durationsMs = mutableListOf<Double>()
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(TraceSectionMetric(TRACE_SECTION_NAME)),
            iterations = 5,
            startupMode = StartupMode.COLD
        ) {
            pressHome()
            val startNs = System.nanoTime()
            Trace.beginSection(TRACE_SECTION_NAME)
            try {
                startActivityAndWait()
            } finally {
                Trace.endSection()
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            durationsMs += elapsedMs
        }
        writeResults(durationsMs)
    }

    private fun writeResults(durationsMs: List<Double>) {
        if (durationsMs.isEmpty()) return
        val sorted = durationsMs.sorted()
        val min = sorted.first()
        val max = sorted.last()
        val median = sorted[sorted.size / 2]
        val totalRunTimeNs = (durationsMs.sum() * 1_000_000).toLong()

        Log.i(TAG, "manual_startup_runs_ms=${durationsMs.joinToString(prefix = "[", postfix = "]")}")
        Log.i(TAG, "manual_startup_summary_ms min=$min median=$median max=$max")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val outputDir = File(context.filesDir, "benchmark")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, OUTPUT_FILENAME)

        val runsArray = JSONArray().apply {
            durationsMs.forEach { put(String.format("%.2f", it)) }
        }

        val metricsObject = JSONObject().apply {
            put("timeToInitialDisplayMs", JSONObject().apply {
                put("minimum", min)
                put("maximum", max)
                put("median", median)
                put("runs", runsArray)
            })
        }

        val benchmarkObject = JSONObject().apply {
            put("name", "coldStart")
            put("params", JSONObject())
            put("className", ColdStartBenchmark::class.java.name)
            put("totalRunTimeNs", totalRunTimeNs)
            put("metrics", metricsObject)
            put("sampledMetrics", JSONObject())
            put("warmupIterations", 0)
            put("repeatIterations", durationsMs.size)
            put("thermalThrottleSleepSeconds", 0)
        }

        val buildInfo = JSONObject().apply {
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("fingerprint", Build.FINGERPRINT)
            put("id", Build.ID)
            put("model", Build.MODEL)
            put("type", Build.TYPE)
            put("version", JSONObject().apply {
                put("codename", Build.VERSION.CODENAME)
                put("sdk", Build.VERSION.SDK_INT)
            })
        }

        val contextObject = JSONObject().apply {
            put("build", buildInfo)
        }

        val root = JSONObject().apply {
            put("context", contextObject)
            put("benchmarks", JSONArray().apply { put(benchmarkObject) })
        }

        outputFile.writeText(root.toString())
        InstrumentationResults.reportAdditionalFileToCopy(OUTPUT_FILENAME, outputFile.absolutePath, false)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.albunyaan.tube"
        const val OUTPUT_FILENAME = "manual-startup.json"
        const val TRACE_SECTION_NAME = "manual_startup"
        const val TAG = "ColdStartBenchmark"
    }
}

