import Foundation
import GoogleCast
import SwiftUI

/// The app's ONE Google Cast seam (spec §10 Chromecast). Every `GoogleCast` reference lives here
/// and in `CastMedia`'s one mapping function -- spec §10's "Cast SDK is not loaded at all when
/// `GCKCastContext` cannot be created" is enforced by construction: a failed `setUp()` leaves
/// `castAvailable == false`, and every consumer (the toolbar's cast slot, the shell's mini
/// controller, `PlayerScreen`'s session reaction) is gated on published state, never on the SDK.
///
/// It PUBLISHES; it never drives. The controller holds no `PlayerViewModel` and knows nothing
/// about the local player: the mounted `PlayerScreen` reacts to `isSessionActive` /
/// `lastStreamPosition` / `lastLoadFailureDevice` and does the resolving, pausing and seeking
/// through the seams it already owns. A session that ends with no player mounted (the mini
/// controller outlives the player route) is therefore a natural no-op -- there is no local player
/// to hand the position back to, and resurrecting the popped route to seek it would be worse than
/// doing nothing. Deliberate.
///
/// The session is app-wide but the player it drives is not (fix round 1, review Important 1):
/// `MainShellView` keeps every visited tab's stack mounted, so several `PlayerScreen`s can read
/// one `isSessionActive`. `castingVideoId` is the stamp that names the ONE screen this session
/// belongs to; every reaction is gated on it.
///
/// `NSObject` subclass because `GCKSessionManagerListener`, `GCKRequestDelegate` and
/// `GCKUIMiniMediaControlsViewControllerDelegate` all refine `NSObjectProtocol`. Every one of
/// those three delivers on the main thread empirically (and `PlayerHostView.Coordinator` already
/// makes the same bet for AVFoundation's) -- but the 4.8.6 headers do NOT document it for any of
/// them, so the `assumeIsolated` hops below are a bet, not a quoted guarantee. They trap rather
/// than race if it is ever wrong.
@MainActor @Observable final class CastController: NSObject {
    /// False until `setUp()` has actually created (or found) a shared `GCKCastContext`. Per
    /// instance, written only by `setUp()`: the composition root calls it once from the launch
    /// hook, and a container that never did (every fixture container) reports false forever.
    private(set) var castAvailable = false

    /// A Cast session is connected. The `PlayerScreen` that owns `castingVideoId` reacts to the
    /// transitions: true -> fresh resolve + load + pause local; false -> seek local to
    /// `lastStreamPosition` and resume.
    private(set) var isSessionActive = false

    /// The videoId of the screen this session belongs to. Claimed by the first `PlayerScreen` to
    /// react to a session start (`claimCastSource`), cleared by that screen's hand-back
    /// (`finishCasting`) or by the next session beginning. Survives `sessionDidEnd` on purpose:
    /// SwiftUI runs `.onChange` after the callback returns, so the end reaction still has to be
    /// able to identify its owner.
    ///
    /// ponytail: first-writer-wins. With two players mounted (CF-D-12's stacked case) the screen
    /// whose `.onChange` SwiftUI runs first claims the session, and that order is not defined.
    /// Both candidates are the user's own player screens and only one resolves/loads either way;
    /// naming the visible one needs a visibility signal SwiftUI does not reliably give a pushed
    /// destination on an unselected tab.
    private(set) var castingVideoId: String?

    /// The receiver's `approximateStreamPosition`, sampled in `willEndSession` while the remote
    /// media client is still connected (by `didEndSession` it may already be gone), and published
    /// when the session actually ends. Cleared when a new session begins so a hand-back can never
    /// seek to a previous session's position (review Important 2).
    private(set) var lastStreamPosition: TimeInterval?

    /// The receiver currently connected, for the cast slot's accessibility value. `nil` when no
    /// session is up.
    private(set) var connectedDeviceName: String?

    /// `GCKUIMiniMediaControlsViewController.active` ("When NO, the control bar should be
    /// hidden", `:43-48`) -- true only once there is media on the receiver to control, so a
    /// connected session with nothing loaded parks no empty strip above the tab bar.
    ///
    /// Drives the strip's HEIGHT, never whether `MainShellView` mounts it (re-review Important 2):
    /// the SDK's own container embeds the mini controller permanently and uses the delegate only
    /// to show/hide it (`GCKUICastContainerViewController.h:27-39`), and nothing in the headers
    /// promises `active` updates for a view controller whose view was never loaded. Mounting on
    /// this flag would have made it its own precondition.
    private(set) var miniControlsActive = false

    /// The device a load failed on, for the "Couldn't play on %@" banner (`cast_error_format`).
    /// Android swallows this failure; spec §10 says to surface it. Consumed and cleared by the
    /// screen that shows the banner.
    var lastLoadFailureDevice: String?

    /// Held only until its delegate callback lands -- `GCKRequest.delegate` is weak and the
    /// request is the only thing carrying the load's outcome.
    private var loadRequest: GCKRequest?

    /// Creates the shared `GCKCastContext`, once per process. Idempotent and non-throwing: the
    /// `setSharedInstanceWithOptions:error:` overload (Bool + NSError, `GCKCastContext.h:78`) is
    /// what makes "cannot be created" a survivable state rather than the exception the
    /// single-argument overload raises.
    ///
    /// Discovery is NOT started here: `GCKCastOptions.startDiscoveryAfterFirstTapOnCastButton`
    /// stays at the SDK default (true), so mDNS -- and with it iOS's local-network permission
    /// prompt -- begins on the first cast-button tap, not at launch.
    func setUp() {
        guard !castAvailable else { return }
        if !GCKCastContext.isSharedInstanceInitialized() {
            // Receiver CC1AD845 -- the default media receiver, same as Android's
            // `CastOptionsProvider.kt:36-42`.
            let options = GCKCastOptions(discoveryCriteria:
                GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID))
            do {
                try GCKCastContext.setSharedInstanceWith(options)
            } catch {
                // Nothing else in the app references the SDK, so this is the whole failure
                // handling: no cast button, no mini controller, no session reaction. Never a
                // user-facing error -- casting is an affordance, not a feature the screen needs.
                #if DEBUG
                print("CastController: GCKCastContext could not be created: \(error)")
                #endif
                return
            }
        }
        castAvailable = true
        GCKCastContext.sharedInstance().sessionManager.add(self)
    }

    /// A FRESH mini controller per mount (cubic R2-9). `MainShellView` wraps one of these in a
    /// `UIViewControllerRepresentable` inside the SELECTED tab's stack, so a tab switch dismantles
    /// one wrapper and creates another in an order SwiftUI does not define -- and while a single
    /// owned controller was handed to both, the old wrapper's teardown could remove it from its
    /// NEW parent, blanking the strip mid-session. One controller per representable makes that
    /// impossible. Each one is its own delegate source for `miniControlsActive`; only the selected
    /// tab ever mounts one, so exactly one is live at a time.
    ///
    /// Called only from a mounted strip, which mounts only while `isSessionActive` -- so a context
    /// always exists by then (`sharedInstance()` raises without one).
    func makeMiniControls() -> GCKUIMiniMediaControlsViewController {
        let controls = GCKCastContext.sharedInstance().createMiniMediaControlsViewController()
        controls.delegate = self
        return controls
    }

    // MARK: - Session ownership (review Important 1)

    /// Claims this session for `videoId`. `false` means another mounted `PlayerScreen` already
    /// owns it and this one must not resolve, load or pause anything.
    @discardableResult
    func claimCastSource(_ videoId: String) -> Bool {
        guard let castingVideoId else {
            self.castingVideoId = videoId
            return true
        }
        return castingVideoId == videoId
    }

    /// The ONE start decision (cubic R2-5), run both by the session TRANSITION and by a screen
    /// that MOUNTS into a live session: a session is up, this is not an offline player (m1: a
    /// sandbox file is never castable), and the claim is winnable. Claims as a side effect when it
    /// answers true, exactly like `claimCastSource`.
    ///
    /// Casting used to hang off `.onChange(of: isSessionActive)` alone, which fires only on
    /// transitions -- so connecting to a receiver and then opening another video left the phone
    /// playing locally while the TV kept the old one.
    func claimForCast(videoId: String, isOfflinePlayback: Bool) -> Bool {
        guard isSessionActive, !isOfflinePlayback else { return false }
        return claimCastSource(videoId)
    }

    /// The claiming screen went away (cubic R2-5): give the stamp back so the NEXT screen opened
    /// during the same session can claim it. Keyed on the videoId for the same reason every other
    /// reaction is -- several `PlayerScreen`s can be mounted, and only the owner may release.
    func releaseClaim(_ videoId: String) {
        guard castingVideoId == videoId else { return }
        finishCasting()
    }

    /// Still ours to act on (cubic R2-8): `PlayerScreen.startCasting` awaits a forced resolve
    /// before it pauses the local player, and both the session and the claim can be gone by the
    /// time that lands.
    func stillCasting(_ videoId: String) -> Bool {
        isSessionActive && castingVideoId == videoId
    }

    /// The owning screen has consumed the hand-back: drop the stamp and the spent position.
    func finishCasting() {
        castingVideoId = nil
        lastStreamPosition = nil
    }

    // MARK: - Session lifecycle seams
    //
    // The `GCKSessionManagerListener` callbacks below carry no decisions of their own: every one
    // of them reads what it needs off the non-`Sendable` `GCKSession` and calls one of these.
    // `GCKSessionManager`'s `init` is `NS_UNAVAILABLE` and `GCKSession` is abstract, so these
    // seams are also the only way `CastSessionTests` can drive the lifecycle at all.

    func sessionDidBegin(deviceName: String?) {
        // A new session inherits nothing from the last one (review Important 2): a stale position
        // would show up as a silent wrong seek that looks like a playback bug, not a cast bug.
        lastStreamPosition = nil
        castingVideoId = nil
        // Re-review Minor 2: a failure that landed with no claimant mounted is never consumed, and
        // `.onChange` does not fire again for the same device name -- so the NEXT failure on that
        // device would be silent. A new session is the natural place to drop an unread one.
        lastLoadFailureDevice = nil
        connectedDeviceName = deviceName
        isSessionActive = true
    }

    /// Called from `willEndSession` -- the last moment the receiver's position can be read.
    func sessionWillEnd(position: TimeInterval?) {
        lastStreamPosition = position
    }

    /// The mini controller's `active` flag, from its delegate (same seam shape as the session
    /// callbacks above, and the only way a test can set it without an SDK view controller).
    func miniMediaControlsViewControllerDidChangeActive(_ active: Bool) {
        miniControlsActive = active
    }

    func sessionDidEnd() {
        isSessionActive = false
        connectedDeviceName = nil
        // Re-review Important 2, other direction: nothing else clears this, so a strip stuck above
        // the tab bar after the session ends would be just as SDK-dependent as one that never
        // appears. One assignment removes the dependency.
        miniControlsActive = false
        // `castingVideoId`/`lastStreamPosition` deliberately survive: the owning screen's
        // `.onChange` has not run yet and needs both. `finishCasting()` clears them.
    }

    // MARK: - Load

    /// Session start/resume's load (spec §10): autoplay, at the local player's position. Live
    /// streams keep the builder's default `startTime` (`kGCKInvalidTimeInterval` = live edge).
    func load(_ media: CastMediaInfo, at position: TimeInterval) {
        // `sharedInstance()` raises if no context was ever created, so every SDK read in this type
        // goes through `castAvailable` first.
        guard castAvailable,
              let session = GCKCastContext.sharedInstance().sessionManager.currentSession,
              let client = session.remoteMediaClient else {
            // Not silent (review Minor 2): `reportLoadFailure` needs a named device and there is
            // no session to name, so the banner cannot fire -- say so somewhere, and clear any
            // in-flight request rather than leaving one pointed at a session that is gone.
            #if DEBUG
            print("CastController: load with no current session/remote media client")
            #endif
            cancelLoadRequest()
            reportLoadFailure()
            return
        }
        // Minor 4: a second load issued while the first is in flight would otherwise drop the only
        // strong reference to it (`GCKRequest.delegate` is weak) and its outcome would go
        // unobserved.
        cancelLoadRequest()
        let builder = GCKMediaLoadRequestDataBuilder()
        builder.mediaInformation = CastMedia.gckMediaInformation(from: media)
        builder.autoplay = true
        if media.streamType == .buffered, position > 0 { builder.startTime = position }
        let request = client.loadMedia(with: builder.build())
        request.delegate = self
        loadRequest = request
    }

    private func cancelLoadRequest() {
        if loadRequest?.inProgress == true { loadRequest?.cancel() }
        loadRequest = nil
    }

    /// What one `GCKRequestDelegate` callback should do.
    nonisolated enum LoadCallbackOutcome: Sendable, Equatable {
        /// About an older request: touch nothing.
        case ignore
        /// Retire the stored request, say nothing.
        case clear
        /// Retire it and raise "Couldn't play on {device}".
        case clearAndReport
    }

    /// Both of the load-callback guards, as one pure decision over request ids.
    ///
    /// Re-review Important 1: `GCKRequest.cancel()` aborts with `.cancelled` and tells the delegate
    /// (`GCKRequest.h:23-24,142-148`), so `cancelLoadRequest()` fed its own abort straight into the
    /// failure handler -- every second load raised "Couldn't play on {TV}" for a request the app
    /// itself cancelled, and (via the Important-4 resume) restarted the phone's player while the
    /// real load was still in flight. Neither guard subsumes the other: a SYNCHRONOUS abort arrives
    /// while `loadRequest` is still the cancelled request (identity passes, only the reason saves
    /// it), an ASYNCHRONOUS one arrives after the new request is stored (only identity saves it --
    /// and it is also what stops the handler nil-ing the NEW request's only strong reference, since
    /// `GCKRequest.delegate` is weak).
    ///
    /// Re-review Minor 1: that identity guard used to live in `finishLoad` AHEAD of this helper, so
    /// no test could ever watch it answer false -- deleting it left every test green. Taking the
    /// current id as an argument is what makes it testable.
    static func loadCallbackOutcome(callbackID: GCKRequestID, currentID: GCKRequestID?,
                                    reportFailure: Bool, cancelledByUs: Bool) -> LoadCallbackOutcome {
        guard currentID == callbackID else { return .ignore }
        return reportFailure && !cancelledByUs ? .clearAndReport : .clear
    }

    /// The other way a cast can fail to play: nothing castable to load at all (an embed rung, or a
    /// resolve that did not come back). Same user-visible outcome as a rejected load, so it gets
    /// the same banner rather than copy of its own -- and, per the copy rule, it says WHAT, never
    /// why.
    func reportLoadFailure() {
        // No named device means no session left to blame — and `cast_error_format` is built around
        // the device's name, so there is nothing honest to say. Silence beats "Couldn't play on ".
        guard castAvailable,
              let name = GCKCastContext.sharedInstance().sessionManager.currentSession?.device.friendlyName,
              !name.isEmpty else { return }
        lastLoadFailureDevice = name
    }
}

// MARK: - Session lifecycle (SDK callbacks -> the seams above)

extension CastController: GCKSessionManagerListener {
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) {
        // Read BEFORE the actor hop: `GCKSession` is a non-`Sendable` ObjC object, so carrying it
        // into the closure is a sending violation. Only the `String?` crosses.
        let name = session.device.friendlyName
        MainActor.assumeIsolated { sessionDidBegin(deviceName: name) }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didResumeSession session: GCKSession) {
        let name = session.device.friendlyName
        MainActor.assumeIsolated { sessionDidBegin(deviceName: name) }
    }

    /// WILL, not DID: the remote media client is still connected here, so this is the last moment
    /// the receiver's position can be read.
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, willEnd session: GCKSession) {
        let position = session.remoteMediaClient?.approximateStreamPosition()
        MainActor.assumeIsolated { sessionWillEnd(position: position) }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession,
                                    withError error: (any Error)?) {
        MainActor.assumeIsolated { sessionDidEnd() }
    }
}

// MARK: - Mini controller visibility (review Minor 1)

extension CastController: GCKUIMiniMediaControlsViewControllerDelegate {
    nonisolated func miniMediaControlsViewController(
        _ miniMediaControlsViewController: GCKUIMiniMediaControlsViewController,
        shouldAppear: Bool) {
        MainActor.assumeIsolated { miniMediaControlsViewControllerDidChangeActive(shouldAppear) }
    }
}

// MARK: - Load result (spec §10: "surface 'Couldn't play on {device}' on failure")

extension CastController: GCKRequestDelegate {
    // `requestID` (an `NSInteger`), not the request object: `GCKRequest` is a non-`Sendable` ObjC
    // object and carrying it into the actor hop is a sending violation -- the same reason
    // `willEndSession` reads its position before hopping. Distinct in-flight requests carry
    // distinct ids, which is all the identity check needs.
    nonisolated func requestDidComplete(_ request: GCKRequest) {
        let id = request.requestID
        MainActor.assumeIsolated { finishLoad(id, cancelledByUs: false, reportFailure: false) }
    }

    nonisolated func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        let id = request.requestID
        MainActor.assumeIsolated { finishLoad(id, cancelledByUs: false, reportFailure: true) }
    }

    nonisolated func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
        let id = request.requestID
        let cancelledByUs = abortReason == .cancelled
        MainActor.assumeIsolated { finishLoad(id, cancelledByUs: cancelledByUs, reportFailure: true) }
    }
}

private extension CastController {
    /// The one exit for all three delegate callbacks, executing `loadCallbackOutcome`. A callback
    /// about a request this controller is no longer holding touches nothing -- neither the banner
    /// nor `loadRequest`, which by then may already be the NEXT request's only strong reference.
    func finishLoad(_ requestID: GCKRequestID, cancelledByUs: Bool, reportFailure: Bool) {
        switch Self.loadCallbackOutcome(callbackID: requestID, currentID: loadRequest?.requestID,
                                        reportFailure: reportFailure, cancelledByUs: cancelledByUs) {
        case .ignore:
            return
        case .clear:
            loadRequest = nil
        case .clearAndReport:
            loadRequest = nil
            reportLoadFailure()
        }
    }
}

// MARK: - SwiftUI wrappers (the only cast UI in the app)

/// Reaches the hosted `GCKUICastButton` so the toolbar slot's own `Button` can replay a tap into
/// it (cubic R2-6 / re-review m3). Weak: the SDK button belongs to the view hierarchy.
@MainActor final class CastButtonHandle {
    fileprivate weak var button: GCKUICastButton?

    /// Replay the tap the SDK button would have received itself. `sendActions` dispatches to the
    /// control's registered target/action pairs directly, so it works on a button whose own
    /// interaction is off -- and `PlayerToolbarLayoutTests.theCastButtonAnswersAReplayedTouchUpInside`
    /// pins that `GCKUICastButton` still registers for `.touchUpInside` at all.
    func tap() { button?.sendActions(for: .touchUpInside) }
}

/// `GCKUICastButton` (spec §10: the toolbar's cast affordance). The SDK button owns its own icon
/// states -- connected / connecting / not connected -- and presents the device chooser itself; the
/// first tap is also what starts discovery.
///
/// It renders only. Cubic R2-6 / re-review m3: the toolbar's other four slots wrap icon AND caption
/// in a `Button`, so the whole slot is tappable and the accessibility element is a real button at
/// the ≥44 pt floor. This view is a 24 pt `UIView`, and neither way of enlarging it in place works:
/// `.frame(minWidth: 44, minHeight: 44)` only pads the SwiftUI layout box (the `UIButton`'s own
/// rect -- its hit area AND its accessibility frame -- stays 24×24), while resizing the `UIButton`
/// to 44×44 grows the toolbar row by a measured 22 pt and drops the cast caption out of line with
/// its siblings. So the slot is a `Button` like the other four, `handle` carries its tap in here,
/// and the SDK button's own interaction is off so one tap can never fire twice.
struct CastButton: UIViewRepresentable {
    let handle: CastButtonHandle

    func makeUIView(context: Context) -> GCKUICastButton {
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 24, height: 24))
        button.tintColor = .label
        button.isUserInteractionEnabled = false
        handle.button = button
        return button
    }

    func updateUIView(_ uiView: GCKUICastButton, context: Context) {
        handle.button = uiView
    }

    /// Match the toolbar's icon row: the caption below it is SwiftUI's, so this reports only the
    /// glyph's size and never stretches. This is what keeps the cast caption in line with the
    /// other four slots' -- `theCastSlotWidensTheRowWithoutGrowingIt` is the guard.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: GCKUICastButton, context: Context) -> CGSize? {
        CGSize(width: 24, height: 24)
    }
}

/// `GCKUIMiniMediaControlsViewController` (spec §10: pinned above the tab bar while a session is
/// active). Renders UI only -- it drives the RECEIVER, never this app's `AVAudioSession` or its
/// local player, so the single-audio-owner rule is untouched.
struct CastMiniControls: UIViewControllerRepresentable {
    let controller: CastController

    func makeUIViewController(context: Context) -> GCKUIMiniMediaControlsViewController {
        controller.makeMiniControls()
    }

    func updateUIViewController(_ controller: GCKUIMiniMediaControlsViewController, context: Context) {}

    func sizeThatFits(_ proposal: ProposedViewSize, uiViewController: GCKUIMiniMediaControlsViewController,
                      context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? UIView.noIntrinsicMetric, height: uiViewController.minHeight)
    }
}
