// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import MessageUI
import SwiftUI
import UIKit

/// The system mail sheet, wrapped as thinly as it can be.
///
/// A WRAPPER only: every decision about what is sent belongs to the presenting view. That
/// keeps mail out of the stores — `DeviceStore` never learns email exists, and this file
/// never learns what a gauge is.
struct MailComposer: UIViewControllerRepresentable {
    /// A file riding along with the message. One is enough — the only attachment this app
    /// ever sends is the breadcrumb report.
    struct Attachment {
        var data: Data
        var mimeType: String
        var fileName: String
    }

    var recipients: [String]
    var subject: String
    var body: String
    var attachment: Attachment?
    /// Called for EVERY result, and the only thing that takes the sheet down; dismissing
    /// only on success would make a sheet you cannot leave.
    var onFinish: () -> Void

    /// The controller presents an EMPTY sheet with no mail account, so callers must ask
    /// first. Spelled once so the view and its `mailto:` fallback agree.
    static var canSend: Bool { MFMailComposeViewController.canSendMail() }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let composer = MFMailComposeViewController()
        composer.mailComposeDelegate = context.coordinator
        composer.setToRecipients(recipients)
        composer.setSubject(subject)
        composer.setMessageBody(body, isHTML: false)
        if let attachment {
            composer.addAttachmentData(attachment.data,
                                       mimeType: attachment.mimeType,
                                       fileName: attachment.fileName)
        }
        return composer
    }

    /// Empty: set once at presentation; rewriting a half-typed body would be worse than
    /// useless.
    func updateUIViewController(_ controller: MFMailComposeViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    /// `@preconcurrency`, as with `LiveProgressorClient`'s CoreBluetooth delegates, lets a
    /// main-actor method witness UIKit's nonisolated `@objc` requirement. A `nonisolated`
    /// method hopping inside would send the non-Sendable controller across isolation, which
    /// Swift 6 rejects.
    @MainActor
    final class Coordinator: NSObject, @preconcurrency MFMailComposeViewControllerDelegate {
        private let onFinish: () -> Void

        init(onFinish: @escaping () -> Void) {
            self.onFinish = onFinish
        }

        func mailComposeController(_ controller: MFMailComposeViewController,
                                   didFinishWith result: MFMailComposeResult,
                                   error: Error?) {
            onFinish()
        }
    }
}
