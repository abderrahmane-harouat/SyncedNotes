import AppKit

/// The colours text can be given from the toolbar and the Format menu, beside the normal
/// colour, which follows light and dark mode.
///
/// Fixed values, each chosen to read on both the light and the dark background: a note saves a
/// colour as its sRGB value (note.proto), so a colour picked for one mode would come back
/// unreadable in the other. Anything else comes from the system colour panel.
enum TextColor: String, CaseIterable {
    case red, orange, green, blue, purple, pink, gray

    var title: String { rawValue.capitalized }

    var color: NSColor {
        let (red, green, blue): (CGFloat, CGFloat, CGFloat) = switch self {
        case .red: (0.91, 0.26, 0.24)
        case .orange: (0.95, 0.55, 0.13)
        case .green: (0.22, 0.67, 0.35)
        case .blue: (0.20, 0.50, 0.96)
        case .purple: (0.64, 0.38, 0.90)
        case .pink: (0.93, 0.33, 0.58)
        case .gray: (0.56, 0.56, 0.60)
        }
        return NSColor(srgbRed: red, green: green, blue: blue, alpha: 1)
    }

    /// The preset [color] is. Near enough counts: a saved colour comes back from 32-bit floats.
    init?(_ color: NSColor) {
        guard let rgb = color.usingColorSpace(.sRGB) else { return nil }
        let found = Self.allCases.first { preset in
            let mine = preset.color
            return abs(mine.redComponent - rgb.redComponent) < 0.01
                && abs(mine.greenComponent - rgb.greenComponent) < 0.01
                && abs(mine.blueComponent - rgb.blueComponent) < 0.01
        }
        guard let found else { return nil }
        self = found
    }

    /// A round swatch for menus. Not a template image, so menus show it in its own colour.
    var swatch: NSImage {
        let image = NSImage(size: NSSize(width: 12, height: 12), flipped: false) { rect in
            color.setFill()
            NSBezierPath(ovalIn: rect.insetBy(dx: 0.5, dy: 0.5)).fill()
            return true
        }
        image.isTemplate = false
        return image
    }

    /// Whether [color] is one of the plain text colours, black, white or a grey close to them,
    /// which should follow light and dark mode rather than stay fixed.
    ///
    /// Text arrives with these fixed from other apps (black from a web page, white from an app
    /// in dark mode), and saved that way it became black on the dark background, or white on
    /// the light one, once the Mac switched. So they are never kept: pasted text, saving and
    /// opening a note all treat them as the normal colour, which also mends notes saved before.
    static func followsAppearance(_ color: NSColor) -> Bool {
        if color == NSColor.textColor || color == NSColor.labelColor || color == NSColor.controlTextColor { return true }
        guard let rgb = color.usingColorSpace(.sRGB) else { return false }
        let channels = [rgb.redComponent, rgb.greenComponent, rgb.blueComponent]
        let (darkest, lightest) = (channels.min()!, channels.max()!)
        let grey = lightest - darkest < 0.08
        return grey && (lightest < 0.25 || darkest > 0.85)
    }
}

extension NoteTextView {

    /// Colours text with the preset named by [sender], or the normal colour for any other
    /// name. The name travels as the sender because the responder chain carries no arguments.
    @objc func applyTextColor(_ sender: Any?) {
        applyTextColor((sender as? String).flatMap(TextColor.init(rawValue:))?.color)
    }

    /// From the system colour panel. AppKit's own handling would keep any colour as it is,
    /// black included, so it comes through the same rule as the menu.
    override func changeColor(_ sender: Any?) {
        guard let color = (sender as? NSColorPanel)?.color else { return super.changeColor(sender) }
        applyTextColor(color)
    }

    /// Colours the selection, or what is typed next, with [color]; nil is the normal colour.
    private func applyTextColor(_ color: NSColor?) {
        let chosen = color.flatMap { TextColor.followsAppearance($0) ? nil : $0 } ?? NSColor.textColor
        format(
            named: "Text Color",
            selection: { storage, range in storage.addAttribute(.foregroundColor, value: chosen, range: range) },
            typing: { $0[.foregroundColor] = chosen }
        )
    }
}
