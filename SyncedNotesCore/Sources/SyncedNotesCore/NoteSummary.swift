import Foundation

// What the list and search need from a note, worked out from the stored note alone. One
// definition, used both by the editor when it saves and by an import, so the two can never
// name or index a note differently.

extension StoredNote {

    /// Every paragraph's text, table cells included, without the U+FFFC placeholders.
    public var words: [String] {
        paragraphs.flatMap(Self.words(of:))
    }

    /// The first line with any words: the note's name in the list.
    public var title: String {
        let first = words.lazy.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty } ?? ""
        return String(first.prefix(120))
    }

    /// Whether there is nothing in it at all: no words, no picture, table or divider.
    public var isEmpty: Bool {
        title.isEmpty && !paragraphs.contains { $0.runs.contains(where: \.hasEmbedded) }
    }

    /// The IDs of the pictures it shows, table cells included.
    public var pictureIDs: [Data] {
        paragraphs.flatMap(Self.pictureIDs(in:))
    }

    private static func words(of paragraph: StoredParagraph) -> [String] {
        let own = paragraph.text.replacingOccurrences(of: "\u{FFFC}", with: " ")
        let cells = paragraph.runs.flatMap { run -> [String] in
            guard case .table(let table) = run.embedded.kind else { return [] }
            return table.rows.flatMap { $0.cells.flatMap { $0.paragraphs.flatMap(words(of:)) } }
        }
        return [own] + cells
    }

    private static func pictureIDs(in paragraph: StoredParagraph) -> [Data] {
        paragraph.runs.flatMap { run -> [Data] in
            switch run.embedded.kind {
            case .picture(let picture): [picture.id]
            case .table(let table): table.rows.flatMap { $0.cells.flatMap { $0.paragraphs.flatMap(pictureIDs(in:)) } }
            default: []
            }
        }
    }
}
