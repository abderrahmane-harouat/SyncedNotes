import AppKit
import Observation

/// What the selection looks like, so the toolbar can show pressed buttons and the menus can
/// show checkmarks for the styles that are on.
///
/// A style counts as on only when all of the selection has it, the same rule the commands use
/// to decide between switching it on and off, so a pressed button always means "clicking this
/// takes the style away". With no selection it describes what is typed next.
struct Formatting: Equatable {
    var bold = false
    var italic = false
    var underline = false
    var strikethrough = false
    /// The selection's highlight colour, when all of it has the same one.
    var highlight: HighlightColor?
    /// The selection's text colour, when all of it has the same preset.
    var textColor: TextColor?
    /// Whether all of the selection has the normal text colour.
    var plainColor = true
    var code = false
    /// Nil when the selection spans paragraphs of different styles.
    var role: NoteTextView.ParagraphRole? = .body
    var list: ListKind?
    var checked = false
    var alignment: NSTextAlignment?
    var direction: NSWritingDirection?

    /// Whether [command] is on, or nil for commands that are actions rather than states.
    func isOn(_ command: FormatCommand) -> Bool? {
        switch command {
        case .bold: bold
        case .italic: italic
        case .underline: underline
        case .strikethrough: strikethrough
        case .highlight: highlight != nil
        case .highlightColor(let color): highlight == color
        case .color(let color): textColor == color
        case .automaticColor: plainColor
        case .inlineCode: code
        case .title: role == .title
        case .heading: role == .heading
        case .subheading: role == .subheading
        case .body: role == .body
        case .blockQuote: role == .quote
        case .codeBlock: role == .codeBlock
        case .checklist: role == .checklist
        case .markChecked: checked
        case .bulletedList: list == .bulleted
        case .dashedList: list == .dashed
        case .numberedList: list == .numbered
        case .alignLeft: alignment == .left
        case .alignCenter: alignment == .center
        case .alignRight: alignment == .right
        case .justify: alignment == .justified
        case .directionNatural: direction == .natural
        case .directionLeftToRight: direction == .leftToRight
        case .directionRightToLeft: direction == .rightToLeft
        default: nil
        }
    }
}

/// The three kinds of list, whatever marker a nested level happens to use.
enum ListKind {
    case bulleted, dashed, numbered

    init?(_ format: NSTextList.MarkerFormat) {
        switch format {
        case .disc, .circle, .square: self = .bulleted
        case .hyphen: self = .dashed
        default:
            guard format.rawValue.hasPrefix("{") else { return nil }
            self = .numbered
        }
    }
}

/// The current window's `Formatting`, for SwiftUI to read. Only the text view writes it.
@Observable
final class FormattingModel {
    var current = Formatting()
}

extension NoteTextView {

    /// The formatting at the selection now. See `Formatting`.
    var currentFormatting: Formatting {
        var formatting = Formatting()
        formatting.bold = everywhere { has(.boldFontMask, font(in: $0)) }
        formatting.italic = everywhere { has(.italicFontMask, font(in: $0)) }
        formatting.underline = everywhere { isSet($0[.underlineStyle]) }
        formatting.strikethrough = everywhere { isSet($0[.strikethroughStyle]) }
        formatting.highlight = HighlightColor.allCases.first { color in
            everywhere { $0[Self.highlightKey] as? String == color.rawValue }
        }
        formatting.code = everywhere { isCode(font(in: $0)) }
        formatting.plainColor = everywhere { ($0[.foregroundColor] as? NSColor).map(TextColor.followsAppearance) ?? true }
        formatting.textColor = TextColor.allCases.first { color in
            everywhere { ($0[.foregroundColor] as? NSColor).flatMap(TextColor.init) == color }
        }
        formatting.role = commonParagraphRole
        formatting.list = [ListKind.bulleted, .dashed, .numbered].first { kind in
            everyParagraph { style in style.textLists.last.flatMap { ListKind($0.markerFormat) } == kind }
        }
        formatting.checked = formatting.role == .checklist
            && everyCharacter(in: paragraphRanges, hasValue: true, for: Self.checkedKey)
        let style = firstParagraphStyle
        formatting.direction = style.baseWritingDirection
        // "Natural" alignment follows the writing direction; shown as the side it lands on.
        formatting.alignment = style.alignment == .natural
            ? (style.baseWritingDirection == .rightToLeft ? .right : .left)
            : style.alignment
        return formatting
    }

    /// The paragraph style all selected paragraphs share: body when they have none, nil when
    /// they differ.
    private var commonParagraphRole: ParagraphRole? {
        let ranges = paragraphRanges
        guard let storage = textStorage, !ranges.isEmpty else {
            return (typingAttributes[Self.roleKey] as? String).flatMap(ParagraphRole.init(rawValue:)) ?? .body
        }
        var roles = Set<String>()
        for range in ranges {
            storage.enumerateAttribute(Self.roleKey, in: range) { value, run, _ in
                // A divider's line break carries no role; the divider is its attachment.
                if value == nil, (string as NSString).substring(with: run) == "\n",
                   run.location > 0, storage.attribute(Self.roleKey, at: run.location - 1, effectiveRange: nil) as? String == ParagraphRole.divider.rawValue {
                    return
                }
                roles.insert(value as? String ?? ParagraphRole.body.rawValue)
            }
        }
        guard roles.count == 1, let only = roles.first else { return nil }
        return ParagraphRole(rawValue: only)
    }
}
