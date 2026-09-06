// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import SwiftUI

/// Construct the runner only after acceptance. Closing this view preserves the caller's records.
struct TrainingAgreementGate<Content: View>: View {
    let onCancel: () -> Void
    @ViewBuilder var content: () -> Content
    @Environment(\.locale) private var locale
    @State private var store = LegalAgreementStore()
    @State private var checked = false
    @State private var saveFailed = false

    var body: some View {
        if let loaded = LegalBundle.loaded {
            if store.hasAccepted(loaded.document, fingerprint: loaded.fingerprint) {
                content()
            } else {
                agreement(loaded.document, fingerprint: loaded.fingerprint)
            }
        } else {
            ContentUnavailableView {
                Text(locale.language.languageCode?.identifier == "fr" ? "Documents indisponibles" : "Documents unavailable")
            } actions: {
                Button(locale.language.languageCode?.identifier == "fr" ? "Retour" : "Go back", action: onCancel)
            }
        }
    }

    private func agreement(_ bundle: LegalBundle, fingerprint: String) -> some View {
        let language = LegalBundle.language(locale)
        let text = { bundle.text($0, language) }
        return NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(text("intro")).font(.title3.weight(.medium))
                    Text(text("risk")).foregroundStyle(Ink.secondary)
                    Text(text("choice")).font(.footnote).foregroundStyle(Ink.secondary)
                    VStack(spacing: 0) {
                        LegalDocumentLink(bundle: bundle, language: language, kind: "terms")
                        LegalDocumentLink(bundle: bundle, language: language, kind: "privacy")
                    }
                    Text("\(text("version")): \(bundle.version)").font(.caption).foregroundStyle(Ink.secondary)
                    Toggle(isOn: $checked) { Text(text("checkbox")) }
                        .toggleStyle(.switch)
                        .accessibilityIdentifier("legal.agree")
                    if saveFailed { Text(text("error")).foregroundStyle(Accent.alarm).accessibilityIdentifier("legal.error") }
                    Button {
                        do { try store.accept(bundle, fingerprint: fingerprint, language: language) }
                        catch { saveFailed = true }
                    } label: {
                        Text(text("accept")).bold().frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.borderedProminent).tint(Accent.graphite).disabled(!checked)
                    .accessibilityIdentifier("legal.continue")
                    Text(text("access")).font(.footnote).foregroundStyle(Ink.secondary)
                }
                .padding(Metrics.hPadding)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .background { AppBackground() }
            .navigationTitle(text("heading")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(text("cancel"), action: onCancel) } }
        }
        .onChange(of: language) { _, _ in checked = false; saveFailed = false }
        .interactiveDismissDisabled()
    }
}

private struct LegalDocumentLink: View {
    let bundle: LegalBundle
    let language: String
    let kind: String
    var body: some View {
        if let document = bundle.documents[language]?[kind] {
            NavigationLink {
                LegalDocumentView(document: document, bundle: bundle, language: language)
            } label: {
                HStack {
                    Label(bundle.text(kind, language), systemImage: kind == "terms" ? "doc.text" : "hand.raised")
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption)
                }
                .frame(minHeight: 48).contentShape(Rectangle())
            }
            .tint(Accent.graphite)
        }
    }
}

private struct LegalDocumentView: View {
    let document: LegalDocument
    let bundle: LegalBundle
    let language: String
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("\(bundle.text("version", language)): \(bundle.version)").font(.caption).foregroundStyle(Ink.secondary)
                Text(document.summary).font(.title3)
                ForEach(document.sections.indices, id: \.self) { index in
                    let section = document.sections[index]
                    VStack(alignment: .leading, spacing: 10) {
                        Text(section.title).font(.headline)
                        ForEach(section.paragraphs, id: \.self) { Text($0) }
                    }
                }
            }
            .textSelection(.enabled)
            .padding(Metrics.hPadding).frame(maxWidth: Metrics.maxContentWidth).frame(maxWidth: .infinity)
        }
        .background { AppBackground() }
        .navigationTitle(document.title).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ShareLink(item: "\(bundle.version)\n\n\(document.plainText)") {
                Label(bundle.text("share", language), systemImage: "square.and.arrow.up")
            }
        }
    }
}

struct LegalSettingsLinks: View {
    @Environment(\.locale) private var locale
    var body: some View {
        if let loaded = LegalBundle.loaded {
            let bundle = loaded.document
            let language = LegalBundle.language(locale)
            LegalDocumentLink(bundle: bundle, language: language, kind: "terms")
            LegalDocumentLink(bundle: bundle, language: language, kind: "privacy")
            NavigationLink(bundle.text("receipt", language)) {
                LegalReceiptView(bundle: bundle, language: language)
            }
            .frame(minHeight: 44).tint(Accent.graphite)
        }
    }
}

private struct LegalReceiptView: View {
    let bundle: LegalBundle
    let language: String
    @State private var store = LegalAgreementStore()
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(bundle.text("recordNote", language)).foregroundStyle(Ink.secondary)
                if let receipt = store.receipt {
                    ForEach(Array(store.records.enumerated()), id: \.offset) { _, record in
                        Text("\(bundle.text("version", language)): \(record.version)\n\(bundle.text("language", language)): \(record.language)\n\(bundle.text("date", language)): \(record.acceptedUTC)\n\(bundle.text("appVersion", language)): \(record.appVersion)")
                    }
                    ShareLink(item: receipt) { Label(bundle.text("share", language), systemImage: "square.and.arrow.up") }
                } else { Text(bundle.text("noReceipt", language)) }
            }.textSelection(.enabled).padding(Metrics.hPadding)
        }
        .background { AppBackground() }
        .navigationTitle(bundle.text("receipt", language)).navigationBarTitleDisplayMode(.inline)
    }
}
