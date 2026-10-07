import AppKit
import SwiftProtobuf
import SyncedNotesCore

/// Turns the editor's text into the stored note format (SyncedNotesCore/Proto/note.proto) and back.
///
/// Both directions record meaning, not looks: a heading is saved as "heading", and on the way
/// back gets whatever font a heading has, so the note looks right however fonts change.
/// Anything that could not be read back (a picture whose file is gone) keeps its place, so
/// saving again never quietly drops it.
enum NoteFormat {

    struct Encoded {
        var note: StoredNote
        /// Pictures whose bytes are here, to store beside the note.
        var pictures: [(id: Data, bytes: Data)]
        /// The first line with any words: the note's name in the list.
        var title: String
        /// Every word in the note, table cells included, for search.
        var words: String
    }

    // MARK: - Editor to stored

    static func encode(_ text: NSAttributedString) -> Encoded {
        var encoder = Encoder()
        var note = StoredNote()
        note.formatVersion = 1
        note.paragraphs = encoder.paragraphs(of: text)
        // Worked out from the stored note, as an import does, so the two always agree.
        return Encoded(note: note, pictures: encoder.pictures, title: note.title, words: note.words.joined(separator: "\n"))
    }

    private struct Encoder {
        var pictures: [(id: Data, bytes: Data)] = []
        /// Each NSTextList met so far, numbered in order: the list IDs within the note.
        var lists: [ObjectIdentifier: UInt32] = [:]

        mutating func paragraphs(of text: NSAttributedString) -> [StoredParagraph] {
            let string = text.string as NSString
            var result: [StoredParagraph] = []
            var location = 0
            repeat {
                let whole = string.paragraphRange(for: NSRange(location: location, length: 0))
                var content = whole
                while content.length > 0, Self.isLineBreak(string.character(at: NSMaxRange(content) - 1)) {
                    content.length -= 1
                }
                result.append(paragraph(text, content: content, attributesAt: whole.length > 0 ? whole.location : nil))
                location = NSMaxRange(whole)
            } while location < string.length
            // Text ending in a line break has one more, empty paragraph after it.
            if string.length > 0, Self.isLineBreak(string.character(at: string.length - 1)) {
                result.append(paragraph(text, content: NSRange(location: string.length, length: 0), attributesAt: string.length - 1))
            }
            return result
        }

        private static func isLineBreak(_ character: unichar) -> Bool {
            character == 0x0A || character == 0x0D || character == 0x2029
        }

        private mutating func paragraph(_ text: NSAttributedString, content: NSRange, attributesAt location: Int?) -> StoredParagraph {
            var paragraph = StoredParagraph()
            let plain = (text.string as NSString).substring(with: content)
            paragraph.text = plain

            let attributes = location.map { text.attributes(at: $0, effectiveRange: nil) } ?? [:]
            let role = (attributes[NoteTextView.roleKey] as? String).flatMap(NoteTextView.ParagraphRole.init(rawValue:)) ?? .body
            paragraph.role = Self.stored(role)
            paragraph.checked = role == .checklist && attributes[NoteTextView.checkedKey] as? Bool == true

            let style = attributes[.paragraphStyle] as? NSParagraphStyle ?? .default
            paragraph.lists = style.textLists.map { list in
                var level = StoredListLevel()
                level.kind = Self.stored(ListKind(list.markerFormat))
                let key = ObjectIdentifier(list)
                if lists[key] == nil { lists[key] = UInt32(lists.count + 1) }
                level.list = lists[key]!
                level.start = list.startingItemNumber > 1 ? UInt32(list.startingItemNumber) : 0
                return level
            }
            paragraph.alignment = Self.stored(style.alignment)
            paragraph.direction = Self.stored(style.baseWritingDirection)
            let steps = ((style.headIndent - NoteFormat.indent(of: role)) / NoteTextView.indentStep).rounded()
            paragraph.indent = UInt32(max(0, steps))
            paragraph.lineSpacing = style.lineHeightMultiple == 0 || style.lineHeightMultiple == 1 ? 0 : Float(style.lineHeightMultiple)

            paragraph.runs = runs(text, in: content, role: role)
            return paragraph
        }

        private mutating func runs(_ text: NSAttributedString, in range: NSRange, role: NoteTextView.ParagraphRole) -> [StoredRun] {
            var runs: [StoredRun] = []
            text.enumerateAttributes(in: range) { attributes, span, _ in
                var run = StoredRun()
                run.start = UInt32(span.location - range.location)
                run.length = UInt32(span.length)
                let font = attributes[.font] as? NSFont ?? NoteTextView.plainFont(for: role)
                // A code block is monospaced as a whole; only code inside other text is marked.
                run.code = role != .codeBlock && font.isFixedPitch
                let plain = NoteTextView.plainFont(for: role)
                // A heading's own weight is part of the heading, not bold added to it.
                run.bold = NoteTextView.has(.boldFontMask, font) && !NoteTextView.has(.boldFontMask, plain)
                run.italic = NoteTextView.has(.italicFontMask, font)
                let scale = font.pointSize / plain.pointSize
                run.relativeSize = abs(scale - 1) < 0.001 ? 0 : Float(scale)
                run.underline = (attributes[.underlineStyle] as? Int ?? 0) != 0
                run.strikethrough = (attributes[.strikethroughStyle] as? Int ?? 0) != 0
                run.highlight = Self.stored((attributes[NoteTextView.highlightKey] as? String).flatMap(HighlightColor.init(rawValue:)))
                if let color = attributes[.foregroundColor] as? NSColor, let stored = Self.stored(color) {
                    run.color = stored
                }
                if let attachment = attributes[.attachment] as? NSTextAttachment,
                   let embedded = embedded(attachment, role: attributes[NoteTextView.roleKey] as? String) {
                    run.embedded = embedded
                }
                let plainRun = StoredRun.with { $0.start = run.start; $0.length = run.length }
                guard run != plainRun else { return }
                // Neighbours that differ only in things not recorded here (fonts of the same
                // family, say) become one run.
                if var last = runs.last, last.start + last.length == run.start, !run.hasEmbedded, !last.hasEmbedded,
                   StoredRun.with({ $0 = run; $0.start = 0; $0.length = 0 }) == StoredRun.with({ $0 = last; $0.start = 0; $0.length = 0 }) {
                    last.length += run.length
                    runs[runs.count - 1] = last
                } else {
                    runs.append(run)
                }
            }
            return runs
        }

        /// What an attachment is, or nil for one the note format has no place for (a file
        /// dropped in that is not a picture): it is not dressed up as something else.
        private mutating func embedded(_ attachment: NSTextAttachment, role: String?) -> StoredEmbedded? {
            var embedded = StoredEmbedded()
            if let picture = attachment as? PictureAttachment {
                embedded.picture = StoredPicture.with {
                    $0.id = picture.pictureID
                    $0.type = picture.pictureType
                    $0.width = UInt32(picture.naturalSize.width.rounded())
                    $0.height = UInt32(picture.naturalSize.height.rounded())
                }
                if let bytes = picture.contents { pictures.append((picture.pictureID, bytes)) }
            } else if let table = attachment as? TableAttachment {
                embedded.table = StoredTable.with { stored in
                    stored.rows = table.cells.map { row in
                        StoredRow.with { $0.cells = row.map { cell in StoredCell.with { $0.paragraphs = paragraphs(of: cell) } } }
                    }
                }
            } else if role == NoteTextView.ParagraphRole.divider.rawValue {
                embedded.divider = StoredDivider()
            } else {
                return nil
            }
            return embedded
        }

        private static func stored(_ role: NoteTextView.ParagraphRole) -> StoredRole {
            switch role {
            case .body: .body
            case .title: .title
            case .heading: .heading
            case .subheading: .subheading
            case .quote: .quote
            case .codeBlock: .codeBlock
            case .checklist: .checklist
            case .divider: .divider
            }
        }

        private static func stored(_ kind: ListKind?) -> StoredListKind {
            switch kind {
            case .bulleted: .bulleted
            case .dashed: .dashed
            case .numbered: .numbered
            case nil: .unspecified
            }
        }

        private static func stored(_ alignment: NSTextAlignment) -> StoredAlignment {
            switch alignment {
            case .left: .left
            case .center: .center
            case .right: .right
            case .justified: .justified
            default: .natural
            }
        }

        private static func stored(_ direction: NSWritingDirection) -> StoredDirection {
            switch direction {
            case .leftToRight: .leftToRight
            case .rightToLeft: .rightToLeft
            default: .natural
            }
        }

        private static func stored(_ highlight: HighlightColor?) -> StoredHighlight {
            switch highlight {
            case .yellow: .yellow
            case .green: .green
            case .blue: .blue
            case .pink: .pink
            case .purple: .purple
            case .orange: .orange
            case nil: .none
            }
        }

        /// A colour picked by hand, in sRGB; nil for the normal text colour, which follows
        /// light and dark mode and so has no fixed value to save, and for a fixed black or
        /// white, which would vanish in one of the two (see `TextColor.followsAppearance`).
        private static func stored(_ color: NSColor) -> StoredColor? {
            if TextColor.followsAppearance(color) { return nil }
            guard let rgb = color.usingColorSpace(.sRGB) else { return nil }
            return StoredColor.with {
                ($0.red, $0.green, $0.blue, $0.alpha) = (Float(rgb.redComponent), Float(rgb.greenComponent), Float(rgb.blueComponent), Float(rgb.alphaComponent))
            }
        }
    }

    /// The indent a role brings with it, before any the user adds. Nonisolated, as the
    /// checklist fragment reads it while TextKit draws.
    nonisolated static func indent(of role: NoteTextView.ParagraphRole) -> CGFloat {
        switch role {
        case .quote: 20
        case .codeBlock: 12
        case .checklist: 24
        default: 0
        }
    }

    // MARK: - Stored to editor

    /// The note as editor text. [picture] gives a picture's bytes by ID, nil if unavailable.
    static func decode(_ note: StoredNote, picture: @escaping (Data) -> Data?) -> NSAttributedString {
        var decoder = Decoder(picture: picture)
        return decoder.text(of: note.paragraphs)
    }

    private struct Decoder {
        let picture: (Data) -> Data?
        /// The NSTextList for each list ID and level: one object per list, so its numbering
        /// runs on (TextKit numbers paragraphs together only when they share the object).
        var lists: [UInt32: NSTextList] = [:]

        mutating func text(of paragraphs: [StoredParagraph]) -> NSAttributedString {
            let result = NSMutableAttributedString()
            for (index, stored) in paragraphs.enumerated() {
                let base = baseAttributes(stored)
                let paragraph = NSMutableAttributedString(string: stored.text, attributes: base)
                for run in stored.runs { apply(run, to: paragraph, role: role(stored)) }
                result.append(paragraph)
                if index < paragraphs.count - 1 {
                    result.append(NSAttributedString(string: "\n", attributes: base))
                }
            }
            return result
        }

        private func role(_ stored: StoredParagraph) -> NoteTextView.ParagraphRole {
            switch stored.role {
            case .title: .title
            case .heading: .heading
            case .subheading: .subheading
            case .quote: .quote
            case .codeBlock: .codeBlock
            case .checklist: .checklist
            case .divider: .divider
            default: .body
            }
        }

        private mutating func baseAttributes(_ stored: StoredParagraph) -> [NSAttributedString.Key: Any] {
            let role = role(stored)
            var attributes: [NSAttributedString.Key: Any] = [
                .font: NoteTextView.plainFont(for: role),
                .foregroundColor: NSColor.textColor,
                .paragraphStyle: paragraphStyle(stored, role: role),
            ]
            if role != .body { attributes[NoteTextView.roleKey] = role.rawValue }
            if role == .checklist, stored.checked { attributes[NoteTextView.checkedKey] = true }
            return attributes
        }

        private mutating func paragraphStyle(_ stored: StoredParagraph, role: NoteTextView.ParagraphRole) -> NSParagraphStyle {
            let style = NSMutableParagraphStyle()
            var parentFormat: NSTextList.MarkerFormat?
            style.textLists = stored.lists.map { level in
                let format = parentFormat.map(NoteTextView.nestedFormat) ?? Self.format(level.kind)
                parentFormat = format
                if let existing = lists[level.list] { return existing }
                let list = NSTextList(markerFormat: format, options: [], startingItemNumber: max(1, Int(level.start)))
                lists[level.list] = list
                return list
            }
            let indent = NoteFormat.indent(of: role) + CGFloat(stored.indent) * NoteTextView.indentStep
            (style.headIndent, style.firstLineHeadIndent) = (indent, indent)
            if role == .codeBlock { style.tailIndent = -12 }
            style.alignment = switch stored.alignment {
            case .left: .left
            case .center: .center
            case .right: .right
            case .justified: .justified
            default: .natural
            }
            style.baseWritingDirection = switch stored.direction {
            case .leftToRight: .leftToRight
            case .rightToLeft: .rightToLeft
            default: .natural
            }
            if stored.lineSpacing > 0 { style.lineHeightMultiple = CGFloat(stored.lineSpacing) }
            return style
        }

        private static func format(_ kind: StoredListKind) -> NSTextList.MarkerFormat {
            switch kind {
            case .dashed: .hyphen
            case .numbered: NoteTextView.numberedFormat
            default: .disc
            }
        }

        private mutating func apply(_ run: StoredRun, to paragraph: NSMutableAttributedString, role: NoteTextView.ParagraphRole) {
            let range = NSRange(location: Int(run.start), length: Int(run.length))
            // A run that does not fit its paragraph's text is damage, not something to crash on.
            guard NSMaxRange(range) <= paragraph.length, range.length > 0 else { return }
            var font = NoteTextView.plainFont(for: role)
            if run.code { font = NoteTextView.codeFont(like: font) }
            if run.bold { font = NSFontManager.shared.convert(font, toHaveTrait: .boldFontMask) }
            if run.italic { font = NSFontManager.shared.convert(font, toHaveTrait: .italicFontMask) }
            if run.relativeSize > 0 { font = NSFontManager.shared.convert(font, toSize: font.pointSize * CGFloat(run.relativeSize)) }
            paragraph.addAttribute(.font, value: font, range: range)
            if run.underline { paragraph.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: range) }
            if run.strikethrough { paragraph.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: range) }
            if let highlight = Self.highlight(run.highlight) {
                paragraph.addAttribute(NoteTextView.highlightKey, value: highlight.rawValue, range: range)
                paragraph.addAttribute(.backgroundColor, value: highlight.color, range: range)
            }
            if run.hasColor {
                let color = NSColor(srgbRed: CGFloat(run.color.red), green: CGFloat(run.color.green), blue: CGFloat(run.color.blue), alpha: CGFloat(run.color.alpha))
                // A black or white saved before such colours were refused keeps the normal one.
                if !TextColor.followsAppearance(color) { paragraph.addAttribute(.foregroundColor, value: color, range: range) }
            }
            if run.hasEmbedded, let attachment = attachment(run.embedded) {
                paragraph.addAttribute(.attachment, value: attachment, range: range)
                if case .divider = run.embedded.kind {
                    paragraph.addAttribute(NoteTextView.roleKey, value: NoteTextView.ParagraphRole.divider.rawValue, range: range)
                }
            }
        }

        private mutating func attachment(_ embedded: StoredEmbedded) -> NSTextAttachment? {
            switch embedded.kind {
            case .picture(let stored):
                let size = CGSize(width: CGFloat(stored.width), height: CGFloat(stored.height))
                if let bytes = picture(stored.id), let loaded = PictureAttachment(data: bytes, type: stored.type, id: stored.id) {
                    return loaded
                }
                return PictureAttachment(missing: stored.id, type: stored.type, size: size)
            case .table(let stored):
                // Every row as wide as the widest, and at least one cell, so a damaged table
                // still comes back as a usable grid rather than a broken one.
                let columns = max(stored.rows.map(\.cells.count).max() ?? 0, 1)
                var cells = stored.rows.map { row in
                    row.cells.map { cell in
                        var cellDecoder = Decoder(picture: picture)
                        return cellDecoder.text(of: cell.paragraphs)
                    } + Array(repeating: NSAttributedString(), count: columns - row.cells.count)
                }
                if cells.isEmpty { cells = [Array(repeating: NSAttributedString(), count: columns)] }
                let table = TableAttachment(rows: cells.count, columns: columns)
                table.cells = cells
                return table
            case .divider:
                return NoteDivider.make(attributes: [:]).attribute(.attachment, at: 0, effectiveRange: nil) as? NSTextAttachment
            case nil:
                return nil
            }
        }

        private static func highlight(_ stored: StoredHighlight) -> HighlightColor? {
            switch stored {
            case .yellow: .yellow
            case .green: .green
            case .blue: .blue
            case .pink: .pink
            case .purple: .purple
            case .orange: .orange
            default: nil
            }
        }
    }
}
