import AppKit

/// A formatting command, sent down the responder chain to whichever text view has focus.
///
/// The toolbar and the menu only name the command; the text view carries it out. That keeps
/// SwiftUI away from the text, as `Docs/Architect.md` requires.
enum FormatCommand {
    case bold, italic, underline, strikethrough, highlight, inlineCode
    case textColor, bigger, smaller
    case color(TextColor), automaticColor
    case uppercase, lowercase, capitalize
    case clearFormatting
    case title, heading, subheading, body
    case alignLeft, alignCenter, alignRight, justify
    case directionNatural, directionLeftToRight, directionRightToLeft
    case increaseIndent, decreaseIndent
    case lineSpacing(Double)
    case bulletedList, dashedList, numberedList
    case blockQuote, codeBlock, divider
    case checklist, markChecked
    case highlightColor(HighlightColor), removeHighlight
    case insertTable, addRowAbove, addRowBelow, addColumnBefore, addColumnAfter
    case deleteTableRow, deleteTableColumn, convertTableToText, deleteTable

    var title: String {
        switch self {
        case .bold: "Bold"
        case .italic: "Italic"
        case .underline: "Underline"
        case .strikethrough: "Strikethrough"
        case .highlight: "Highlight"
        case .inlineCode: "Code"
        case .textColor: "Show Colors…"
        case .color(let color): color.title
        case .automaticColor: "Automatic"
        case .bigger: "Bigger"
        case .smaller: "Smaller"
        case .uppercase: "Make Upper Case"
        case .lowercase: "Make Lower Case"
        case .capitalize: "Capitalize"
        case .clearFormatting: "Clear Formatting"
        case .title: "Title"
        case .heading: "Heading"
        case .subheading: "Subheading"
        case .body: "Body"
        case .alignLeft: "Align Left"
        case .alignCenter: "Center"
        case .alignRight: "Align Right"
        case .justify: "Justify"
        case .directionNatural: "Natural"
        case .directionLeftToRight: "Left to Right"
        case .directionRightToLeft: "Right to Left"
        case .increaseIndent: "Increase Indent"
        case .decreaseIndent: "Decrease Indent"
        case .lineSpacing(let multiple): multiple == 1 ? "Single" : multiple == 2 ? "Double" : multiple.formatted()
        case .bulletedList: "Bulleted List"
        case .dashedList: "Dashed List"
        case .numberedList: "Numbered List"
        case .blockQuote: "Block Quote"
        case .codeBlock: "Code Block"
        case .divider: "Insert Divider"
        case .checklist: "Checklist"
        case .markChecked: "Mark as Checked"
        case .highlightColor(let color): color.title
        case .removeHighlight: "Remove Highlight"
        case .insertTable: "Table"
        case .addRowAbove: "Add Row Above"
        case .addRowBelow: "Add Row Below"
        case .addColumnBefore: "Add Column Before"
        case .addColumnAfter: "Add Column After"
        case .deleteTableRow: "Delete Row"
        case .deleteTableColumn: "Delete Column"
        case .convertTableToText: "Convert to Text"
        case .deleteTable: "Delete Table"
        }
    }

    /// The picture on the command's toolbar button.
    ///
    /// Menus show words, not these: Apple's guidance is to keep icons in menus to the few that
    /// add real meaning, and at menu size the list symbols (list.bullet, list.dash,
    /// list.number) could not be told apart. The list commands show `listMarker` instead.
    /// At toolbar size the two lists that have buttons there are clear, as in Notes.
    var symbol: String? {
        switch self {
        case .bulletedList: "list.bullet"
        case .numberedList: "list.number"
        case .bold: "bold"
        case .italic: "italic"
        case .underline: "underline"
        case .strikethrough: "strikethrough"
        case .highlight: "highlighter"
        case .checklist: "checklist"
        case .insertTable: "tablecells"
        default: nil
        }
    }

    /// The marker a list command gives, shown beside its name in menus: the very mark the
    /// note will draw, so the three lists are told apart by what they look like.
    var listMarker: ListMarker? {
        switch self {
        case .bulletedList: .disc
        case .dashedList: .text("–")
        case .numberedList: .text("1.")
        default: nil
        }
    }

    func send() {
        switch self {
        case .textColor:
            // The system colour panel. It sends `changeColor:` to the focused text view, which
            // AppKit already handles, undo included.
            NSApp.orderFrontColorPanel(nil)
        case .lineSpacing(let multiple):
            NSApp.sendAction(selector, to: nil, from: NSNumber(value: multiple))
        case .highlightColor(let color):
            NSApp.sendAction(selector, to: nil, from: color.rawValue as NSString)
        case .color(let color):
            NSApp.sendAction(selector, to: nil, from: color.rawValue as NSString)
        case .automaticColor:
            NSApp.sendAction(selector, to: nil, from: "automatic" as NSString)
        default:
            NSApp.sendAction(selector, to: nil, from: nil)
        }
    }

    private var selector: Selector {
        switch self {
        case .bold: #selector(NoteTextView.toggleBold(_:))
        case .italic: #selector(NoteTextView.toggleItalic(_:))
        case .underline: #selector(NoteTextView.toggleUnderline(_:))
        case .strikethrough: #selector(NoteTextView.toggleStrikethrough(_:))
        case .highlight: #selector(NoteTextView.toggleHighlight(_:))
        case .inlineCode: #selector(NoteTextView.toggleInlineCode(_:))
        case .bigger: #selector(NoteTextView.makeTextBigger(_:))
        case .smaller: #selector(NoteTextView.makeTextSmaller(_:))
        // AppKit's own: NSTextView already implements these, undo included.
        case .uppercase: #selector(NSResponder.uppercaseWord(_:))
        case .lowercase: #selector(NSResponder.lowercaseWord(_:))
        case .capitalize: #selector(NSResponder.capitalizeWord(_:))
        case .clearFormatting: #selector(NoteTextView.clearFormatting(_:))
        case .textColor: #selector(NSApplication.orderFrontColorPanel(_:))
        case .color, .automaticColor: #selector(NoteTextView.applyTextColor(_:))
        case .title: #selector(NoteTextView.makeTitle(_:))
        case .heading: #selector(NoteTextView.makeHeading(_:))
        case .subheading: #selector(NoteTextView.makeSubheading(_:))
        case .body: #selector(NoteTextView.makeBody(_:))
        // AppKit's own paragraph commands, undo included.
        case .alignLeft: #selector(NSTextView.alignLeft(_:))
        case .alignCenter: #selector(NSTextView.alignCenter(_:))
        case .alignRight: #selector(NSTextView.alignRight(_:))
        case .justify: #selector(NSTextView.alignJustified(_:))
        case .directionNatural: #selector(NSResponder.makeBaseWritingDirectionNatural(_:))
        case .directionLeftToRight: #selector(NSResponder.makeBaseWritingDirectionLeftToRight(_:))
        case .directionRightToLeft: #selector(NSResponder.makeBaseWritingDirectionRightToLeft(_:))
        case .increaseIndent: #selector(NoteTextView.increaseIndent(_:))
        case .decreaseIndent: #selector(NoteTextView.decreaseIndent(_:))
        case .lineSpacing: #selector(NoteTextView.setLineSpacing(_:) as (NoteTextView) -> (Any?) -> Void)
        case .bulletedList: #selector(NoteTextView.toggleBulletedList(_:))
        case .dashedList: #selector(NoteTextView.toggleDashedList(_:))
        case .numberedList: #selector(NoteTextView.toggleNumberedList(_:))
        case .blockQuote: #selector(NoteTextView.toggleBlockQuote(_:))
        case .codeBlock: #selector(NoteTextView.toggleCodeBlock(_:))
        case .divider: #selector(NoteTextView.insertDivider(_:))
        case .checklist: #selector(NoteTextView.toggleChecklist(_:))
        case .markChecked: #selector(NoteTextView.toggleChecked(_:))
        case .highlightColor: #selector(NoteTextView.setHighlightColor(_:))
        case .removeHighlight: #selector(NoteTextView.removeHighlight(_:))
        case .insertTable: #selector(NoteTextView.insertTable(_:))
        // Only a cell answers these, so they do nothing unless the caret is in a table.
        case .addRowAbove: #selector(TableCellTextView.addRowAbove(_:))
        case .addRowBelow: #selector(TableCellTextView.addRowBelow(_:))
        case .addColumnBefore: #selector(TableCellTextView.addColumnBefore(_:))
        case .addColumnAfter: #selector(TableCellTextView.addColumnAfter(_:))
        case .deleteTableRow: #selector(TableCellTextView.deleteTableRow(_:))
        case .deleteTableColumn: #selector(TableCellTextView.deleteTableColumn(_:))
        case .convertTableToText: #selector(TableCellTextView.convertTableToText(_:))
        case .deleteTable: #selector(TableCellTextView.deleteTable(_:))
        }
    }
}
