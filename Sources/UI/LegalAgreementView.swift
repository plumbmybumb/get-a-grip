// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import SwiftUI

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
    @State private var store = LegalAgreementStore()
    var body: some View {
        if let loaded = LegalBundle.loaded {
            let bundle = loaded.document
            let language = LegalBundle.language(locale)
            LegalDocumentLink(bundle: bundle, language: language, kind: "terms")
            LegalDocumentLink(bundle: bundle, language: language, kind: "privacy")
            // Earlier builds requested an acknowledgement. Keep genuine historical
            // records accessible, without asking for or fabricating new acceptance.
            if !store.records.isEmpty {
                NavigationLink(bundle.text("receipt", language)) {
                    LegalReceiptView(bundle: bundle, language: language)
                }
                .frame(minHeight: 44).tint(Accent.graphite)
            }
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
