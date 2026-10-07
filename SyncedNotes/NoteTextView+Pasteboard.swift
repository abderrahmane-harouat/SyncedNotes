import AppKit
import SwiftProtobuf
import SyncedNotesCore
import UniformTypeIdentifiers

/// Copying out of a note, and pasting or dropping into one.
///
/// Everything pasted becomes note text through the note format itself (SyncedNotesCore/Proto/note.proto):
/// what comes in is what a note can save, in the note's own fonts and colours.
///
/// - Between notes, a copy also carries the note format (`noteType`), so headings, checklists,
///   highlights, colours and pictures arrive as they left. RTF, which AppKit copies too, has no
///   place for any of them.
/// - From other apps, rich text keeps bold, italic, underline, strikethrough, code, lists and
///   pictures, and loses its fonts, sizes, colours and backgrounds. Those made pasted text
///   small and in another typeface, and a web page's text has no colour of its own, so it
///   was drawn black, and vanished, in dark mode.
extension NoteTextView {

    /// A piece of a note: its `StoredNote` bytes, and the bytes of its pictures.
    static let noteType = NSPasteboard.PasteboardType("com.syncednotes.mac.note")

    // MARK: - Copying

    override var writablePasteboardTypes: [NSPasteboard.PasteboardType] {
        var types = [Self.noteType] + super.writablePasteboardTypes
        // A picture copied alone is offered as a picture too: apps such as Messages' or a
        // browser's text fields, and Preview, take pictures but not rich text.
        if let picture = selectedPicture { types += Self.pictureTypes(picture) }
        return types
    }

    /// Written here rather than by AppKit, so the clipboard is marked for this Mac only:
    /// Universal Clipboard would otherwise hand a copied note to every nearby Apple device on
    /// the account (Privacy.md).
    override func writeSelection(to pasteboard: NSPasteboard, types: [NSPasteboard.PasteboardType]) -> Bool {
        writeSelectionForThisMacOnly(to: pasteboard, types: types)
    }

    override func writeSelection(to pasteboard: NSPasteboard, type: NSPasteboard.PasteboardType) -> Bool {
        if type == Self.noteType {
            guard let data = copiedNote() else { return false }
            return pasteboard.setData(data, forType: type)
        }
        if let picture = selectedPicture, Self.pictureTypes(picture).contains(type) {
            guard let data = Self.pictureData(picture, as: type) else { return false }
            return pasteboard.setData(data, forType: type)
        }
        return super.writeSelection(to: pasteboard, type: type)
    }

    private func copiedNote() -> Data? {
        guard let storage = textStorage else { return nil }
        let ranges = selectedRanges.map(\.rangeValue).filter { $0.length > 0 }
        guard !ranges.isEmpty else { return nil }
        let selection = NSMutableAttributedString()
        for (index, range) in ranges.enumerated() {
            if index > 0 { selection.append(NSAttributedString(string: "\n")) }
            selection.append(storage.attributedSubstring(from: range))
        }
        let encoded = NoteFormat.encode(selection)
        guard let note = try? encoded.note.serializedData() else { return nil }
        let pictures = encoded.pictures.map { ["id": $0.id, "bytes": $0.bytes] }
        return try? PropertyListSerialization.data(fromPropertyList: ["note": note, "pictures": pictures], format: .binary, options: 0)
    }

    private static func pictureTypes(_ picture: PictureAttachment) -> [NSPasteboard.PasteboardType] {
        let own = NSPasteboard.PasteboardType(picture.pictureType)
        return own == .png ? [.png] : [own, .png]
    }

    private static func pictureData(_ picture: PictureAttachment, as type: NSPasteboard.PasteboardType) -> Data? {
        guard let bytes = picture.contents else { return nil }
        if type.rawValue == picture.pictureType { return bytes }
        return NSBitmapImageRep(data: bytes)?.representation(using: .png, properties: [:])
    }

    // MARK: - Pasting

    override var readablePasteboardTypes: [NSPasteboard.PasteboardType] {
        [Self.noteType] + super.readablePasteboardTypes
    }

    override func readSelection(from pasteboard: NSPasteboard, type: NSPasteboard.PasteboardType) -> Bool {
        // Paste and Match Style: plain text in the style of what is around it, as AppKit does.
        if type == .string { return super.readSelection(from: pasteboard, type: type) }
        if let data = pasteboard.data(forType: Self.noteType), let text = pastedNote(data) {
            return insertPasted(text)
        }
        if let pictures = Self.pictureFiles(on: pasteboard) ?? Self.pictureOnly(on: pasteboard) {
            insertPictures(pictures)
            return true
        }
        if let rich = Self.richText(on: pasteboard) {
            return insertPasted(fittedToNote(rich.text, fromWeb: rich.isHTML))
        }
        return super.readSelection(from: pasteboard, type: type)
    }

    /// The richest text on the pasteboard, as AppKit reads it.
    private static func richText(on pasteboard: NSPasteboard) -> (text: NSAttributedString, isHTML: Bool)? {
        if let data = pasteboard.data(forType: .rtfd), let text = NSAttributedString(rtfd: data, documentAttributes: nil) { return (text, false) }
        if let data = pasteboard.data(forType: .rtf), let text = NSAttributedString(rtf: data, documentAttributes: nil) { return (text, false) }
        guard let data = pasteboard.data(forType: .html) else { return nil }
        // HTML that does not name its encoding was read as Latin-1, which turned Arabic (and
        // any other non-Latin text) into "Ù…Ø±Ø­Ø¨Ø§". What apps put on the pasteboard is UTF-8.
        let head = String(decoding: data.prefix(1024), as: UTF8.self).lowercased()
        var options: [NSAttributedString.DocumentReadingOptionKey: Any] = [.documentType: NSAttributedString.DocumentType.html]
        if !head.contains("charset") { options[.characterEncoding] = String.Encoding.utf8.rawValue }
        return (try? NSAttributedString(data: data, options: options, documentAttributes: nil)).map { ($0, true) }
    }

    /// A piece of a note copied from this app, with pictures given IDs of their own: the copy
    /// is a picture of its own, and deleting one note must not take the other's with it.
    private func pastedNote(_ data: Data) -> NSAttributedString? {
        guard let payload = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              let bytes = payload["note"] as? Data,
              var note = try? StoredNote(serializedBytes: bytes)
        else { return nil }
        var pictures: [Data: Data] = [:]
        for entry in payload["pictures"] as? [[String: Data]] ?? [] {
            if let id = entry["id"], let bytes = entry["bytes"] { pictures[id] = bytes }
        }
        joinCaretParagraph(&note)
        let text = NSMutableAttributedString(attributedString: NoteFormat.decode(note) { pictures[$0] })
        text.enumerateAttribute(.attachment, in: NSRange(location: 0, length: text.length)) { value, run, _ in
            guard let picture = value as? PictureAttachment, let bytes = picture.contents,
                  let copy = PictureAttachment(data: bytes, type: picture.pictureType)
            else { return }
            text.addAttribute(.attachment, value: copy, range: run)
        }
        return text
    }

    /// Rich text from another app, made note text: what the note format records survives, and
    /// the rest (font, size, colour, background, spacing, a forced writing direction) gives
    /// way to the note's own.
    ///
    /// A web page's pictures are left out: they are only addresses, which the app cannot fetch
    /// (it has no network), and what came in their place was a broken-picture icon. A picture
    /// copied on its own arrives through `pictureOnly`, as the browser copies its bytes too.
    private func fittedToNote(_ rich: NSAttributedString, fromWeb: Bool) -> NSAttributedString {
        let text = NSMutableAttributedString(attributedString: rich)
        Self.fitPictures(in: text, keepingPictures: !fromWeb)
        Self.removeTypedListMarkers(in: text)
        let encoded = NoteFormat.encode(text)
        var note = encoded.note
        for index in note.paragraphs.indices {
            // Natural, so each paragraph runs the way its own first letter does: HTML marked
            // Arabic as left to right.
            note.paragraphs[index].alignment = .natural
            note.paragraphs[index].direction = .natural
            note.paragraphs[index].indent = 0
            note.paragraphs[index].lineSpacing = 0
            for run in note.paragraphs[index].runs.indices {
                note.paragraphs[index].runs[run].relativeSize = 0
                note.paragraphs[index].runs[run].clearColor()
            }
        }
        joinCaretParagraph(&note)
        let pictures = Dictionary(encoded.pictures.map { ($0.id, $0.bytes) }, uniquingKeysWith: { first, _ in first })
        return NoteFormat.decode(note) { pictures[$0] }
    }

    /// The first pasted paragraph runs on in the paragraph the caret is in, so it takes that
    /// paragraph's style: a word copied from a heading and pasted into body text is body text.
    /// Into an empty line, what was copied keeps its own style.
    private func joinCaretParagraph(_ note: inout StoredNote) {
        guard !note.paragraphs.isEmpty, let storage = textStorage else { return }
        let range = rangeForUserTextChange
        guard range.location != NSNotFound else { return }
        let text = storage.string as NSString
        let paragraph = text.paragraphRange(for: NSRange(location: range.location, length: 0))
        let before = text.substring(with: NSRange(location: paragraph.location, length: range.location - paragraph.location))
        let afterEnd = NSMaxRange(paragraph)
        let after = text.substring(with: NSRange(location: NSMaxRange(range), length: max(0, afterEnd - NSMaxRange(range))))
        guard !(before + after).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let attributes = paragraph.length > 0 && paragraph.location < storage.length
            ? storage.attributes(at: paragraph.location, effectiveRange: nil) : typingAttributes
        let role = (attributes[Self.roleKey] as? String).flatMap(ParagraphRole.init(rawValue:)) ?? .body
        var first = note.paragraphs[0]
        first.role = switch role {
        case .title: .title
        case .heading: .heading
        case .subheading: .subheading
        case .quote: .quote
        case .codeBlock: .codeBlock
        case .checklist: .checklist
        case .divider, .body: .body
        }
        first.lists = []
        first.checked = false
        note.paragraphs[0] = first
    }

    /// Other apps write a list item's marker into its text ("\t•\tMilk") beside the list
    /// itself; the note draws its own, so the written one goes.
    private static func removeTypedListMarkers(in text: NSMutableAttributedString) {
        let string = text.string as NSString
        var starts: [Int] = []
        string.enumerateSubstrings(in: NSRange(location: 0, length: string.length), options: [.byParagraphs, .substringNotRequired]) { _, range, _, _ in
            starts.append(range.location)
        }
        for start in starts.reversed() where start < text.length {
            guard let style = text.attribute(.paragraphStyle, at: start, effectiveRange: nil) as? NSParagraphStyle,
                  !style.textLists.isEmpty
            else { continue }
            let rest = (text.string as NSString).substring(from: start)
            guard let marker = rest.range(of: "^\\t?[^\\t\\n]{1,8}\\t", options: .regularExpression) else { continue }
            text.deleteCharacters(in: NSRange(location: start, length: (rest[marker] as Substring).utf16.count))
        }
    }

    /// Puts [text] where the selection is, as one step that undo takes back.
    private func insertPasted(_ text: NSAttributedString) -> Bool {
        let range = rangeForUserTextChange
        guard range.location != NSNotFound, let storage = textStorage,
              shouldChangeText(in: range, replacementString: text.string)
        else { return false }
        storage.replaceCharacters(in: range, with: text)
        didChangeText()
        setSelectedRange(NSRange(location: range.location + text.length, length: 0))
        return true
    }
}
