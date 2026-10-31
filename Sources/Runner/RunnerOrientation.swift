// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit

/// **Landscape on iPhone, only while the LIVE runner is on screen** — nightstand mode
/// (docs/IPHONE_DUO_NIGHTSTAND.md).
///
/// An iPhone Duo stood on its edges like a tent puts its outer display in landscape
/// facing the climber, and Apple recommends supporting landscape there for exactly that
/// pose. Everything ELSE on iPhone stays portrait: the one-page Today, History, the
/// builder and the summary are portrait documents, and unlocking them would buy nothing
/// but layouts nobody asked for. So the plist stays portrait-only for iPhone, the app
/// delegate widens the mask while a live runner holds the lock, and the runner turns
/// the screen itself by request — see `apply()` for the measured reason it has to.
///
/// - **The summary is NOT included.** The lock is released the moment a session
///   finishes, so the summary arrives portrait. It is a form you fill in with the phone
///   in your hand; releasing at the finish rather than at dismissal also means the cover
///   never slides away to reveal a Today that is briefly sideways.
/// - **iPad is untouched**: `.all`, exactly what `UISupportedInterfaceOrientations~ipad`
///   already lists. The idiom is read here because the plist key it mirrors is keyed on
///   the idiom — this is orientation POLICY, never a layout decision.
/// - **Holders, not a flag.** A runner that disappears late must not clear the lock a
///   newer one holds, so each runner holds its own token.
/// - The Duo's INNER display ignores supported orientations entirely; none of this
///   reaches it.
@MainActor
enum RunnerOrientation {
    private static var lock = RunnerOrientationLock()

    /// The mask the app delegate returns.
    static func supportedOrientations(isPad: Bool) -> UIInterfaceOrientationMask {
        lock.mask(isPad: isPad)
    }

    /// Ask for (or give back) landscape on behalf of one live runner.
    static func setLandscapeAllowed(_ allowed: Bool, by token: UUID) {
        guard lock.set(allowed, by: token) else { return }
        apply()
    }

    /// While the lock is held, every turn of the phone becomes an explicit request.
    private static var deviceObserver: NSObjectProtocol?

    /// Tell UIKit the mask moved, then DRIVE the rotation rather than wait for it.
    ///
    /// Measured on the iPhone Duo simulator (iOS 27.1, 2026-10-31), three ways:
    /// - With portrait alone in the plist, the delegate's wider mask reached SpringBoard
    ///   ("orientation preferences ( Pu ) -> ( Pu Ll Lr )") and a turn of the device was
    ///   still never followed; only `requestGeometryUpdate` rotated the scene.
    /// - With the landscapes added to the plist, SpringBoard turned the SCENE with the
    ///   device even on Today, and the app — portrait at that moment — was drawn
    ///   counter-rotated inside it; when the runner then widened the mask the window
    ///   stayed counter-rotated, the runner sideways on a sideways phone.
    /// - The working shape: plist portrait-only, so nothing turns unless the app asks,
    ///   and while a runner holds the lock the app asks on every device turn (and once on
    ///   opening, for a phone already on its side — the tent pose, Start tapped on a
    ///   sideways Today). Releasing asks for portrait, so the summary and Today come back
    ///   upright whatever the phone is doing.
    private static func apply() {
        guard UIDevice.current.userInterfaceIdiom != .pad else { return }
        let device = UIDevice.current
        updateSupportedOrientations()
        if lock.allowsLandscape {
            device.beginGeneratingDeviceOrientationNotifications()
            deviceObserver = NotificationCenter.default.addObserver(
                forName: UIDevice.orientationDidChangeNotification, object: nil, queue: .main
            ) { _ in
                MainActor.assumeIsolated { follow(UIDevice.current.orientation) }
            }
            follow(device.orientation)
        } else {
            if let deviceObserver { NotificationCenter.default.removeObserver(deviceObserver) }
            deviceObserver = nil
            device.endGeneratingDeviceOrientationNotifications()
            request(.portrait)
        }
    }

    /// Turn the screen with the phone, among the orientations the runner allows. Flat
    /// and unknown readings change nothing — a phone laid on the bench keeps the
    /// orientation it was last held in.
    private static func follow(_ orientation: UIDeviceOrientation) {
        guard lock.allowsLandscape else { return }
        if orientation == .portrait {
            request(.portrait)
        } else if let landscape = RunnerOrientationLock.interfaceMask(matching: orientation) {
            request(landscape)
        }
    }

    private static func request(_ mask: UIInterfaceOrientationMask) {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
        }
    }

    private static func updateSupportedOrientations() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows {
                var controller = window.rootViewController
                while let current = controller {
                    current.setNeedsUpdateOfSupportedInterfaceOrientations()
                    controller = current.presentedViewController
                }
            }
        }
    }
}

/// The lock's arithmetic, pure so a test can pin it without a window.
struct RunnerOrientationLock: Equatable {
    private(set) var holders: Set<UUID> = []

    var allowsLandscape: Bool { !holders.isEmpty }

    /// iPhone: portrait, plus both landscapes while any runner holds the lock. Never
    /// upside down — the house rule for iPhone, and a tented phone is never inverted.
    func mask(isPad: Bool) -> UIInterfaceOrientationMask {
        if isPad { return .all }
        return allowsLandscape ? [.portrait, .landscapeLeft, .landscapeRight] : .portrait
    }

    /// The interface orientation that keeps the screen upright for a phone lying in
    /// `device` orientation — mirrored, because a device turned LEFT shows its interface
    /// turned RIGHT. Nil when the phone is not on its side (portrait, flat, unknown).
    static func interfaceMask(matching device: UIDeviceOrientation) -> UIInterfaceOrientationMask? {
        switch device {
        case .landscapeLeft: .landscapeRight
        case .landscapeRight: .landscapeLeft
        default: nil
        }
    }

    /// Returns whether the iPhone mask actually changed.
    @discardableResult
    mutating func set(_ allowed: Bool, by token: UUID) -> Bool {
        let before = allowsLandscape
        if allowed { holders.insert(token) } else { holders.remove(token) }
        return before != allowsLandscape
    }
}

/// The app delegate exists for ONE answer: the runtime orientation mask. Everything
/// else the app does still lives in the SwiftUI `App`.
final class DoigtAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        RunnerOrientation.supportedOrientations(isPad: UIDevice.current.userInterfaceIdiom == .pad)
    }
}
