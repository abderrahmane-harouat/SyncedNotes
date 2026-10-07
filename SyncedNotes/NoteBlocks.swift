import AppKit

/// Decides how each paragraph is drawn, so block paragraphs can draw more than their text:
/// a bar beside a quote, a panel behind code, a list item's marker.
///
/// Blocks are drawn by TextKit 2 layout fragments rather than by extra characters or views in
/// the text, so the text stays exactly what the user typed, and selection, undo and copying
/// behave as for any other paragraph.
final class BlockLayout: NSObject, NSTextLayoutManagerDelegate {

    func textLayoutManager(
        _ textLayoutManager: NSTextLayoutManager,
        textLayoutFragmentFor location: any NSTextLocation,
        in textElement: NSTextElement
    ) -> NSTextLayoutFragment {
        let range = textElement.elementRange
        switch role(of: textElement) {
        case .quote: return QuoteFragment(textElement: textElement, range: range)
        case .codeBlock: return CodeBlockFragment(textElement: textElement, range: range)
        case .divider: return DividerFragment(textElement: textElement, range: range)
        case .checklist: return ChecklistFragment(textElement: textElement, range: range)
        default: return NoteFragment(textElement: textElement, range: range)
        }
    }

    private func role(of element: NSTextElement) -> NoteTextView.ParagraphRole? {
        guard let text = (element as? NSTextParagraph)?.attributedString, text.length > 0,
              let raw = text.attribute(NoteTextView.roleKey, at: 0, effectiveRange: nil) as? String
        else { return nil }
        return NoteTextView.ParagraphRole(rawValue: raw)
    }
}

/// A paragraph that draws something outside its own text, across the full width of the note.
///
/// `nonisolated`, like the class it extends: TextKit may lay out and draw fragments away from
/// the main thread. These only draw, and touch nothing else in the app.
nonisolated class BlockFragment: NoteFragment {

    /// The note's full text width, in the fragment's own coordinates.
    ///
    /// A fragment's frame starts where its text starts, after the paragraph's indent, so the
    /// note's left edge is at minus that offset. The container's line padding is kept on both
    /// sides, so a block lines up with the left edge of ordinary text.
    var blockRect: CGRect {
        let container = textLayoutManager?.textContainer
        let padding = container?.lineFragmentPadding ?? 0
        let width = (container?.size.width ?? layoutFragmentFrame.width) - padding * 2
        return CGRect(x: padding - layoutFragmentFrame.minX, y: 0, width: width, height: layoutFragmentFrame.height)
    }

    // TextKit only redraws, and only lets a fragment paint, inside these bounds. By default
    // they hug the glyphs, which would clip a bar in the margin or a panel past the text's end.
    override var renderingSurfaceBounds: CGRect {
        super.renderingSurfaceBounds.union(blockRect)
    }
}

nonisolated final class QuoteFragment: BlockFragment {
    override func draw(at point: CGPoint, in context: CGContext) {
        context.saveGState()
        context.setFillColor(NSColor.tertiaryLabelColor.cgColor)
        context.fill(CGRect(x: point.x + blockRect.minX, y: point.y, width: 3, height: blockRect.height))
        context.restoreGState()
        super.draw(at: point, in: context)
    }
}

nonisolated final class CodeBlockFragment: BlockFragment {
    override func draw(at point: CGPoint, in context: CGContext) {
        context.saveGState()
        context.setFillColor(NSColor.textColor.withAlphaComponent(0.06).cgColor)
        context.fill(blockRect.offsetBy(dx: point.x, dy: point.y))
        context.restoreGState()
        super.draw(at: point, in: context)
    }
}

/// A horizontal line across the note.
///
/// A paragraph with the role `divider` holding one attachment character. The attachment only
/// holds the line's height and keeps the divider atomic, so it is selected and deleted whole
/// and nothing can be typed inside it; `DividerFragment` draws the line. The role is also what
/// identifies a divider for saving and copying later.
///
/// Drawing the line from the attachment itself did not work on TextKit 2: an attachment that
/// overrides `image(for:…)` was never asked for its image, and a view provider registered for
/// a custom file type was never used, since the attachment reports no file type for a type
/// macOS does not know.
enum NoteDivider {
    static func make(attributes: [NSAttributedString.Key: Any]) -> NSAttributedString {
        let attachment = NSTextAttachment()
        // A blank image: without one TextKit draws a generic document icon in its place.
        attachment.image = NSImage(size: NSSize(width: 1, height: 16))
        attachment.bounds = CGRect(x: 0, y: 0, width: 1, height: 16)
        let text = NSMutableAttributedString(attachment: attachment)
        text.addAttributes(attributes, range: NSRange(location: 0, length: text.length))
        text.addAttribute(NoteTextView.roleKey, value: NoteTextView.ParagraphRole.divider.rawValue, range: NSRange(location: 0, length: text.length))
        return text
    }
}

nonisolated final class DividerFragment: BlockFragment {
    override func draw(at point: CGPoint, in context: CGContext) {
        super.draw(at: point, in: context)
        let line = blockRect.offsetBy(dx: point.x, dy: point.y)
        context.saveGState()
        context.setFillColor(NSColor.separatorColor.cgColor)
        context.fill(CGRect(x: line.minX, y: line.midY - 0.5, width: line.width, height: 1))
        context.restoreGState()
    }
}

/// A checklist item: a circle beside the paragraph, filled with a tick once checked, and the
/// text dimmed. Clicking the circle is handled by `NoteTextView.mouseDown`, which uses
/// `checkboxRect` so that what is drawn and what can be clicked are the same place.
nonisolated final class ChecklistFragment: BlockFragment {

    static let boxSize: CGFloat = 16

    /// Whether this item is checked: recorded on the paragraph's text as `checkedKey`.
    var isChecked: Bool {
        guard let text = (textElement as? NSTextParagraph)?.attributedString, text.length > 0 else { return false }
        return text.attribute(NoteTextView.checkedKey, at: 0, effectiveRange: nil) as? Bool == true
    }

    /// How far the item is indented beyond a plain checklist item, so its circle moves in
    /// with its text rather than staying at the note's edge.
    private var extraIndent: CGFloat {
        guard let text = (textElement as? NSTextParagraph)?.attributedString, text.length > 0,
              let style = text.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle
        else { return 0 }
        return max(0, style.headIndent - NoteFormat.indent(of: .checklist))
    }

    /// The circle, in the fragment's own coordinates: in the margin, level with the first line.
    var checkboxRect: CGRect {
        let firstLine = textLineFragments.first?.typographicBounds ?? CGRect(x: 0, y: 0, width: 0, height: layoutFragmentFrame.height)
        return CGRect(x: blockRect.minX + extraIndent + 2, y: firstLine.midY - Self.boxSize / 2, width: Self.boxSize, height: Self.boxSize)
    }

    override func draw(at point: CGPoint, in context: CGContext) {
        let box = checkboxRect.offsetBy(dx: point.x, dy: point.y)
        context.saveGState()
        if isChecked {
            context.setFillColor(NSColor.controlAccentColor.cgColor)
            context.fillEllipse(in: box)
            // The tick, drawn in the fragment's flipped coordinates: y grows downwards.
            context.setStrokeColor(NSColor.white.cgColor)
            context.setLineWidth(1.8)
            context.setLineCap(.round)
            context.setLineJoin(.round)
            context.move(to: CGPoint(x: box.minX + box.width * 0.28, y: box.minY + box.height * 0.52))
            context.addLine(to: CGPoint(x: box.minX + box.width * 0.44, y: box.minY + box.height * 0.68))
            context.addLine(to: CGPoint(x: box.minX + box.width * 0.72, y: box.minY + box.height * 0.34))
            context.strokePath()
        } else {
            context.setStrokeColor(NSColor.secondaryLabelColor.cgColor)
            context.setLineWidth(1.2)
            context.strokeEllipse(in: box.insetBy(dx: 0.6, dy: 0.6))
        }
        context.restoreGState()

        context.saveGState()
        if isChecked { context.setAlpha(0.45) }
        super.draw(at: point, in: context)
        context.restoreGState()
    }

    // The circle sits in the margin, outside the text's own bounds.
    override var renderingSurfaceBounds: CGRect {
        super.renderingSurfaceBounds.union(checkboxRect)
    }
}

/// A picture in a note, shrunk to fit the width of the text and never enlarged.
///
/// The picture's own bytes are the attachment's contents, untouched, so nothing is lost to
/// re-encoding, and TextKit draws from them. Its size is read from a decoded copy kept here:
/// an attachment holds either an image or original bytes, never both (setting `image` quietly
/// replaces the bytes with a re-encoded copy), and without a size the picture is laid out at
/// zero and never seen.
nonisolated final class PictureAttachment: NSTextAttachment {

    private(set) var naturalSize = CGSize.zero

    /// The picture as TextKit draws it, decoded once and held for as long as the attachment.
    ///
    /// Left to TextKit, a picture given as bytes is decoded into an image TextKit keeps for
    /// itself, and two crashes (2026-10-07) were that image drawn after it had been freed: the
    /// attachment itself was still being drawn, the image it handed over was gone. When the
    /// attachment owns it, it cannot go while the attachment is drawn. Set only while the
    /// attachment is made, so reading it from TextKit's own threads is safe.
    private var drawingImage: NSImage?

    /// The picture's own ID: it is stored, and will travel, as a locked item of its own.
    private(set) var pictureID = Data((0..<16).map { _ in UInt8.random(in: .min ... .max) })

    /// The picture's file type, kept even when its bytes could not be loaded.
    private(set) var pictureType = ""

    /// Whether the picture's bytes are here. A picture whose file could not be read keeps its
    /// place, ID and size, so saving the note again does not quietly drop it.
    var isMissing: Bool { contents == nil }

    convenience init?(data: Data, type: String, id: Data? = nil) {
        guard let image = NSImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        self.init(data: data, ofType: type)
        naturalSize = image.size
        drawingImage = image
        pictureType = type
        if let id { pictureID = id }
    }

    /// A picture whose bytes could not be loaded: an empty space of its size, until they can.
    convenience init(missing id: Data, type: String, size: CGSize) {
        self.init(data: nil, ofType: nil)
        (pictureID, pictureType, naturalSize) = (id, type, size)
        // A blank image: without one TextKit draws a generic document icon in its place.
        image = NSImage(size: NSSize(width: 1, height: 1))
    }

    // What TextKit 2 asks for to draw the picture (checked: the other is not asked).
    override func image(for bounds: CGRect, attributes: [NSAttributedString.Key: Any], location: any NSTextLocation, textContainer: NSTextContainer?) -> NSImage? {
        drawingImage ?? super.image(for: bounds, attributes: attributes, location: location, textContainer: textContainer)
    }

    // The older way, which printing and rich text export still use.
    override func image(forBounds imageBounds: CGRect, textContainer: NSTextContainer?, characterIndex charIndex: Int) -> NSImage? {
        drawingImage ?? super.image(forBounds: imageBounds, textContainer: textContainer, characterIndex: charIndex)
    }

    override func attachmentBounds(
        for attributes: [NSAttributedString.Key: Any],
        location: any NSTextLocation,
        textContainer: NSTextContainer?,
        proposedLineFragment: CGRect,
        position: CGPoint
    ) -> CGRect {
        guard naturalSize.width > 0 else { return .zero }
        // A hair narrower than the line, or TextKit wraps it onto a line of its own.
        let room = max(proposedLineFragment.width - position.x - 1, 1)
        let scale = min(1, room / naturalSize.width)
        return CGRect(x: 0, y: 0, width: naturalSize.width * scale, height: naturalSize.height * scale)
    }
}
