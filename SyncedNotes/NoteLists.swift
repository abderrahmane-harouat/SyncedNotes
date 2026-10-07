import AppKit

/// How list items are laid out and marked: the note draws its own markers rather than
/// TextKit's.
///
/// TextKit 2 lays out a list paragraph by itself: it replaces the paragraph's indents with its
/// own fixed tab stops (the marker 11 points in, the text at 36, at every font size) and draws
/// the marker as a small glyph in the text's font, whatever the paragraph asks for. That left
/// a wide gap after "1.", pin-prick bullets, a hyphen for a dash, and a nested bullet (◦)
/// hard to tell from a top-level one (•). None of it can be adjusted through the paragraph
/// style, so here list paragraphs reach layout without their lists, indented by the app, and
/// the marker is drawn beside the text by `NoteFragment`.
///
/// Only what is drawn changes. The text keeps its `NSTextList`s, so saving, copying, undo, the
/// menus' checkmarks and numbering are exactly as before, and the text itself is untouched:
/// the paragraph handed to layout has the same characters, so the caret and selection map
/// one to one.
final class ListLayout: NSObject, NSTextContentStorageDelegate {

    /// How far each level of a list moves its text in. The marker sits in that space.
    nonisolated static let levelIndent: CGFloat = 24

    func textContentStorage(_ textContentStorage: NSTextContentStorage, textParagraphWith range: NSRange) -> NSTextParagraph? {
        guard let text = textContentStorage.textStorage, range.length > 0,
              let style = text.attribute(.paragraphStyle, at: range.location, effectiveRange: nil) as? NSParagraphStyle,
              !style.textLists.isEmpty, !Self.isRightToLeft(style, (text.string as NSString).substring(with: range))
        else { return nil }
        let laidOut = style.mutableCopy() as! NSMutableParagraphStyle
        laidOut.textLists = []
        // On top of the paragraph's own indent, so Increase Indent and a quote's inset still apply.
        let indent = style.headIndent + CGFloat(style.textLists.count) * Self.levelIndent
        (laidOut.headIndent, laidOut.firstLineHeadIndent) = (indent, indent)
        let paragraph = NSMutableAttributedString(attributedString: text.attributedSubstring(from: range))
        paragraph.addAttribute(.paragraphStyle, value: laidOut, range: NSRange(location: 0, length: paragraph.length))
        return NSTextParagraph(attributedString: paragraph)
    }

    /// A right-to-left item keeps TextKit's own layout, which puts the marker on the right;
    /// the markers drawn here are only placed on the left.
    static func isRightToLeft(_ style: NSParagraphStyle, _ text: String) -> Bool {
        switch style.baseWritingDirection {
        case .rightToLeft: return true
        case .leftToRight: return false
        default:
            // Natural: the direction of the first letter, as the text system decides it.
            for scalar in text.unicodeScalars where scalar.properties.isAlphabetic {
                return (0x0590...0x08FF).contains(scalar.value) || (0xFB1D...0xFDFF).contains(scalar.value)
                    || (0xFE70...0xFEFF).contains(scalar.value)
            }
            return false
        }
    }

    /// The marker of the list item at [location], or nil if the paragraph there is not one
    /// whose marker the note draws.
    static func marker(at location: Int, in text: NSAttributedString) -> ListMarker? {
        guard location < text.length,
              let style = text.attribute(.paragraphStyle, at: location, effectiveRange: nil) as? NSParagraphStyle,
              let list = style.textLists.last
        else { return nil }
        let paragraph = (text.string as NSString).paragraphRange(for: NSRange(location: location, length: 0))
        guard !isRightToLeft(style, (text.string as NSString).substring(with: paragraph)) else { return nil }
        switch list.markerFormat {
        case .disc: return .disc
        case .circle: return .circle
        case .square: return .square
        // An en dash: a hyphen is a joining mark, too short and too thin to lead a line.
        case .hyphen: return .text("–")
        default:
            // Counted from 1 whatever the list's start, which is added here.
            let number = text.itemNumber(in: list, at: location) + list.startingItemNumber - 1
            return .text(list.marker(forItemNumber: number))
        }
    }
}

/// What leads a list item.
///
/// Bullets are drawn as shapes, not glyphs: the font's • is under three points across at the
/// body size, and its ◦ can hardly be told from it.
nonisolated enum ListMarker: Equatable, Sendable {
    case disc, circle, square
    case text(String)
}

/// A paragraph's layout fragment, drawing its list marker, if it has one, in front of its text.
///
/// `nonisolated`, like the class it extends: TextKit may lay out and draw fragments away from
/// the main thread.
nonisolated class NoteFragment: NSTextLayoutFragment {

    /// The marker, read from the note's text each time it is drawn, so an item's number is
    /// always its number now. The paragraph laid out here has had its lists taken off by
    /// `ListLayout`, so it cannot say.
    ///
    /// The note's text may only be read on the main thread, where NSTextView draws; anywhere
    /// else no marker is drawn, rather than reading the text while it may be changing.
    var listMarker: ListMarker? {
        guard Thread.isMainThread, let start = textElement?.elementRange?.location else { return nil }
        // Handed over to the main actor, which this thread is: checked just above.
        nonisolated(unsafe) let (layout, location) = (textLayoutManager, start)
        return MainActor.assumeIsolated {
            guard let content = layout?.textContentManager as? NSTextContentStorage, let text = content.textStorage else { return nil }
            return ListLayout.marker(at: content.offset(from: content.documentRange.location, to: location), in: text)
        }
    }

    /// Where the marker goes: numbers end this far before the text, bullets are centred this
    /// far before it. Together with `ListLayout.levelIndent` this is the whole list layout.
    static let numberGap: CGFloat = 6
    static let bulletCentre: CGFloat = 12

    private var markerFont: NSFont {
        let text = (textElement as? NSTextParagraph)?.attributedString
        let font = text.flatMap { $0.length > 0 ? $0.attribute(.font, at: 0, effectiveRange: nil) as? NSFont : nil }
        // The item's size, never its weight or slant: a bold first word should not make a bold
        // number. Figures of one width, so 9. and 10. line up on their full stops.
        return .monospacedDigitSystemFont(ofSize: font?.pointSize ?? NoteTextView.bodySize, weight: .regular)
    }

    private var markerColor: NSColor {
        let text = (textElement as? NSTextParagraph)?.attributedString
        return text.flatMap { $0.length > 0 ? $0.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor : nil } ?? .textColor
    }

    /// The first line's baseline, at the start of its text, in the fragment's own coordinates.
    private var firstBaseline: CGPoint? {
        guard let first = textLineFragments.first else { return nil }
        return CGPoint(x: first.typographicBounds.minX, y: first.typographicBounds.minY + first.glyphOrigin.y)
    }

    override func draw(at point: CGPoint, in context: CGContext) {
        if let marker = listMarker, let baseline = firstBaseline {
            drawMarker(marker, baseline: CGPoint(x: point.x + baseline.x, y: point.y + baseline.y), in: context)
        }
        super.draw(at: point, in: context)
    }

    private func drawMarker(_ marker: ListMarker, baseline: CGPoint, in context: CGContext) {
        let font = markerFont
        context.saveGState()
        defer { context.restoreGState() }
        context.setFillColor(markerColor.cgColor)
        context.setStrokeColor(markerColor.cgColor)
        // Bullets sit on the middle of the lowercase letters, sized from them, so they scale
        // with a heading's text as well as the body's.
        let size = (font.xHeight * 0.72).rounded()
        let dot = CGRect(x: baseline.x - Self.bulletCentre - size / 2, y: baseline.y - font.xHeight / 2 - size / 2, width: size, height: size)
        switch marker {
        case .disc:
            context.fillEllipse(in: dot)
        case .circle:
            context.setLineWidth(1.1)
            context.strokeEllipse(in: dot.insetBy(dx: 0.55, dy: 0.55))
        case .square:
            context.fill(dot.insetBy(dx: 0.4, dy: 0.4))
        case .text(let string):
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: string, attributes: [.font: font, .foregroundColor: markerColor]))
            let width = CTLineGetTypographicBounds(line, nil, nil, nil)
            // Fragments draw with y growing downwards; Core Text draws text the other way up.
            context.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
            context.textPosition = CGPoint(x: baseline.x - Self.numberGap - width, y: baseline.y)
            CTLineDraw(line, context)
        }
    }

    // The marker is in the indent, outside the text's own bounds, where TextKit would clip it.
    override var renderingSurfaceBounds: CGRect {
        guard listMarker != nil, let first = textLineFragments.first else { return super.renderingSurfaceBounds }
        let reach = ListLayout.levelIndent * 2
        return super.renderingSurfaceBounds.union(CGRect(x: first.typographicBounds.minX - reach, y: first.typographicBounds.minY, width: reach, height: first.typographicBounds.height))
    }
}

extension NoteTextView {

    /// Whether an edit can change which number an item has: one that adds or removes a line,
    /// or changes formatting, which is how items join and leave lists. Typing within an item
    /// cannot, and redraws nothing extra.
    static func mayRenumber(_ ranges: [NSValue], _ replacements: [String]?, in text: NSString) -> Bool {
        guard let replacements else { return true }
        return replacements.contains { $0.contains(where: \.isNewline) }
            || ranges.contains { text.rangeOfCharacter(from: .newlines, range: $0.rangeValue).location != NSNotFound }
    }

    /// Redraws the list markers after an edit, when there is a list at or below it.
    ///
    /// Numbers are drawn, not stored, and TextKit redraws only the paragraphs an edit touched:
    /// taking the first item out of a list redrew it and the item after, and left the third
    /// still showing 3. Asking TextKit to lay the note out again does not help while it is
    /// handling the edit (it lays the same fragments out and keeps what they drew). What does,
    /// found by trying, is marking the views that show the paragraphs, which TextKit keeps
    /// inside the text view, as needing to be drawn: at the next display every one draws again
    /// and reads its number afresh. Only for edits that can renumber, and only what is on
    /// screen, since TextKit keeps views for nothing else.
    func redrawListMarkersBelowEdit() {
        guard let storage = textStorage, storage.length > 0 else { return }
        let caret = min(selectedRange().location, storage.length - 1)
        let from = (storage.string as NSString).paragraphRange(for: NSRange(location: caret, length: 0)).location
        var listBelow = false
        storage.enumerateAttribute(.paragraphStyle, in: NSRange(location: from, length: storage.length - from)) { value, _, stop in
            if (value as? NSParagraphStyle)?.textLists.isEmpty == false { (listBelow, stop.pointee) = (true, true) }
        }
        guard listBelow else { return }
        func redraw(_ view: NSView) {
            for subview in view.subviews {
                subview.needsDisplay = true
                redraw(subview)
            }
        }
        redraw(self)
    }
}
