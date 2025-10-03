plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.albunyaan.tube.macrobenchmarks"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"

        val enabledRulesPropertyKey = "android.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules"
        if (!project.hasProperty(enabledRulesPropertyKey)) {
            testInstrumentationRunnerArguments["androidx.benchmark.enabledRules"] = "Macrobenchmark"
        }
        val dropShadersKey = "android.testInstrumentationRunnerArguments.androidx.benchmark.dropShaders.enable"
        if (!project.hasProperty(dropShadersKey)) {
            testInstrumentationRunnerArguments["androidx.benchmark.dropShaders.enable"] = "false"
        }
        val dropShadersOnFailureKey = "android.testInstrumentationRunnerArguments.androidx.benchmark.dropShaders.throwOnFailure"
        if (!project.hasProperty(dropShadersOnFailureKey)) {
            testInstrumentationRunnerArguments["androidx.benchmark.dropShaders.throwOnFailure"] = "false"
        }
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true                       // keep the run-as support
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isDebuggable = true
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.0")
    implementation("androidx.benchmark:benchmark-junit4:1.3.0")
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
