# Android Instrumentation Tests

Infrastructure for running Android instrumentation tests with Espresso.

## Test Utilities

### `util/MockWebServerRule.kt`
JUnit rule for managing MockWebServer lifecycle in tests.

**Usage**:
```kotlin
class MyApiTest {
    @get:Rule
    val mockWebServerRule = MockWebServerRule()

    @Test
    fun testApiCall() {
        // Enqueue mock response
        mockWebServerRule.enqueueJson("""{"id": "1", "title": "Test"}""")

        // Your test code that makes API call to mockWebServerRule.baseUrl
        // ...

        // Verify request was made
        val request = mockWebServerRule.takeRequest()
        assertEquals("/api/videos", request.path)
    }
}
```

### `util/TestDataBuilder.kt`
Builders for creating test data objects.

**Usage**:
```kotlin
@Test
fun testVideoDisplay() {
    val video = TestDataBuilder.video(
        id = "video123",
        title = "Test Video",
        viewCount = 5000
    )

    // Use in your test
    viewModel.loadVideo(video)
}
```

### `util/BaseInstrumentationTest.kt`
Base class for instrumentation tests with common setup.

**Usage**:
```kotlin
class MyTest : BaseInstrumentationTest() {
    @Test
    fun testWithContext() {
        // appContext is available
        val packageName = appContext.packageName
    }
}
```

## Running Tests

### From Command Line
```bash
# Run all instrumentation tests
./gradlew connectedAndroidTest

# Run specific test class
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.albunyaan.tube.ui.HomeScreenTest

# Run tests on specific device
adb devices  # List devices
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.deviceSerial=<device-id>
```

### From Android Studio
1. Right-click on test file or package
2. Select "Run '...'"
3. Tests will run on connected device/emulator

## Writing Tests

### Example: UI Test with MockWebServer

```kotlin
class HomeScreenTest : BaseInstrumentationTest() {
    @get:Rule
    val mockWebServerRule = MockWebServerRule()

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun homeScreen_displaysVideos() {
        // Arrange: Mock API response
        mockWebServerRule.enqueueJson(TestDataBuilder.homeResponseJson())

        // Act: Wait for content to load
        onView(withId(R.id.recyclerViewHome))
            .check(matches(isDisplayed()))

        // Assert: Verify first video is displayed
        onView(withText("Test Video 1"))
            .check(matches(isDisplayed()))
    }
}
```

### Example: ViewModel Test with Test Data

```kotlin
class PlayerViewModelTest : BaseInstrumentationTest() {
    private lateinit var viewModel: PlayerViewModel

    @Before
    override fun setUp() {
        super.setUp()
        viewModel = PlayerViewModel(/* dependencies */)
    }

    @Test
    fun loadVideo_updatesState() = runTest {
        // Arrange
        val video = TestDataBuilder.video(id = "123")

        // Act
        viewModel.loadVideo(video.id)

        // Assert
        val state = viewModel.playerState.value
        assertEquals("123", state.videoId)
    }
}
```

## Best Practices

1. **Use MockWebServer for API tests**: Don't hit real backend in tests
2. **Use TestDataBuilder for test data**: Consistent, realistic test objects
3. **Clean up after tests**: Use `@After` to clean up resources
4. **Test user flows, not implementation**: Focus on what users see/do
5. **Keep tests fast**: Mock expensive operations
6. **Use descriptive test names**: `featureName_scenario_expectedBehavior()`

## Dependencies

All test dependencies are configured in `app/build.gradle.kts`:
- Espresso: UI testing framework
- JUnit: Test runner
- MockWebServer: Mock HTTP server for API tests
- AndroidX Test: Core testing utilities

## CI Integration

Tests run automatically in CI via `.github/workflows/android-ci.yml`:
- On every push to main
- On every pull request
- Test results uploaded as artifacts

## Troubleshooting

### "Unable to find instrumentation info"
- Ensure `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` is in `build.gradle.kts`

### "Device offline" errors
- Check device is connected: `adb devices`
- Restart ADB: `adb kill-server && adb start-server`

### Slow test execution
- Use emulator with hardware acceleration (HAXM/KVM)
- Disable animations on test device: Developer Options → Animation scales → Off

### "Class not found" errors
- Clean and rebuild: `./gradlew clean assembleDebug assembleDebugAndroidTest`
- Invalidate Android Studio caches: File → Invalidate Caches / Restart

## Resources

- [Espresso Documentation](https://developer.android.com/training/testing/espresso)
- [AndroidX Test](https://developer.android.com/training/testing/instrumented-tests)
- [MockWebServer](https://github.com/square/okhttp/tree/master/mockwebserver)

