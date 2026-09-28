package com.albunyaan.tube.data.account

import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AccountUpdateRepositoryTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private fun fakeAccountMeResponse(displayName: String? = "Test User", dateOfBirth: String? = null) =
        AccountMeResponseDto(
            uid = "uid-test",
            email = "test@example.com",
            displayName = displayName,
            dateOfBirth = dateOfBirth,
            phoneNumber = null,
            status = "active",
            role = "user",
            profileCompletedAt = null,
        )

    private fun errorBody(json: String) =
        json.toResponseBody("application/json".toMediaType())

    // ── 200 success ──────────────────────────────────────────────────────

    @Test
    fun `updateProfile 200 returns Success with response body`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto) =
                Response.success(fakeAccountMeResponse(displayName = body.displayName))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "Alice"))

        assertTrue(result is ProfileUpdateResult.Success)
        assertEquals("Alice", (result as ProfileUpdateResult.Success).response.displayName)
    }

    @Test
    fun `updateProfile 200 with null displayName preserves null in response`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto) =
                Response.success(fakeAccountMeResponse(displayName = null))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = null))

        assertTrue(result is ProfileUpdateResult.Success)
        assertEquals(null, (result as ProfileUpdateResult.Success).response.displayName)
    }

    // ── 429 rate limited ─────────────────────────────────────────────────

    @Test
    fun `updateProfile 429 body retryAfterSeconds returns RateLimited`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                Response.error(429, errorBody("""{"code":"RATE_LIMIT","retryAfterSeconds":120}"""))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "X"))

        assertTrue(result is ProfileUpdateResult.RateLimited)
        assertEquals(120L, (result as ProfileUpdateResult.RateLimited).retryAfterSec)
    }

    @Test
    fun `updateProfile 429 no body falls back to 60s default`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                Response.error(429, errorBody("{}"))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "X"))

        assertTrue(result is ProfileUpdateResult.RateLimited)
        assertEquals(60L, (result as ProfileUpdateResult.RateLimited).retryAfterSec)
    }

    // ── 422 age ineligible ───────────────────────────────────────────────

    @Test
    fun `updateProfile 422 AGE_INELIGIBLE returns AgeIneligible`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                Response.error(422, errorBody("""{"code":"AGE_INELIGIBLE","message":"under 13"}"""))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "Kid"))

        assertTrue(result is ProfileUpdateResult.AgeIneligible)
    }

    @Test
    fun `updateProfile 422 non-AGE_INELIGIBLE returns ValidationFailed`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                Response.error(422, errorBody("""{"code":"VALIDATION","message":"name too long"}"""))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "X".repeat(50)))

        assertTrue(result is ProfileUpdateResult.ValidationFailed)
        val v = result as ProfileUpdateResult.ValidationFailed
        // No "<field>: " prefix on this raw message → falls back to displayName.
        assertEquals("displayName", v.field)
        assertEquals("name too long", v.message)
    }

    // ── 400 validation ───────────────────────────────────────────────────

    @Test
    fun `updateProfile 400 returns ValidationFailed with message`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                Response.error(400, errorBody("""{"message":"displayName: exceeds max length"}"""))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "X".repeat(50)))

        assertTrue(result is ProfileUpdateResult.ValidationFailed)
        val v = result as ProfileUpdateResult.ValidationFailed
        // "<field>: <reason>" wire envelope — split into typed fields.
        assertEquals("displayName", v.field)
        assertEquals("exceeds max length", v.message)
    }

    @Test
    fun `updateProfile 400 with dateOfBirth field routes to dateOfBirth`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                Response.error(400, errorBody("""{"message":"dateOfBirth: must not be in the future"}"""))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(dateOfBirth = "2099-01-01"))

        assertTrue(result is ProfileUpdateResult.ValidationFailed)
        val v = result as ProfileUpdateResult.ValidationFailed
        assertEquals("dateOfBirth", v.field)
        assertEquals("must not be in the future", v.message)
    }

    // ── network error ────────────────────────────────────────────────────

    @Test
    fun `updateProfile IOException returns NetworkError`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                throw java.io.IOException("no connection")
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "X"))

        assertTrue(result is ProfileUpdateResult.NetworkError)
    }

    // ── unknown codes ────────────────────────────────────────────────────

    @Test
    fun `updateProfile 401 returns Unknown 401`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                Response.error(401, errorBody("{}"))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "X"))

        assertTrue(result is ProfileUpdateResult.Unknown)
        assertEquals(401, (result as ProfileUpdateResult.Unknown).code)
    }

    @Test
    fun `updateProfile 500 returns Unknown 500`() = runTest {
        val api = object : AccountUpdateApi {
            override suspend fun updateProfile(body: UpdateProfileRequestDto): Response<AccountMeResponseDto> =
                Response.error(500, errorBody("{}"))
        }
        val result = AccountUpdateRepository(api)
            .updateProfile(UpdateProfileRequestDto(displayName = "X"))

        assertTrue(result is ProfileUpdateResult.Unknown)
        assertEquals(500, (result as ProfileUpdateResult.Unknown).code)
    }
}
