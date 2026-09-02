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
/// `NSObject` subclass because `GCKSessionManagerListener`/`GCKRequestDelegate` both refine
/// `NSObjectProtocol`; their callbacks arrive on the main thread, hence the `assumeIsolated` hops.
@MainActor @Observable final class CastController: NSObject {
    /// False until `setUp()` has actually created (or found) a shared `GCKCastContext`. Per
    /// instance, written only by `setUp()`: the composition root calls it once from the launch
    /// hook, and a container that never did (every fixture container) reports false forever.
    private(set) var castAvailable = false

    /// A Cast session is connected. `PlayerScreen` reacts to the transitions: true -> fresh
    /// resolve + load + pause local; false -> seek local to `lastStreamPosition` and resume.
    private(set) var isSessionActive = false

    /// The receiver's `approximateStreamPosition`, sampled in `willEndSession` while the remote
    /// media client is still connected (by `didEndSession` it may already be gone), and published
    /// when the session actually ends.
    private(set) var lastStreamPosition: TimeInterval?

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
        guard !GCKCastContext.isSharedInstanceInitialized() else {
            castAvailable = true
            observeSessions()
            return
        }
        // Receiver CC1AD845 -- the default media receiver, same as Android's
        // `CastOptionsProvider.kt:36-42`.
        let options = GCKCastOptions(discoveryCriteria:
            GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID))
        do {
            try GCKCastContext.setSharedInstanceWith(options)
        } catch {
            // Nothing else in the app references the SDK, so this is the whole failure handling:
            // no cast button, no mini controller, no session reaction. Never a user-facing error
            // -- casting is an affordance, not a feature the screen depends on.
            return
        }
        castAvailable = true
        observeSessions()
    }

    private func observeSessions() {
        GCKCastContext.sharedInstance().sessionManager.add(self)
    }

    /// Session start/resume's load (spec §10): autoplay, at the local player's position. Live
    /// streams keep the builder's default `startTime` (`kGCKInvalidTimeInterval` = live edge).
    func load(_ media: CastMediaInfo, at position: TimeInterval) {
        // `sharedInstance()` raises if no context was ever created, so every SDK read in this type
        // goes through `castAvailable` first.
        guard castAvailable,
              let session = GCKCastContext.sharedInstance().sessionManager.currentSession,
              let client = session.remoteMediaClient else {
            reportLoadFailure()
            return
        }
        let builder = GCKMediaLoadRequestDataBuilder()
        builder.mediaInformation = CastMedia.gckMediaInformation(from: media)
        builder.autoplay = true
        if media.streamType == .buffered, position > 0 { builder.startTime = position }
        let request = client.loadMedia(with: builder.build())
        request.delegate = self
        loadRequest = request
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

// MARK: - Session lifecycle

extension CastController: GCKSessionManagerListener {
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) {
        MainActor.assumeIsolated { isSessionActive = true }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didResumeSession session: GCKSession) {
        MainActor.assumeIsolated { isSessionActive = true }
    }

    /// WILL, not DID: the remote media client is still connected here, so this is the last moment
    /// the receiver's position can be read.
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, willEnd session: GCKSession) {
        // Read BEFORE the actor hop: `GCKSession` is a non-`Sendable` ObjC object, so carrying it
        // into the closure is a sending violation. Only the `TimeInterval` crosses. Safe because
        // the SDK raises these callbacks on the main thread (the same assumption every
        // `assumeIsolated` here makes).
        let position = session.remoteMediaClient?.approximateStreamPosition()
        MainActor.assumeIsolated { lastStreamPosition = position }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession,
                                    withError error: (any Error)?) {
        MainActor.assumeIsolated { isSessionActive = false }
    }
}

// MARK: - Load result (spec §10: "surface 'Couldn't play on {device}' on failure")

extension CastController: GCKRequestDelegate {
    nonisolated func requestDidComplete(_ request: GCKRequest) {
        MainActor.assumeIsolated { loadRequest = nil }
    }

    nonisolated func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        MainActor.assumeIsolated {
            loadRequest = nil
            reportLoadFailure()
        }
    }

    nonisolated func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
        MainActor.assumeIsolated {
            loadRequest = nil
            reportLoadFailure()
        }
    }
}

// MARK: - SwiftUI wrappers (the only cast UI in the app)

/// `GCKUICastButton` (spec §10: the toolbar's cast affordance). The SDK button owns its own icon
/// states and presents the device chooser itself; the first tap is also what starts discovery.
struct CastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> GCKUICastButton {
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 24, height: 24))
        button.tintColor = .label
        return button
    }

    func updateUIView(_ uiView: GCKUICastButton, context: Context) {}

    /// Match the toolbar's icon row: the caption below it is SwiftUI's, so this reports only the
    /// glyph's size and never stretches.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: GCKUICastButton, context: Context) -> CGSize? {
        CGSize(width: 24, height: 24)
    }
}

/// `GCKUIMiniMediaControlsViewController` (spec §10: pinned above the tab bar while a session is
/// active). Renders UI only -- it drives the RECEIVER, never this app's `AVAudioSession` or its
/// local player, so the single-audio-owner rule is untouched.
struct CastMiniControls: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> GCKUIMiniMediaControlsViewController {
        GCKCastContext.sharedInstance().createMiniMediaControlsViewController()
    }

    func updateUIViewController(_ controller: GCKUIMiniMediaControlsViewController, context: Context) {}

    func sizeThatFits(_ proposal: ProposedViewSize, uiViewController: GCKUIMiniMediaControlsViewController,
                      context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? UIView.noIntrinsicMetric, height: uiViewController.minHeight)
    }
}
