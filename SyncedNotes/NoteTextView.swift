import AppKit

/// The text view the editor uses: `NSTextView` plus the formatting commands the app offers.
///
/// Commands arrive through the responder chain (`NSApp.sendAction`), not as calls from SwiftUI,
/// so a toolbar button or a menu item acts on whichever text view has focus, and SwiftUI never
/// holds a reference to the text.
class NoteTextView: NSTextView {

    /// A text view set up as the note editor.
    ///
    /// Everything that makes it the editor is here, not in the SwiftUI wrapper, so anything
    /// that exercises the editor outside the app gets the same one the app does.
    static func makeEditor() -> NoteTextView {
        // TextKit 2, asked for by name rather than left to the default. Nothing may ever read
        // `layoutManager`: that one call silently rebuilds the view on TextKit 1, for good.
        let editor = NoteTextView(usingTextLayoutManager: true)
        editor.textLayoutManager?.delegate = editor.blockLayout
        (editor.textLayoutManager?.textContentManager as? NSTextContentStorage)?.delegate = editor.listLayout
        editor.configureAsNoteText()
        return editor
    }

    /// What every text view in a note shares, the note itself and each table cell alike.
    func configureAsNoteText() {
        allowsUndo = true
        // Lets pictures be pasted and dropped in. See NoteTextView+Pictures.
        importsGraphics = true
        font = Self.bodyFont
        turnOffTextServices()
        reportChangesMadeByUndo()
    }

    /// Makes undo and redo count as edits, as typing does: saved, and drawn.
    ///
    /// On TextKit 2, NSTextView undoes and redoes by changing the text directly, without
    /// `didChangeText` (TextKit 1 does call it; tried side by side on macOS 26). So an undo
    /// was never saved unless something was typed after it, and a list renumbered by undo
    /// kept its old numbers on screen. Whatever an undo changes in this view's text is
    /// reported here once the undo is done, through `didChangeText`, like any other edit.
    private func reportChangesMadeByUndo() {
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(textChangedDuringUndo(_:)), name: NSTextStorage.didProcessEditingNotification, object: textStorage)
        center.addObserver(self, selector: #selector(undoFinished(_:)), name: .NSUndoManagerDidUndoChange, object: nil)
        center.addObserver(self, selector: #selector(undoFinished(_:)), name: .NSUndoManagerDidRedoChange, object: nil)
    }

    @objc private func textChangedDuringUndo(_ notification: Notification) {
        guard let undoManager, undoManager.isUndoing || undoManager.isRedoing else { return }
        changedByUndo = true
    }

    @objc private func undoFinished(_ notification: Notification) {
        guard changedByUndo else { return }
        changedByUndo = false
        editMayRenumber = true
        didChangeText()
    }

    private var changedByUndo = false

    /// Told what the selection looks like whenever that may have changed. See `Formatting`.
    var onFormattingChange: ((Formatting) -> Void)?

    private var formattingReportScheduled = false

    /// Reports the formatting once the current event is done. One keystroke changes the
    /// selection, the typing attributes and the text, and each would otherwise report.
    func formattingMayHaveChanged() {
        guard onFormattingChange != nil, !formattingReportScheduled else { return }
        formattingReportScheduled = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            formattingReportScheduled = false
            onFormattingChange?(currentFormatting)
        }
    }

    override func setSelectedRanges(_ ranges: [NSValue], affinity: NSSelectionAffinity, stillSelecting: Bool) {
        let before = selectedRange().location
        super.setSelectedRanges(ranges, affinity: affinity, stillSelecting: stillSelecting)
        formattingMayHaveChanged()
        guard !stillSelecting else { return }
        // Once AppKit has finished moving the caret: moving focus into a table from inside this
        // call would pull the first responder out from under AppKit's own selection handling.
        let forward = selectedRange().location >= before
        Task { @MainActor [weak self] in self?.enterTableIfCaretIsBesideOne(movingForward: forward) }
    }

    // With nothing selected, a command changes only what is typed next, so the selection and
    // the text stay as they were; this is the only sign of it.
    override var typingAttributes: [NSAttributedString.Key: Any] {
        didSet { formattingMayHaveChanged() }
    }

    /// Set when an edit is about to happen that may renumber the list items after it.
    private var editMayRenumber = false


    override func shouldChangeText(inRanges affectedRanges: [NSValue], replacementStrings: [String]?) -> Bool {
        guard super.shouldChangeText(inRanges: affectedRanges, replacementStrings: replacementStrings) else { return false }
        if Self.mayRenumber(affectedRanges, replacementStrings, in: string as NSString) { editMayRenumber = true }
        return true
    }

    // Undo and redo ask through this one, without going through the one above.
    override func shouldChangeText(in affectedCharRange: NSRange, replacementString: String?) -> Bool {
        guard super.shouldChangeText(in: affectedCharRange, replacementString: replacementString) else { return false }
        if Self.mayRenumber([NSValue(range: affectedCharRange)], replacementString.map { [$0] }, in: string as NSString) { editMayRenumber = true }
        return true
    }

    override func didChangeText() {
        super.didChangeText()
        if editMayRenumber {
            editMayRenumber = false
            redrawListMarkersBelowEdit()
        }
        formattingMayHaveChanged()
        onTextChange?()
    }

    /// Told whenever the note's content changes, a table cell's included, so it can be saved.
    var onTextChange: (() -> Void)?

    /// Which note this text is. Saving checks it: SwiftUI shows a newly chosen note a moment
    /// after the choice, and in that moment the editor still holds the previous note's text.
    var noteID: Data?

    private var focusWhenShown = false

    /// Puts the caret here, now if the view is in its window, or as soon as it is.
    ///
    /// The first note opened after unlocking shows a new editor, which is not in the window
    /// yet at that moment: asking for focus then fails silently, and whatever is typed next
    /// goes nowhere. Asked again after the current event too, since the list beside it can
    /// take focus as its selection changes.
    func takeFocusWhenShown() {
        focusWhenShown = true
        focusIfShown()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        focusIfShown()
    }

    private func focusIfShown() {
        guard focusWhenShown, let window else { return }
        window.makeFirstResponder(self)
        Task { @MainActor [weak self] in
            guard let self, self.focusWhenShown, let window = self.window else { return }
            self.focusWhenShown = false
            window.makeFirstResponder(self)
        }
    }

    /// Draws quotes, code blocks, dividers, checklists and list markers. Held here because the layout manager
    /// only keeps a weak reference to its delegate.
    private let blockLayout = BlockLayout()

    /// Indents list items for the markers `NoteFragment` draws. Held here for the same reason.
    private let listLayout = ListLayout()

    // What hands a note's text to a service, refused (Privacy.md). Text services are off in
    // `configureAsNoteText`, and copies stay on this Mac (NoteTextView+Pasteboard).

    /// Editing only: AppKit's own menu offers Look Up, Translate, Search With Google, Share,
    /// Services and Writing Tools. A table cell has its own, with the table's commands.
    override func menu(for event: NSEvent) -> NSMenu? { editingOnlyMenu(pasteAndMatchStyle: true) }

    /// A force click or three-finger tap would look the word up, Siri knowledge included.
    override func quickLook(with event: NSEvent) {}

    /// The Services menu, and anything else that asks a view for its text to send away.
    override func validRequestor(forSendType sendType: NSPasteboard.PasteboardType?, returnType: NSPasteboard.PasteboardType?) -> Any? { nil }


    // MARK: - Commands

    @objc func toggleBold(_ sender: Any?) { toggleFontTrait(.boldFontMask, named: "Bold") }

    @objc func toggleItalic(_ sender: Any?) { toggleFontTrait(.italicFontMask, named: "Italic") }

    @objc func toggleUnderline(_ sender: Any?) {
        toggleAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, named: "Underline")
    }

    @objc func toggleStrikethrough(_ sender: Any?) {
        toggleAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, named: "Strikethrough")
    }

    /// Monospaced type for a word or phrase. Bold and italic survive the round trip.
    @objc func toggleInlineCode(_ sender: Any?) {
        let makeCode = !everywhere { isCode(font(in: $0)) }
        restyleFonts(named: makeCode ? "Code" : "Remove Code") { font in
            makeCode ? codeFont(like: font) : bodyFont(like: font)
        }
    }

    @objc func makeTextBigger(_ sender: Any?) { resize(by: 1, named: "Bigger") }

    @objc func makeTextSmaller(_ sender: Any?) { resize(by: -1, named: "Smaller") }

    /// Back to plain body text: every character style above is removed.
    @objc func clearFormatting(_ sender: Any?) {
        let body = baseFont
        format(
            named: "Clear Formatting",
            selection: { storage, range in
                for key in Self.characterStyles { storage.removeAttribute(key, range: range) }
                storage.addAttribute(.font, value: body, range: range)
                // Set rather than removed: text with no colour at all is drawn black, which
                // vanishes in dark mode. `textColor` follows the appearance.
                storage.addAttribute(.foregroundColor, value: NSColor.textColor, range: range)
            },
            typing: { attributes in
                for key in Self.characterStyles { attributes[key] = nil }
                attributes[.font] = body
                attributes[.foregroundColor] = NSColor.textColor
            }
        )
    }

    // Paragraph commands. Alignment and writing direction are not here: NSTextView already
    // implements them (`alignLeft:`, `makeBaseWritingDirectionRightToLeft:` and so on), undo
    // included, and the menu sends those directly.

    @objc func makeTitle(_ sender: Any?) { setRole(.title) }

    @objc func makeHeading(_ sender: Any?) { setRole(.heading) }

    @objc func makeSubheading(_ sender: Any?) { setRole(.subheading) }

    @objc func makeBody(_ sender: Any?) { setRole(.body) }

    @objc func increaseIndent(_ sender: Any?) {
        changeParagraphStyle(named: "Increase Indent") { style in
            style.headIndent += Self.indentStep
            style.firstLineHeadIndent += Self.indentStep
        }
    }

    @objc func decreaseIndent(_ sender: Any?) {
        changeParagraphStyle(named: "Decrease Indent") { style in
            style.headIndent = max(0, style.headIndent - Self.indentStep)
            style.firstLineHeadIndent = max(0, style.firstLineHeadIndent - Self.indentStep)
        }
    }

    /// Line spacing as a multiple of the font's own line height. The command arrives down the
    /// responder chain, which carries no arguments, so the multiple travels as the sender.
    @objc func setLineSpacing(_ sender: Any?) {
        setLineSpacing(CGFloat((sender as? NSNumber)?.doubleValue ?? 1))
    }

    func setLineSpacing(_ multiple: CGFloat) {
        changeParagraphStyle(named: "Line Spacing") { $0.lineHeightMultiple = multiple }
    }

    /// A heading ends at Return. The new, empty paragraph starts as body text, as in Notes and
    /// Pages, rather than carrying the heading's size on into the next line of writing.
    override func insertNewline(_ sender: Any?) {
        if leaveBlockAtEmptyParagraph() { return }
        super.insertNewline(sender)
        if typingAttributes[Self.roleKey] as? String == ParagraphRole.checklist.rawValue {
            startNewChecklistItem()
            return
        }
        guard let raw = typingAttributes[Self.roleKey] as? String,
              let role = ParagraphRole(rawValue: raw), role.isHeading,
              let storage = textStorage
        else { return }
        let text = string as NSString
        let caret = selectedRange().location
        let paragraph = text.paragraphRange(for: NSRange(location: caret, length: 0))
        let content = text.substring(with: paragraph).trimmingCharacters(in: .newlines)
        guard content.isEmpty else { return }

        let body = font(for: .body, like: baseFont)
        typingAttributes[.font] = body
        typingAttributes[Self.roleKey] = nil
        // The empty paragraph's own line break still carries the heading's font, and a line is
        // as tall as its tallest character, so it is reset too. Same undo step as the Return.
        if paragraph.length > 0, shouldChangeText(in: paragraph, replacementString: nil) {
            storage.addAttribute(.font, value: body, range: paragraph)
            storage.removeAttribute(Self.roleKey, range: paragraph)
            didChangeText()
        }
    }

    /// Where a paragraph's text style is recorded. See `setRole`.
    nonisolated static let roleKey = NSAttributedString.Key("SyncedNotesParagraphRole")

    /// Whether a checklist item is checked, recorded on all of its text.
    nonisolated static let checkedKey = NSAttributedString.Key("SyncedNotesChecked")

    enum ParagraphRole: String {
        case title, heading, subheading, body
        case quote, codeBlock, divider, checklist

        var title: String {
            switch self {
            case .codeBlock: "Code Block"
            case .quote: "Block Quote"
            case .checklist: "Checklist"
            default: rawValue.capitalized
            }
        }

        var isHeading: Bool { self == .title || self == .heading || self == .subheading }

        /// Continued by Return, left by Return on an empty line or Backspace at its start.
        var isBlock: Bool { self == .quote || self == .codeBlock || self == .checklist }
    }

    /// What Clear Formatting takes away, besides resetting the font and colour.
    private static let characterStyles: [NSAttributedString.Key] = [.underlineStyle, .strikethroughStyle, .backgroundColor, highlightKey]

    // MARK: - Toggles

    /// Mixed selections are switched on, like TextEdit and Pages: only a selection that has the
    /// style everywhere is switched off. With nothing selected, what is typed next changes.
    private func toggleFontTrait(_ trait: NSFontTraitMask, named name: String) {
        let turnOn = !everywhere { has(trait, font(in: $0)) }
        restyleFonts(named: turnOn ? name : "Remove \(name)") { font in
            turnOn
                ? NSFontManager.shared.convert(font, toHaveTrait: trait)
                : NSFontManager.shared.convert(font, toNotHaveTrait: trait)
        }
    }

    private func toggleAttribute(_ key: NSAttributedString.Key, value: Any, named name: String) {
        let turnOn = !everywhere { isSet($0[key]) }
        format(
            named: turnOn ? name : "Remove \(name)",
            selection: { storage, range in
                if turnOn {
                    storage.addAttribute(key, value: value, range: range)
                } else {
                    storage.removeAttribute(key, range: range)
                }
            },
            typing: { $0[key] = turnOn ? value : nil }
        )
    }

    private func resize(by step: CGFloat, named name: String) {
        restyleFonts(named: name) { font in
            NSFontManager.shared.convert(font, toSize: min(max(font.pointSize + step, 8), 96))
        }
    }

    // MARK: - Applying a change

    /// The ranges a command acts on: the selection, minus empty carets.
    private var selectedTextRanges: [NSRange] {
        selectedRanges.map(\.rangeValue).filter { $0.length > 0 }
    }

    /// Whether every run of the selection (or, with none, the typing attributes) passes [test].
    func everywhere(_ test: ([NSAttributedString.Key: Any]) -> Bool) -> Bool {
        let ranges = selectedTextRanges
        guard let storage = textStorage, !ranges.isEmpty else { return test(typingAttributes) }
        var result = true
        for range in ranges {
            storage.enumerateAttributes(in: range) { attributes, _, stop in
                if !test(attributes) {
                    result = false
                    stop.pointee = true
                }
            }
        }
        return result
    }

    private func restyleFonts(named name: String, _ change: (NSFont) -> NSFont) {
        format(
            named: name,
            selection: { storage, range in
                // Read first, then changed, as in `changeParagraphStyle`: text made Bigger to
                // the size of the text after it would otherwise grow twice.
                var runs: [(NSRange, NSFont)] = []
                storage.enumerateAttribute(.font, in: range) { value, run, _ in runs.append((run, value as? NSFont ?? self.baseFont)) }
                for (run, font) in runs { storage.addAttribute(.font, value: change(font), range: run) }
            },
            typing: { $0[.font] = change($0[.font] as? NSFont ?? self.baseFont) }
        )
    }

    /// Applies one change to [ranges] (the selection, unless given) as a single undoable step,
    /// or, when there is nothing to change, to what is typed next.
    ///
    /// Going through shouldChangeText/didChangeText is what makes the change undoable and tells
    /// the view (and anything watching it later) that the note changed.
    ///
    /// Paragraph commands pass `alsoTyping`: the caret stays inside the paragraph they changed,
    /// and what is typed there next has to match it.
    func format(
        named name: String,
        ranges given: [NSRange]? = nil,
        alsoTyping: Bool = false,
        selection change: (NSTextStorage, NSRange) -> Void,
        typing changeTyping: (inout [NSAttributedString.Key: Any]) -> Void
    ) {
        let ranges = given ?? selectedTextRanges
        guard let storage = textStorage, !ranges.isEmpty else {
            changeTyping(&typingAttributes)
            return
        }
        guard shouldChangeText(inRanges: ranges.map { NSValue(range: $0) }, replacementStrings: nil) else { return }
        storage.beginEditing()
        for range in ranges { change(storage, range) }
        storage.endEditing()
        didChangeText()
        if alsoTyping { changeTyping(&typingAttributes) }
        undoManager?.setActionName(name)
    }

    // MARK: - Paragraphs

    /// The whole paragraphs the selection touches, merged where they meet, so a command applied
    /// to two carets in one paragraph still applies once.
    var paragraphRanges: [NSRange] {
        let text = string as NSString
        var paragraphs = IndexSet()
        for range in selectedRanges.map(\.rangeValue) {
            let paragraph = text.paragraphRange(for: range)
            paragraphs.insert(integersIn: paragraph.location..<(paragraph.location + paragraph.length))
        }
        return paragraphs.rangeView.map { NSRange(location: $0.lowerBound, length: $0.count) }
    }

    /// Gives each selected paragraph one of the note's text styles.
    ///
    /// The style is also recorded under [roleKey], not only as a font size, so that saving later
    /// can tell a heading from text that merely happens to be large.
    func setRole(_ role: ParagraphRole) {
        format(
            named: role.title,
            ranges: paragraphRanges,
            alsoTyping: true,
            selection: { storage, range in
                let previous = (storage.attribute(Self.roleKey, at: range.location, effectiveRange: nil) as? String)
                    .flatMap(ParagraphRole.init(rawValue:))
                storage.enumerateAttribute(.font, in: range) { value, run, _ in
                    storage.addAttribute(.font, value: self.font(for: role, like: value as? NSFont ?? self.baseFont), range: run)
                }
                storage.enumerateAttribute(.paragraphStyle, in: range) { value, run, _ in
                    storage.addAttribute(.paragraphStyle, value: self.indented(value, for: role, leaving: previous), range: run)
                }
                if role != .checklist { storage.removeAttribute(Self.checkedKey, range: range) }
                if role == .body {
                    storage.removeAttribute(Self.roleKey, range: range)
                } else {
                    storage.addAttribute(Self.roleKey, value: role.rawValue, range: range)
                }
            },
            typing: { attributes in
                let previous = (attributes[Self.roleKey] as? String).flatMap(ParagraphRole.init(rawValue:))
                attributes[.font] = self.font(for: role, like: attributes[.font] as? NSFont ?? self.baseFont)
                attributes[.paragraphStyle] = self.indented(attributes[.paragraphStyle], for: role, leaving: previous)
                attributes[Self.roleKey] = role == .body ? nil : role.rawValue
                if role != .checklist { attributes[Self.checkedKey] = nil }
            }
        )
    }

    /// A block's indents: a quote sits inside its bar, code inside its panel. Leaving a block
    /// takes its indents away; other roles leave whatever indent the user set.
    private func indented(_ current: Any?, for role: ParagraphRole, leaving previous: ParagraphRole?) -> NSParagraphStyle {
        let style = ((current as? NSParagraphStyle) ?? defaultParagraphStyle ?? .default).mutableCopy() as! NSMutableParagraphStyle
        switch role {
        case .quote:
            (style.headIndent, style.firstLineHeadIndent, style.tailIndent) = (20, 20, 0)
        case .codeBlock:
            (style.headIndent, style.firstLineHeadIndent, style.tailIndent) = (12, 12, -12)
        case .checklist:
            (style.headIndent, style.firstLineHeadIndent, style.tailIndent) = (24, 24, 0)
        default:
            if previous == .quote || previous == .codeBlock || previous == .checklist {
                (style.headIndent, style.firstLineHeadIndent, style.tailIndent) = (0, 0, 0)
            }
        }
        return style
    }

    /// What is typed next in an empty paragraph of [role], with nothing else applied.
    static func typingAttributes(for role: ParagraphRole) -> [NSAttributedString.Key: Any] {
        var attributes: [NSAttributedString.Key: Any] = [
            .font: plainFont(for: role), .foregroundColor: NSColor.textColor, .paragraphStyle: NSParagraphStyle.default,
        ]
        if role != .body { attributes[roleKey] = role.rawValue }
        return attributes
    }

    /// A style's own font, keeping only italic from the text it replaces: a heading's weight
    /// is part of what makes it a heading, so bold is not carried over in either direction.
    func font(for role: ParagraphRole, like old: NSFont) -> NSFont { Self.font(for: role, like: old) }

    static func font(for role: ParagraphRole, like old: NSFont) -> NSFont {
        let styled = plainFont(for: role)
        if role == .codeBlock { return codeFont(like: NSFontManager.shared.convert(old, toSize: bodyFont.pointSize)) }
        return has(.italicFontMask, old) ? NSFontManager.shared.convert(styled, toHaveTrait: .italicFontMask) : styled
    }

    /// A style's font with nothing added: the size and weight that make a heading a heading.
    ///
    /// Sizes rather than the system's text styles, which put a subheading (15) below the body
    /// size used here. Notes store roles, not sizes, so changing these restyles every note.
    static func plainFont(for role: ParagraphRole) -> NSFont {
        switch role {
        case .title: .systemFont(ofSize: 30, weight: .bold)
        case .heading: .systemFont(ofSize: 24, weight: .bold)
        case .subheading: .systemFont(ofSize: 19, weight: .bold)
        case .body, .quote, .divider, .checklist: bodyFont
        case .codeBlock: NSFont.monospacedSystemFont(ofSize: bodyFont.pointSize, weight: .regular)
        }
    }

    func changeParagraphStyle(named name: String, _ change: @escaping (NSMutableParagraphStyle) -> Void) {
        let edited = { (current: Any?) -> NSParagraphStyle in
            let style = ((current as? NSParagraphStyle) ?? self.defaultParagraphStyle ?? .default).mutableCopy() as! NSMutableParagraphStyle
            change(style)
            return style
        }
        format(
            named: name,
            ranges: paragraphRanges,
            alsoTyping: true,
            selection: { storage, range in
                // Read first, then changed: a paragraph changed to the style of the next one
                // merges with it, and enumerating while changing then met it a second time,
                // so a selection of items at different depths moved its first item twice.
                var runs: [(NSRange, Any?)] = []
                storage.enumerateAttribute(.paragraphStyle, in: range) { value, run, _ in runs.append((run, value)) }
                for (run, value) in runs { storage.addAttribute(.paragraphStyle, value: edited(value), range: run) }
            },
            typing: { $0[.paragraphStyle] = edited($0[.paragraphStyle]) }
        )
    }

    /// How far one press of Increase Indent moves a paragraph.
    static let indentStep: CGFloat = 28

    // MARK: - Fonts

    /// The note's normal text font.
    ///
    /// A constant, not `self.font`: NSTextView's `font` is not a setting but reports the font at
    /// the start of the text, so once a note opens with a title, "the body font" read from it
    /// was the title's, and Body, Clear Formatting and Return after a heading all produced titles.
    ///
    /// 16 points, not the system's body style: that is 13 on the Mac, sized for labels and
    /// lists, and too small to write in for long.
    static let bodyFont = NSFont.systemFont(ofSize: bodySize)

    /// Nonisolated, unlike the font: tables measure their cells while TextKit lays them out.
    nonisolated static let bodySize: CGFloat = 16

    var baseFont: NSFont { Self.bodyFont }

    func font(in attributes: [NSAttributedString.Key: Any]) -> NSFont {
        attributes[.font] as? NSFont ?? baseFont
    }

    func has(_ trait: NSFontTraitMask, _ font: NSFont) -> Bool { Self.has(trait, font) }

    static func has(_ trait: NSFontTraitMask, _ font: NSFont) -> Bool {
        NSFontManager.shared.traits(of: font).contains(trait)
    }

    func isSet(_ value: Any?) -> Bool {
        guard let value else { return false }
        // Underline and strikethrough are numbers, where 0 means "none".
        if let number = value as? NSNumber { return number.intValue != 0 }
        return true
    }

    func isCode(_ font: NSFont) -> Bool { font.isFixedPitch }

    private func codeFont(like font: NSFont) -> NSFont { Self.codeFont(like: font) }

    static func codeFont(like font: NSFont) -> NSFont {
        let mono = NSFont.monospacedSystemFont(ofSize: font.pointSize, weight: has(.boldFontMask, font) ? .bold : .regular)
        return has(.italicFontMask, font) ? NSFontManager.shared.convert(mono, toHaveTrait: .italicFontMask) : mono
    }

    private func bodyFont(like font: NSFont) -> NSFont {
        var body = NSFontManager.shared.convert(baseFont, toSize: font.pointSize)
        for trait in [NSFontTraitMask.boldFontMask, .italicFontMask] where has(trait, font) {
            body = NSFontManager.shared.convert(body, toHaveTrait: trait)
        }
        return body
    }
}
