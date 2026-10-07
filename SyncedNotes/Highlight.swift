import AppKit

/// The marker colours a highlight can have.
///
/// A highlight is recorded twice: as its colour's name under `highlightKey`, which is what the
/// menus check and what saving will keep, and as the background colour that actually draws it.
/// The name is the truth; the colour only follows from it.
enum HighlightColor: String, CaseIterable {
    case yellow, green, blue, pink, purple, orange

    var title: String { rawValue.capitalized }

    /// Translucent, so the text on top stays readable in light and dark mode.
    var color: NSColor {
        let base: NSColor = switch self {
        case .yellow: .systemYellow
        case .green: .systemGreen
        case .blue: .systemBlue
        case .pink: .systemPink
        case .purple: .systemPurple
        case .orange: .systemOrange
        }
        return base.withAlphaComponent(0.35)
    }

    /// A round swatch for menus. Not a template image, so menus show it in its own colour.
    var swatch: NSImage {
        let image = NSImage(size: NSSize(width: 12, height: 12), flipped: false) { rect in
            color.withAlphaComponent(1).setFill()
            NSBezierPath(ovalIn: rect.insetBy(dx: 0.5, dy: 0.5)).fill()
            return true
        }
        image.isTemplate = false
        return image
    }
}

extension NoteTextView {

    /// Where a highlight's colour name is recorded. See `HighlightColor`.
    nonisolated static let highlightKey = NSAttributedString.Key("SyncedNotesHighlight")

    /// The colour the highlighter button and ⇧⌘H use: the one picked last.
    static var lastHighlight = HighlightColor.yellow

    /// Highlights in the last colour used, or removes the highlight if all of the selection has one.
    @objc func toggleHighlight(_ sender: Any?) {
        if everywhere({ $0[Self.highlightKey] != nil }) {
            removeHighlight(sender)
        } else {
            highlight(in: Self.lastHighlight)
        }
    }

    /// Highlights in the colour named by [sender], or removes it if the selection is all that
    /// colour already. The colour travels as the sender because the responder chain carries no
    /// arguments.
    @objc func setHighlightColor(_ sender: Any?) {
        guard let name = sender as? String, let color = HighlightColor(rawValue: name) else { return }
        Self.lastHighlight = color
        if everywhere({ $0[Self.highlightKey] as? String == color.rawValue }) {
            removeHighlight(sender)
        } else {
            highlight(in: color)
        }
    }

    @objc func removeHighlight(_ sender: Any?) {
        format(
            named: "Remove Highlight",
            selection: { storage, range in
                storage.removeAttribute(Self.highlightKey, range: range)
                storage.removeAttribute(.backgroundColor, range: range)
            },
            typing: { attributes in
                attributes[Self.highlightKey] = nil
                attributes[.backgroundColor] = nil
            }
        )
    }

    private func highlight(in color: HighlightColor) {
        format(
            named: "Highlight",
            selection: { storage, range in
                storage.addAttribute(Self.highlightKey, value: color.rawValue, range: range)
                storage.addAttribute(.backgroundColor, value: color.color, range: range)
            },
            typing: { attributes in
                attributes[Self.highlightKey] = color.rawValue
                attributes[.backgroundColor] = color.color
            }
        )
    }
}
