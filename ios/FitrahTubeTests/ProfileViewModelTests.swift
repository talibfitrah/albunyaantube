import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Task 17. What nothing else pins: the `PUT` is provably PARTIAL (a name-only edit carries no
/// `dateOfBirth` and no `phoneNumber` key at all), the account observer reconciles only the fields
/// something else can change, `.ageIneligible` stops at a dialog-trigger state instead of signing
/// anyone out, the local under-13 gate runs before the request that would destroy the account
/// permanently, and `verifyBeforeUpdateEmail` is the ONLY email path there is.
///
/// Fakes only — `ScriptedTransport` for `/api/account/*`, `FakeAuthClient` for Firebase, an
/// injected calendar and an injected `today`. No clock, no network, no sleeps.
@Suite(.perTest)
@MainActor
struct ProfileViewModelTests {

    private static let base = URL(string: "https://api.fitrah.test/")!

    private static func meJSON(name: String = "Aisha", dob: String = "2000-01-01",
                               phone: String? = "+31612345678") -> String {
        let phoneField = phone.map { "\"phoneNumber\":\"\($0)\"," } ?? ""
        return "{\"uid\":\"fake-uid\",\"email\":\"student@fitrah.test\",\"displayName\":\"\(name)\","
            + "\"dateOfBirth\":\"\(dob)\",\(phoneField)\"status\":\"active\",\"role\":\"user\"}"
    }

    private static let passwordUser = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                               isEmailVerified: true, providerIDs: ["password"])
    private static let googleUser = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                             isEmailVerified: true, providerIDs: ["google.com"])

    /// An explicit Gregorian calendar on UTC — never `Calendar.current`, which on an Arabic/Gulf
    /// device is Islamic (Umm al-Qura) and would count thirteen LUNAR years.
    private nonisolated static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    private nonisolated static func day(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    private nonisolated static let today = day(2026, 9, 3)

    private struct Fixture {
        let model: ProfileViewModel
        let transport: ScriptedTransport
        let session: AccountSession
        let auth: FakeAuthClient
        let account: AccountClient
    }

    /// The first scripted response is the session's own `GET /me`; whatever follows belongs to the
    /// screen. `session.refresh()` is awaited so `state.me` is loaded before the model syncs.
    private func make(auth: FakeAuthClient = FakeAuthClient(state: .signedIn(passwordUser)),
                      me: String? = meJSON(), then responses: [HTTPResponse] = []) async -> Fixture {
        let transport = ScriptedTransport((me.map { [HTTPResponse.json(200, $0)] } ?? []) + responses)
        let account = AccountClient(transport: transport, baseURL: Self.base,
                                    deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in }, wipe: {})
        if me != nil { await session.refresh(maxAttempts: 1) }
        let model = ProfileViewModel(account: account, auth: auth, session: session,
                                     calendar: Self.calendar, today: { Self.today })
        return Fixture(model: model, transport: transport, session: session, auth: auth,
                       account: account)
    }

    private func loaded(_ fixture: Fixture) async -> ProfileViewModel {
        await fixture.model.sync()
        return fixture.model
    }

    private func puts(_ transport: ScriptedTransport) -> [HTTPRequest] {
        transport.sent.filter { $0.method == "PUT" }
    }

    /// The decoded JSON body, as the server would read it — the keys ABSENT from it are the point.
    private func body(_ request: HTTPRequest) -> [String: String] {
        guard let data = request.body,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return [:] }
        return json
    }

    // MARK: - Changed fields only

    @Test func aChangedDisplayNameSendsThatFieldAloneWithNoDateOfBirthAndNoPhoneNumber() async {
        let fixture = await make(then: [.json(200, Self.meJSON(name: "Aisha K"))])
        let model = await loaded(fixture)

        model.displayName = "Aisha K"
        await model.save()

        let sent = puts(fixture.transport)
        #expect(sent.count == 1)
        let json = body(sent[0])
        #expect(json == ["displayName": "Aisha K"])
        #expect(json["dateOfBirth"] == nil)
        #expect(json["phoneNumber"] == nil)
    }

    @Test func aChangedDateOfBirthSendsThatFieldAloneAsAnISOWireDate() async {
        let fixture = await make(then: [.json(200, Self.meJSON(dob: "1999-05-04"))])
        let model = await loaded(fixture)

        model.dateOfBirth = Self.day(1999, 5, 4)
        await model.save()

        let sent = puts(fixture.transport)
        #expect(sent.count == 1)
        #expect(body(sent[0]) == ["dateOfBirth": "1999-05-04"])
    }

    @Test func anUnchangedDraftMakesNoRequestAtAll() async {
        let fixture = await make()
        let model = await loaded(fixture)

        #expect(model.canSave == false)
        await model.save()

        #expect(puts(fixture.transport).isEmpty)
    }

    @Test func aSuccessfulSaveMakesTheDraftTheNewOriginalAndWritesItBackToTheSession() async {
        let fixture = await make(then: [.json(200, Self.meJSON(name: "Aisha K"))])
        let model = await loaded(fixture)

        model.displayName = "Aisha K"
        await model.save()

        #expect(model.canSave == false)                                 // clean again
        #expect(model.original?.displayName == "Aisha K")
        #expect(model.saveSucceeded)
        // Nothing else re-reads `/me` after an edit; without the write-back the next visit renders
        // the value the edit replaced.
        #expect(fixture.session.state.me?.displayName == "Aisha K")
    }

    // MARK: - The account observer

    @Test func theAccountObserverReconcilesPhoneAndEmailWithoutTouchingTheDraft() async {
        let fixture = await make()
        let model = await loaded(fixture)

        model.displayName = "Aisha typing"
        model.dateOfBirth = Self.day(1998, 3, 2)
        // The phone sheet just landed a new number, so the session's record moved under the screen.
        fixture.session.apply(AccountMe(uid: "fake-uid", email: "new@fitrah.test",
                                        displayName: "Aisha", dateOfBirth: "2000-01-01",
                                        phoneNumber: "+31699999999", status: .active, role: "user"))
        await model.sync()

        #expect(model.draft?.phoneNumber == "+31699999999")
        #expect(model.draft?.emailReadOnly == "new@fitrah.test")
        // The two fields the user is editing survive untouched — this is the whole reason the
        // reconcile is field-scoped rather than a reload.
        #expect(model.draft?.displayName == "Aisha typing")
        #expect(model.draft?.dateOfBirth == Self.day(1998, 3, 2))
    }

    @Test func aLateAccountLoadPromotesLoadingToEditing() async {
        // No `/me` yet: the screen opened before the session settled.
        let fixture = await make(me: nil, then: [.json(200, Self.meJSON())])
        await fixture.model.sync()
        #expect(fixture.model.state == .loading)

        await fixture.session.refresh(maxAttempts: 1)
        await fixture.model.sync()

        #expect(fixture.model.original?.displayName == "Aisha")
        #expect(fixture.model.original?.dateOfBirth == Self.day(2000, 1, 1))
    }

    @Test func aGoogleOnlyAccountHasNoPasswordProviderAndSoNoPasswordRow() async {
        let fixture = await make(auth: FakeAuthClient(state: .signedIn(Self.googleUser)))
        let model = await loaded(fixture)
        #expect(model.draft?.hasPasswordProvider == false)
    }

    // MARK: - Errors

    @Test func anAgeIneligibleResponseStopsAtTheDialogTriggerStateAndSignsNobodyOut() async {
        let fixture = await make(then: [.json(422, #"{"code":"AGE_INELIGIBLE"}"#)])
        let model = await loaded(fixture)

        model.displayName = "Aisha K"
        await model.save()

        #expect(model.error == .ageIneligible)
        #expect(model.state != .signedOut)
        #expect(fixture.session.state.me != nil)
    }

    @Test func confirmAgeIneligibleSignOutIsWhatDropsTheSession() async {
        let fixture = await make(then: [.json(422, #"{"code":"AGE_INELIGIBLE"}"#)])
        let model = await loaded(fixture)
        model.displayName = "Aisha K"
        await model.save()

        model.confirmAgeIneligibleSignOut()

        #expect(model.state == .signedOut)
        #expect(fixture.session.state == .signedOut)
    }

    @Test func aRateLimitedErrorRendersTheMinutesThroughFormat() async {
        let fixture = await make(then: [.json(429, #"{"retryAfterSeconds":180}"#)])
        let model = await loaded(fixture)

        model.displayName = "Aisha K"
        await model.save()

        #expect(model.error == .rateLimited(retryAfterSeconds: 180))
        let message = ProfileViewModel.bannerMessage(for: .rateLimited(retryAfterSeconds: 180),
                                                     locale: Locale(identifier: "en"))
        #expect(message == "Too many updates. Try again in 3 min.")
        // Floored at one: the string says "min", and "try again in 0 min" is not an instruction.
        #expect(ProfileViewModel.bannerMessage(for: .rateLimited(retryAfterSeconds: 30),
                                               locale: Locale(identifier: "en"))
                == "Too many updates. Try again in 1 min.")
    }

    @Test func aValidationFailureAttachesItsMessageToThatField() async {
        let fixture = await make(then: [.json(400, #"{"message":"displayName: must not be blank"}"#)])
        let model = await loaded(fixture)

        model.displayName = "  "
        await model.save()

        #expect(model.error == .validation(field: "displayName", message: "must not be blank"))
        #expect(ProfileViewModel.fieldMessage(for: model.error, field: "displayName")
                == "must not be blank")
        #expect(ProfileViewModel.fieldMessage(for: model.error, field: "dateOfBirth") == nil)
        // A field-scoped message belongs under its field, never also in the banner.
        #expect(ProfileViewModel.bannerMessage(
            for: .validation(field: "displayName", message: "must not be blank"),
            locale: Locale(identifier: "en")) == nil)
    }

    // MARK: - The local age gate

    @Test func aDateOfBirthUnderThirteenIsRefusedBeforeTheRequest() async {
        let fixture = await make()
        let model = await loaded(fixture)

        // 2026-09-03 minus twelve years: under 13 by a year.
        model.dateOfBirth = Self.day(2014, 9, 3)
        await model.save()

        // The server's rejection is PERMANENT — revoked tokens, a disabled Firebase account, a
        // tombstoned document — so a mistyped year must never reach it.
        #expect(puts(fixture.transport).isEmpty)
        #expect(ProfileViewModel.fieldMessage(for: model.error, field: "dateOfBirth") != nil)
        // And it stays a correctable form error: nobody is signed out for a typo.
        #expect(model.state != .signedOut)
        #expect(model.error != .ageIneligible)
    }

    // MARK: - Strings

    /// The twelve `profile_*` keys that existed ONLY in Android's `values/strings.xml` shipped the
    /// English sentence as their Arabic and Dutch value. This screen is what first renders them.
    @Test func everyProfileStringIsReallyTranslatedInArabicAndDutch() throws {
        let reAuthored = ["profile_title", "profile_personal_info", "profile_display_name",
                          "profile_date_of_birth", "profile_dob_pick", "profile_save",
                          "profile_save_success", "profile_email_locked", "profile_error_network",
                          "profile_error_rate_limited", "profile_error_age_dialog_title",
                          "profile_error_age_dialog_message"]
        let ported = ["profile_email_label", "profile_phone", "profile_phone_unset", "profile_add",
                      "profile_edit", "profile_password", "profile_error_rate_limited_short",
                      "edit_email_title", "edit_email_new_email", "edit_email_current_password",
                      "edit_email_send", "edit_email_sent", "edit_email_invalid", "edit_email_in_use",
                      "edit_email_wrong_password", "edit_password_title", "edit_password_current",
                      "edit_password_new", "edit_password_confirm", "edit_password_update",
                      "edit_password_updated", "edit_password_weak", "edit_password_mismatch",
                      "edit_password_wrong_current", "edit_phone_title", "edit_phone_number",
                      "edit_phone_save", "edit_phone_updated"]
        let bundles = try ["en", "ar", "nl"].map { locale in
            (locale, try #require(Bundle.main.path(forResource: locale, ofType: "lproj")
                .flatMap(Bundle.init(path:))))
        }
        for (locale, bundle) in bundles {
            for key in reAuthored + ported {
                let value = bundle.localizedString(forKey: key, value: nil, table: nil)
                #expect(value != key, "\(locale)/\(key) renders the raw key")
            }
        }
        let english = bundles[0].1
        for key in reAuthored {
            let arabic = bundles[1].1.localizedString(forKey: key, value: nil, table: nil)
            #expect(arabic != english.localizedString(forKey: key, value: nil, table: nil),
                    "\(key) is still the English sentence in Arabic")
        }
    }

    // MARK: - Edit email sheet

    /// `verifyBeforeUpdateEmail` is the ONLY email path: the address does not move until the user
    /// opens the link mailed to the NEW one, so the current address keeps signing them in until
    /// then. An `updateEmail`-shaped call would flip it on an unverified mailbox.
    @Test func changingTheEmailReauthenticatesThenVerifiesAndNeverCallsUpdateEmail() async {
        let auth = FakeAuthClient(state: .signedIn(Self.passwordUser))
        let model = EditEmailViewModel(auth: auth)
        model.newEmail = "new@fitrah.test"
        model.currentPassword = "hunter2000"

        await model.submit()

        #expect(auth.operations == [.reauthenticate, .verifyBeforeUpdateEmail])
        #expect(model.sentTo == "new@fitrah.test")
        #expect(model.state.error == nil)
    }

    @Test func aMalformedAddressNeverReachesFirebase() async {
        let auth = FakeAuthClient(state: .signedIn(Self.passwordUser))
        let model = EditEmailViewModel(auth: auth)
        model.newEmail = "not-an-address"
        model.currentPassword = "hunter2000"

        await model.submit()

        #expect(auth.operations.isEmpty)
        #expect(model.state.error == .invalidEmail)
    }

    @Test func aRejectedReauthenticationNamesTheWrongPasswordRatherThanFailingGenerically() async {
        let auth = FakeAuthClient(state: .signedIn(Self.passwordUser))
        auth.nextError = .invalidCredential
        let model = EditEmailViewModel(auth: auth)
        model.newEmail = "new@fitrah.test"
        model.currentPassword = "wrong"

        await model.submit()

        #expect(model.state.error == .wrongPassword)
        #expect(auth.operations == [.reauthenticate])   // never got as far as the verify
        #expect(model.sentTo == nil)
    }

    // MARK: - Edit password sheet

    @Test func theLocalPasswordChecksRunBeforeAnyNetworkCall() async {
        let auth = FakeAuthClient(state: .signedIn(Self.passwordUser))
        let model = EditPasswordViewModel(auth: auth)
        model.current = "hunter2000"
        model.newPassword = "short"
        model.confirm = "short"
        await model.submit()
        #expect(model.state.error == .weakPassword)

        model.newPassword = "longenough1"
        model.confirm = "longenough2"
        await model.submit()
        #expect(model.state.error == .passwordMismatch)

        // Length first, then equality — a too-short pair reports the length, not the mismatch.
        #expect(EditPasswordViewModel.localError(newPassword: "abc", confirm: "xyz") == .weakPassword)
        #expect(auth.operations.isEmpty)
    }

    @Test func anInvalidCredentialFromTheReauthMapsToWrongCurrentPasswordNotUnknown() async {
        let auth = FakeAuthClient(state: .signedIn(Self.passwordUser))
        auth.nextError = .invalidCredential
        let model = EditPasswordViewModel(auth: auth)
        model.current = "wrong"
        model.newPassword = "longenough1"
        model.confirm = "longenough1"

        await model.submit()

        #expect(model.state.error == .wrongCurrentPassword)
        #expect(auth.operations == [.reauthenticate])
        #expect(model.didUpdate == false)
    }

    @Test func aValidChangeReauthenticatesThenUpdatesThePassword() async {
        let auth = FakeAuthClient(state: .signedIn(Self.passwordUser))
        let model = EditPasswordViewModel(auth: auth)
        model.current = "hunter2000"
        model.newPassword = "longenough1"
        model.confirm = "longenough1"

        await model.submit()

        #expect(auth.operations == [.reauthenticate, .updatePassword])
        #expect(model.didUpdate)
    }

    // MARK: - Edit phone sheet

    @Test func aValidPhoneSendsPhoneNumberAloneOnThePutRequest() async {
        let fixture = await make(then: [.json(200, Self.meJSON(phone: "+31699999999"))])
        let model = EditPhoneViewModel(account: fixture.account, session: fixture.session)
        // Seeded from the stored E.164; the "+" is chrome, so the setter drops it.
        model.seed("+31612345678")
        #expect(model.number == "31612345678")

        model.number = "31699999999"
        await model.submit()

        let sent = puts(fixture.transport)
        #expect(sent.count == 1)
        #expect(body(sent[0]) == ["phoneNumber": "+31699999999"])
        #expect(model.didUpdate)
        #expect(fixture.session.state.me?.phoneNumber == "+31699999999")
    }

    /// `nil` on the wire means "no change", never "clear this", so there is no delete affordance —
    /// and this pins the ABSENCE: a cleared field sends nothing at all.
    @Test func aClearedPhoneFieldSendsNothingAtAll() async {
        let fixture = await make()
        let model = EditPhoneViewModel(account: fixture.account, session: fixture.session)
        model.seed("+31612345678")

        model.number = ""
        #expect(model.isValid == false)
        await model.submit()

        #expect(puts(fixture.transport).isEmpty)
        #expect(model.didUpdate == false)
    }

    @Test func aMalformedPhoneIsRefusedByTheSharedPatternBeforeTheRequest() async {
        let fixture = await make()
        let model = EditPhoneViewModel(account: fixture.account, session: fixture.session)

        // Six digits — under the server's own `\+[1-9]\d{7,14}` minimum.
        model.number = "316123"
        await model.submit()

        #expect(model.state.error == .invalidPhone)
        #expect(puts(fixture.transport).isEmpty)
        // Arabic-Indic digits are normalised at the field, exactly as the bootstrap form does it,
        // so the server's ASCII-only `@Pattern` never sees them.
        model.number = "٣١٦١٢٣٤٥٦٧٨"
        #expect(model.e164 == "+31612345678")
        #expect(model.isValid)
    }
}
