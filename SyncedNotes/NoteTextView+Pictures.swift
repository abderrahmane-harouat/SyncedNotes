import AppKit
import UniformTypeIdentifiers

/// Pictures come into a note by paste and by drag and drop, which AppKit routes through the
/// same method for both (see NoteTextView+Pasteboard). There is no "Insert Picture…" file
/// dialog: the sandbox gives the app no file access of its own, and drag and paste need none.
extension NoteTextView {

    func insertPictures(_ pictures: [PictureAttachment]) {
        let text = NSMutableAttributedString()
        for picture in pictures { text.append(NSAttributedString(attachment: picture)) }
        var attributes = typingAttributes
        attributes[.attachment] = nil
        text.addAttributes(attributes, range: NSRange(location: 0, length: text.length))
        insertText(text, replacementRange: selectedRange())
        undoManager?.setActionName(pictures.count == 1 ? "Add Picture" : "Add Pictures")
    }

    /// Picture files dragged in from Finder: the files' own bytes, read through the access the
    /// drag grants. Nil unless every file is a picture.
    static func pictureFiles(on pasteboard: NSPasteboard) -> [PictureAttachment]? {
        let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        guard let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: options) as? [URL], !urls.isEmpty else { return nil }
        let pictures = urls.compactMap { url -> PictureAttachment? in
            guard let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .image),
                  let data = try? Data(contentsOf: url)
            else { return nil }
            return PictureAttachment(data: data, type: type.identifier)
        }
        return pictures.count == urls.count ? pictures : nil
    }

    /// A picture on its own, such as a screenshot or a picture copied from a web page. Nil
    /// when there are words on the pasteboard too: then the text is what is being pasted.
    ///
    /// Words, not any text: a browser's Copy Image puts the picture's web address beside it,
    /// as an `<img>` tag or as the address itself, and reading that instead pasted nothing,
    /// since the app cannot fetch anything from the web.
    static func pictureOnly(on pasteboard: NSPasteboard) -> [PictureAttachment]? {
        guard !hasWords(pasteboard) else { return nil }
        // Kept in the format it came in, except TIFF, which is uncompressed and many times the
        // size of the same picture as PNG.
        for type in [UTType.png, .jpeg, .heic, .gif] {
            if let data = pasteboard.data(forType: NSPasteboard.PasteboardType(type.identifier)),
               let picture = PictureAttachment(data: data, type: type.identifier) {
                return [picture]
            }
        }
        if let tiff = pasteboard.data(forType: .tiff),
           let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]),
           let picture = PictureAttachment(data: png, type: UTType.png.identifier) {
            return [picture]
        }
        return nil
    }

    /// Whether the pasteboard's plain text has anything in it besides a lone web address.
    private static func hasWords(_ pasteboard: NSPasteboard) -> Bool {
        let text = (pasteboard.string(forType: .string) ?? "")
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{FFFC}")))
        guard !text.isEmpty else { return false }
        let isAddress = !text.contains(where: \.isWhitespace) && URL(string: text)?.scheme.map { ["http", "https", "file"].contains($0) } == true
        return !isAddress
    }

    /// Turns the plain image attachments rich text brings (a note copied from Apple Notes, say)
    /// into fitted pictures, and drops any other attachment: a file icon or a picture that
    /// stayed on the web is nothing the note can keep (`NoteFormat` saves pictures only).
    /// Without [keepingPictures], pictures go too.
    static func fitPictures(in text: NSMutableAttributedString, keepingPictures: Bool = true) {
        var changes: [(NSRange, PictureAttachment?)] = []
        text.enumerateAttribute(.attachment, in: NSRange(location: 0, length: text.length)) { value, run, _ in
            guard let attachment = value as? NSTextAttachment, !(attachment is PictureAttachment) else { return }
            guard keepingPictures, let data = attachment.contents ?? attachment.fileWrapper?.regularFileContents,
                  let type = attachment.fileType.flatMap(UTType.init)
                    ?? (attachment.fileWrapper?.preferredFilename as NSString?).flatMap({ UTType(filenameExtension: $0.pathExtension) }),
                  type.conforms(to: .image),
                  let picture = PictureAttachment(data: data, type: type.identifier)
            else { return changes.append((run, nil)) }
            changes.append((run, picture))
        }
        // From the end, so removing a character does not move the ranges still to change.
        for (run, picture) in changes.reversed() {
            if let picture {
                text.addAttribute(.attachment, value: picture, range: run)
            } else {
                text.deleteCharacters(in: run)
            }
        }
    }

    /// The picture that is the whole selection, if it is one: what Copy also offers as an
    /// image, for apps that take pictures but not rich text.
    var selectedPicture: PictureAttachment? {
        guard selectedRanges.count == 1, let storage = textStorage else { return nil }
        let range = selectedRange()
        guard range.length > 0 else { return nil }
        var found: [PictureAttachment] = []
        var other = false
        storage.enumerateAttribute(.attachment, in: range) { value, run, _ in
            if let picture = value as? PictureAttachment, !picture.isMissing {
                found.append(picture)
            } else if !(storage.string as NSString).substring(with: run).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                other = true
            }
        }
        return found.count == 1 && !other ? found[0] : nil
    }

    /// The picture under [point], in the view's coordinates, as a range of the text.
    ///
    /// A click on a picture only put the caret beside it, so a picture could be selected, and
    /// copied, only by dragging across it.
    func pictureRange(at point: NSPoint) -> NSRange? {
        guard let storage = textStorage, let window else { return nil }
        let index = characterIndexForInsertion(at: point)
        for candidate in [index, index - 1] where candidate >= 0 && candidate < storage.length {
            guard storage.attribute(.attachment, at: candidate, effectiveRange: nil) is PictureAttachment else { continue }
            let range = NSRange(location: candidate, length: 1)
            let onScreen = firstRect(forCharacterRange: range, actualRange: nil)
            let frame = convert(window.convertFromScreen(onScreen), from: nil)
            if frame.contains(point) { return range }
        }
        return nil
    }
}
