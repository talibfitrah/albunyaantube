import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Task 12. The three things nothing else pins: the password step is asked for exactly when the
/// account has no password provider, the `profileSaved` latch makes the commit two-phase (a retry
/// after a failed `updatePassword` re-runs ONLY the password step), and the local age gate refuses
/// under-13 without ever reaching the endpoint that would destroy the account permanently.
///
/// Fakes only — `ScriptedTransport` for `/api/account/*`, `FakeAuthClient` for Firebase, an
/// injected calendar and an injected `today`. No clock, no network, no sleeps.
@Suite(.perTest)
@MainActor
struct ProfileBootstrapViewModelTests {

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let meJSON = #"{"uid":"fake-uid","email":"student@fitrah.test","status":"active","role":"user"}"#
    private static let pendingJSON =
        #"{"uid":"fake-uid","email":"student@fitrah.test","status":"pending_profile","role":"user"}"#

    private static let passwordUser = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                               isEmailVerified: true, providerIDs: ["password"])
    private static let googleUser = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                             isEmailVerified: true, providerIDs: ["google.com"])

    private nonisolated static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    private nonisolated static func day(_ year: Int, _ month: Int, _ day: Int, in calendar: Calendar = calendar) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    private nonisolated static let today = day(2026, 9, 3)
    private nonisolated static let dob = day(2000, 1, 1)

    private struct Fixture {
        let model: ProfileBootstrapViewModel
        let transport: ScriptedTransport
        let auth: FakeAuthClient
        let session: AccountSession
    }

    private func make(auth: FakeAuthClient, responses: [HTTPResponse] = [],
                      calendar: Calendar = ProfileBootstrapViewModelTests.calendar) -> Fixture {
        let transport = ScriptedTransport(responses)
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in }, wipe: {})
        let model = ProfileBootstrapViewModel(account: account, auth: auth, session: session,
                                              calendar: calendar, today: { Self.today })
        return Fixture(model: model, transport: transport, auth: auth, session: session)
    }

    /// Fills the form with a valid submission. The phone is the NATIONAL portion — the screen owns
    /// the fixed leading "+", so the model is what re-assembles E.164.
    private func fill(_ model: ProfileBootstrapViewModel, name: String = "Aisha",
                      phone: String = "31612345678", password: String = "") {
        model.displayName = name
        model.dateOfBirth = Self.dob
        model.phoneNumber = phone
        model.password = password
        model.passwordConfirm = password
    }

    private func body(_ request: HTTPRequest) -> [String: String] {
        guard let data = request.body,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return [:] }
        return json
    }

    private func profilePosts(_ transport: ScriptedTransport) -> [HTTPRequest] {
        transport.sent.filter { $0.method == "POST" && $0.url.absoluteString.hasSuffix("/api/account/profile") }
    }

    // MARK: - passwordRequired

    @Test func aGoogleOnlyAccountIsAskedToAttachAPassword() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.googleUser)))
        await fixture.model.load()
        #expect(fixture.model.state.passwordRequired)
    }

    @Test func aPasswordAccountIsNotAskedForAPasswordAgain() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)))
        await fixture.model.load()
        #expect(fixture.model.state.passwordRequired == false)
    }

    // MARK: - One validator, two consumers

    @Test func theSubmitGateAndTheErrorDispatchShareOneValidator() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)))
        fill(fixture.model, phone: "0031612345678")   // the "+" is fixed, so a 00 prefix cannot match
        #expect(fixture.model.isFormValid == false)

        await fixture.model.submit()
        #expect(fixture.model.state.error == .invalidPhone)
        #expect(fixture.transport.sent.isEmpty, "an invalid form never reaches the network")
        #expect(fixture.model.nav == .idle)
    }

    /// The whole reason the age gate is duplicated on the client: the server's rejection revokes
    /// refresh tokens, disables the Firebase account and tombstones the Firestore doc, so a mistyped
    /// year would destroy the account with no recovery. It must fail as a correctable form error.
    @Test func anUnderAgeDateOfBirthNeverReachesTheServer() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)))
        fill(fixture.model)
        fixture.model.dateOfBirth = Self.day(2020, 1, 1)

        #expect(fixture.model.isFormValid == false)
        await fixture.model.submit()
        #expect(fixture.model.state.error == .underAge)
        #expect(fixture.transport.sent.isEmpty)
        #expect(fixture.model.nav == .idle, "the local gate is a form error, NOT the terminal screen")
    }

    @Test func editingAFieldClearsTheStandingError() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)))
        fill(fixture.model, phone: "1")
        await fixture.model.submit()
        #expect(fixture.model.state.error == .invalidPhone)

        fixture.model.phoneNumber = "31612345678"
        #expect(fixture.model.state.error == nil)
    }

    // MARK: - The happy path

    @Test func aSuccessfulSubmitPostsTheProfileAndRoutesToMain() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)),
                           responses: [.json(200, Self.meJSON), .json(200, Self.meJSON)])
        fill(fixture.model, name: "  Aisha  ")
        await fixture.model.load()
        await fixture.model.submit()

        let posts = profilePosts(fixture.transport)
        #expect(posts.count == 1)
        // Trimmed before it is sent: the validator measures the trimmed value, so sending the raw one
        // could ship a 42-character name the server's @Size(max = 40) then rejects.
        #expect(body(posts[0])["displayName"] == "Aisha")
        #expect(fixture.model.state.error == nil)
        #expect(fixture.model.nav == .main)
    }

    @Test func thePhoneIsSentInE164WithTheFixedPlus() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)),
                           responses: [.json(200, Self.meJSON), .json(200, Self.meJSON)])
        fill(fixture.model, phone: "31612345678")
        await fixture.model.submit()
        #expect(body(profilePosts(fixture.transport)[0])["phoneNumber"] == "+31612345678")
    }

    /// The wire form is ISO `yyyy-MM-dd` in ASCII digits. A `DateFormatter` on `Locale.current` would
    /// emit Arabic-Indic digits for an `ar` user and the backend would 400 on every submit.
    @Test func theDateOfBirthIsSentAsAsciiIso() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)),
                           responses: [.json(200, Self.meJSON), .json(200, Self.meJSON)])
        fill(fixture.model)
        await fixture.model.submit()

        let sent = body(profilePosts(fixture.transport)[0])["dateOfBirth"]
        #expect(sent == "2000-01-01")
        #expect(sent?.allSatisfy(\.isASCII) == true)
    }

    /// The DOB is the day the picker SHOWED, resolved in the calendar the picker used — not the UTC
    /// instant behind it. A user at UTC+13 picking 2000-01-01 holds an instant that is still
    /// 1999-12-31 in UTC, and shipping that would put every such account one day younger than it is.
    @Test func theWireDateIsTheDayThePickerShowedNotTheUtcInstant() async {
        var auckland = Calendar(identifier: .gregorian)
        auckland.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)),
                           responses: [.json(200, Self.meJSON), .json(200, Self.meJSON)],
                           calendar: auckland)
        fill(fixture.model)
        fixture.model.dateOfBirth = Self.day(2000, 1, 1, in: auckland)
        await fixture.model.submit()
        #expect(body(profilePosts(fixture.transport)[0])["dateOfBirth"] == "2000-01-01")
    }

    /// The wire date is GREGORIAN whatever the device's region calendar is. An Umm al-Qura device —
    /// the audience this app is built for — used to ship "1420-09-24" for a 2000-01-01 birthday, a
    /// permanently wrong DOB the backend parses as the year 1420.
    @Test func theWireDateIsGregorianOnAHijriDeviceCalendar() async {
        var hijri = Calendar(identifier: .islamicUmmAlQura)
        hijri.timeZone = TimeZone(identifier: "UTC")!
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)),
                           responses: [.json(200, Self.meJSON), .json(200, Self.meJSON)],
                           calendar: hijri)
        fill(fixture.model)
        await fixture.model.submit()
        #expect(body(profilePosts(fixture.transport)[0])["dateOfBirth"] == "2000-01-01")
    }

    /// ONE rule for the phone field: every character that IS a digit becomes its ASCII digit and
    /// everything else is dropped. `.telephoneNumber` autofill hands over spaces and dashes, and an
    /// Arabic keypad hands over Arabic-Indic digits — both used to dead-end on `.invalidPhone` with
    /// no way for the user to see what was wrong.
    @Test func thePhoneSetterNormalisesToAsciiDigits() {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)))
        fill(fixture.model)

        fixture.model.phoneNumber = "+31 6 1234-5678"
        #expect(fixture.model.state.phoneNumber == "31612345678")
        #expect(fixture.model.isFormValid)

        // ٣١٦١٢٣٤٥٦٧٨ — escaped so the source stays readable left to right.
        fixture.model.phoneNumber =
            "\u{0663}\u{0661}\u{0666}\u{0661}\u{0662}\u{0663}\u{0664}\u{0665}\u{0666}\u{0667}\u{0668}"
        #expect(fixture.model.state.phoneNumber == "31612345678")
        #expect(fixture.model.e164 == "+31612345678")
        #expect(fixture.model.isFormValid)
    }

    /// `RootView` routes off `AccountSession.state.me?.status`, and nothing else re-reads `/me` — so
    /// without this refresh a completed profile stays `pending_profile` and the screen never exits.
    @Test func theSessionIsRefreshedSoTheRouterCanLeaveTheScreen() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)),
                           responses: [.json(200, Self.pendingJSON),   // the session's first /me
                                       .json(200, Self.meJSON),       // POST /profile
                                       .json(200, Self.meJSON)])      // the re-read submit() forces
        await fixture.session.refresh(maxAttempts: 1)
        #expect(fixture.session.state.me?.status == .pendingProfile)

        fill(fixture.model)
        await fixture.model.submit()
        #expect(fixture.session.state.me?.status == .active)
    }

    // MARK: - The two-phase commit

    /// `profileSaved` is the latch. The backend 409s a second `POST /profile`, so a retry that
    /// re-sent it would turn a recoverable password failure into a dead form.
    @Test func theProfileIsPostedExactlyOnceAcrossTwoSubmits() async {
        let auth = FakeAuthClient(state: .signedIn(Self.googleUser))
        let fixture = make(auth: auth, responses: [.json(200, Self.meJSON), .json(200, Self.meJSON)])
        await fixture.model.load()
        fill(fixture.model, password: "hunter2hunter2")
        #expect(fixture.model.state.passwordRequired)

        auth.nextError = .weakPassword
        await fixture.model.submit()
        #expect(fixture.model.state.error == .passwordSetFailed)
        #expect(fixture.model.state.profileSaved, "the profile committed; only the password step failed")
        #expect(fixture.model.nav == .idle, "the user stays on the screen to retry the password")

        await fixture.model.submit()
        #expect(fixture.model.nav == .main)
        #expect(profilePosts(fixture.transport).count == 1, "the retry re-ran ONLY the password step")
    }

    @Test func aMissingCurrentUserAtThePasswordStepIsPasswordSetFailed() async {
        let auth = FakeAuthClient(state: .signedIn(Self.googleUser))
        let fixture = make(auth: auth, responses: [.json(200, Self.meJSON)])
        await fixture.model.load()
        fill(fixture.model, password: "hunter2hunter2")

        // The session expired between the profile save and the password attach.
        auth.signOut()
        await fixture.model.submit()
        #expect(fixture.model.state.error == .passwordSetFailed)
        #expect(fixture.model.state.profileSaved)
        #expect(fixture.model.nav == .idle)
    }

    // MARK: - Failure routing

    @Test func anAgeIneligibleResponseRoutesToTheTerminalScreen() async {
        let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)),
                           responses: [.json(422, #"{"code":"AGE_INELIGIBLE"}"#)])
        fill(fixture.model)
        await fixture.model.submit()

        #expect(fixture.model.nav == .ageIneligible)
        #expect(fixture.model.state.profileSaved == false)
        #expect(fixture.model.state.error == nil, "the terminal screen is the message; no inline error")
    }

    @Test func everyOtherProfileFailureIsSaveFailed() async {
        for response in [HTTPResponse.json(500, "{}"),
                         .json(409, "{}"),
                         .json(403, #"{"code":"EMAIL_NOT_VERIFIED"}"#),
                         .failing(URLError(.notConnectedToInternet))] {
            let fixture = make(auth: FakeAuthClient(state: .signedIn(Self.passwordUser)),
                               responses: [response])
            fill(fixture.model)
            await fixture.model.submit()
            #expect(fixture.model.state.error == .saveFailed)
            #expect(fixture.model.nav == .idle)
            #expect(fixture.model.state.profileSaved == false)
        }
    }
}
