// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import MessageUI
import SwiftUI
import UIKit

/// The system mail sheet, wrapped as thinly as it can be wrapped.
///
/// It is a WRAPPER and nothing else: the composer owns its own UI, and every decision
/// about what gets sent — the address, the subject, the footer of app and device facts,
/// whether the diagnostics ring rides along — belongs to the view that presents it. That
/// is what keeps mail out of the stores: `DeviceStore` never learns that email exists,
/// and this file never learns what a gauge is.
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
    /// Called for EVERY result — sent, saved, cancelled, failed — and it is what has to
    /// take the sheet down: nothing else will. A composer that dismissed only on success
    /// would be a sheet you cannot leave.
    var onFinish: () -> Void

    /// `MFMailComposeViewController` presents an EMPTY sheet when there is no mail
    /// account configured, so every caller must ask first. Spelled once, here, so the
    /// presenting view and its `mailto:` fallback can never disagree about the answer.
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

    /// Deliberately empty. Everything the composer carries is set once at presentation;
    /// rewriting a body somebody is halfway through typing would be worse than useless.
    func updateUIViewController(_ controller: MFMailComposeViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    /// `@preconcurrency` on the conformance is the same shape `LiveProgressorClient` uses
    /// for CoreBluetooth's delegates: it is what lets a main-actor-isolated method witness
    /// UIKit's nonisolated `@objc` requirement. Declaring the method `nonisolated` and
    /// hopping inside instead means sending the non-Sendable controller across an
    /// isolation boundary, which Swift 6 rejects outright.
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
