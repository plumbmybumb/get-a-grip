// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreTransferable
import Foundation
import UniformTypeIdentifiers

/// Share the original PNG bytes with a filename. CoreTransferable owns any temporary
/// file required by the receiver, so repeated shares do not leave app-created copies.
struct SharePNG: Transferable, Sendable {
    let data: Data
    let filename: String

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .png) { $0.data }
            .suggestedFileName { $0.filename }
    }
}
