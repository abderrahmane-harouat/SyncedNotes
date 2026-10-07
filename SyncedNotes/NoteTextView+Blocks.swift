import AppKit

/// Lists and blocks: the commands that change what a paragraph is, rather than how it looks.
///
/// Lists are TextKit's own `NSTextList`s: TextKit 2 draws the bullets and numbers itself, and
/// they are not characters in the text, so they cannot be half-deleted or copied as stray `•`s.
/// Quotes, code blocks and dividers are paragraph roles, drawn by `BlockLayout`.
extension NoteTextView {

    static let numberedFormat = NSTextList.MarkerFormat("{decimal}.")

    // MARK: - Commands

    @objc func toggleBulletedList(_ sender: Any?) { toggleList(.disc, named: "Bulleted List") }

    @objc func toggleDashedList(_ sender: Any?) { toggleList(.hyphen, named: "Dashed List") }

    @objc func toggleNumberedList(_ sender: Any?) { toggleList(Self.numberedFormat, named: "Numbered List") }

    @objc func toggleBlockQuote(_ sender: Any?) { toggleBlock(.quote) }

    @objc func toggleChecklist(_ sender: Any?) {
        // Its circle is drawn beside its text, so even an empty new item needs a character.
        giveEmptyLastLineALineBreak()
        toggleBlock(.checklist)
    }

    /// Checks the selected checklist items, or unchecks them if all are checked already.
    @objc func toggleChecked(_ sender: Any?) {
        guard everyParagraphRole(is: .checklist) else { return }
        setChecked(in: paragraphRanges)
    }

    @objc func toggleCodeBlock(_ sender: Any?) { toggleBlock(.codeBlock) }

    /// A divider on a line of its own, starting a new line first if the caret is mid-paragraph.
    @objc func insertDivider(_ sender: Any?) {
        let text = string as NSString
        let at = selectedRange()
        // A plain paragraph style, so a divider inserted inside a list gets no bullet.
        let plain: [NSAttributedString.Key: Any] = [
            .font: baseFont, .foregroundColor: NSColor.textColor, .paragraphStyle: NSParagraphStyle.default,
        ]
        let insertion = NSMutableAttributedString()
        if at.location > 0, text.character(at: at.location - 1) != Self.newline {
            insertion.append(NSAttributedString(string: "\n", attributes: typingAttributes))
        }
        insertion.append(NoteDivider.make(attributes: plain))
        insertion.append(NSAttributedString(string: "\n", attributes: plain))
        insertText(insertion, replacementRange: at)
        typingAttributes = plain
        undoManager?.setActionName("Insert Divider")
    }

    // MARK: - Typing a list

    /// A list started by typing, as in Apple Notes: at the start of a plain paragraph, a space
    /// after "-" makes a dashed list, after "*" or "•" a bulleted one, and after a number and
    /// "." or ")" a numbered one, starting at that number, so items with other writing between
    /// them can go on counting ("2. " after a paragraph is item 2). What was typed is replaced
    /// by the list's own marker.
    override func insertText(_ text: Any, replacementRange: NSRange) {
        super.insertText(text, replacementRange: replacementRange)
        guard (text as? String ?? (text as? NSAttributedString)?.string) == " " else { return }
        startListFromTypedMarker()
    }

    private func startListFromTypedMarker() {
        guard selectedRanges.count == 1, selectedRange().length == 0, let storage = textStorage else { return }
        let caret = selectedRange().location
        let text = string as NSString
        let paragraph = text.paragraphRange(for: NSRange(location: caret, length: 0))
        let typed = NSRange(location: paragraph.location, length: caret - paragraph.location)
        guard typed.length >= 2, typed.length <= 5 else { return }
        let marker = text.substring(with: NSRange(location: typed.location, length: typed.length - 1))
        // Only plain paragraphs: a heading, a quote or code, or a paragraph already in a list
        // keeps what was typed as it is.
        let attributes = storage.attributes(at: paragraph.location, effectiveRange: nil)
        guard attributes[Self.roleKey] == nil,
              (attributes[.paragraphStyle] as? NSParagraphStyle)?.textLists.isEmpty ?? true
        else { return }

        let command: (format: NSTextList.MarkerFormat, name: String, start: Int)
        switch marker {
        case "-": command = (.hyphen, "Dashed List", 1)
        case "*", "•": command = (.disc, "Bulleted List", 1)
        default:
            guard let last = marker.last, last == "." || last == ")",
                  let number = Int(marker.dropLast()), (0...999).contains(number)
            else { return }
            command = (Self.numberedFormat, "Numbered List", max(number, 1))
        }

        guard shouldChangeText(in: typed, replacementString: "") else { return }
        storage.replaceCharacters(in: typed, with: "")
        didChangeText()
        setSelectedRange(NSRange(location: paragraph.location, length: 0))
        toggleList(command.format, named: command.name, startingAt: command.start)
    }

    // MARK: - Keys inside lists and blocks

    /// Tab nests a list item one level deeper, and moves a checklist item in a step; elsewhere
    /// it types a tab as usual.
    override func insertTab(_ sender: Any?) {
        if everyParagraphRole(is: .checklist) { return indentChecklistItems(by: 1) }
        guard everyParagraph({ !$0.textLists.isEmpty }) else { return super.insertTab(sender) }
        nestListItems()
    }

    /// Shift-Tab brings a nested list item, or an indented checklist item, back out one level.
    override func insertBacktab(_ sender: Any?) {
        if everyParagraphRole(is: .checklist) { return indentChecklistItems(by: -1) }
        guard everyParagraph({ !$0.textLists.isEmpty }) else { return super.insertBacktab(sender) }
        if everyParagraph({ $0.textLists.count > 1 }) { outdentListItems() }
    }

    /// A click on a checklist item's circle checks or unchecks it, without moving the caret.
    /// A click on a picture selects it, ready to copy; once selected, AppKit's own handling
    /// takes over, so it can be dragged.
    override func mouseDown(with event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        if let item = checklistItem(atCircle: point) {
            return setChecked(in: [item])
        }
        if event.clickCount == 1, !event.modifierFlags.contains(.shift),
           let picture = pictureRange(at: point), selectedRange() != picture {
            window?.makeFirstResponder(self)
            return setSelectedRange(picture)
        }
        super.mouseDown(with: event)
    }

    /// The checklist item whose circle is at [point], in the view's coordinates.
    func checklistItem(atCircle point: NSPoint) -> NSRange? {
        guard let layout = textLayoutManager, let content = layout.textContentManager,
              let width = layout.textContainer?.size.width
        else { return nil }
        let inContainer = CGPoint(x: point.x - textContainerOrigin.x, y: point.y - textContainerOrigin.y)
        // Found by height alone: the circle sits in the margin, left of where the item's text,
        // and so its fragment, begins.
        guard let item = layout.textLayoutFragment(for: CGPoint(x: width / 2, y: inContainer.y)) as? ChecklistFragment else { return nil }
        let frame = item.layoutFragmentFrame
        // A little larger than the circle, which is small to aim at.
        let target = item.checkboxRect.offsetBy(dx: frame.minX, dy: frame.minY).insetBy(dx: -4, dy: -4)
        guard target.contains(inContainer) else { return nil }
        let range = item.rangeInElement
        return NSRange(
            location: content.offset(from: content.documentRange.location, to: range.location),
            length: content.offset(from: range.location, to: range.endLocation)
        )
    }

    private func setChecked(in ranges: [NSRange]) {
        let allChecked = everyCharacter(in: ranges, hasValue: true, for: Self.checkedKey)
        format(
            named: allChecked ? "Uncheck" : "Check",
            ranges: ranges,
            selection: { storage, range in
                if allChecked {
                    storage.removeAttribute(Self.checkedKey, range: range)
                } else {
                    storage.addAttribute(Self.checkedKey, value: true, range: range)
                }
            },
            typing: { $0[Self.checkedKey] = allChecked ? nil : true }
        )
    }

    /// After Return in a checklist: the new item starts unchecked, and gets a character of its
    /// own at the end of the note so its circle shows before anything is typed.
    func startNewChecklistItem() {
        typingAttributes[Self.checkedKey] = nil
        let text = string as NSString
        let paragraph = text.paragraphRange(for: NSRange(location: selectedRange().location, length: 0))
        if paragraph.length == 0 { return giveEmptyLastLineALineBreak() }
        guard let storage = textStorage,
              text.substring(with: paragraph).trimmingCharacters(in: .newlines).isEmpty,
              storage.attribute(Self.checkedKey, at: paragraph.location, effectiveRange: nil) != nil,
              shouldChangeText(in: paragraph, replacementString: nil)
        else { return }
        storage.removeAttribute(Self.checkedKey, range: paragraph)
        didChangeText()
    }

    /// Backspace at the very start of a list item or block takes the paragraph out of it
    /// (one level, for a nested item) instead of joining it to the paragraph above.
    override func deleteBackward(_ sender: Any?) {
        if let paragraph = caretParagraph, selectedRange().location == paragraph.range.location {
            if !paragraph.style.textLists.isEmpty { return outdentListItems() }
            if paragraph.role == .checklist, Self.checklistLevel(of: paragraph.style) > 0 { return indentChecklistItems(by: -1) }
            if paragraph.role?.isBlock == true { return setRole(.body) }
        }
        super.deleteBackward(sender)
    }

    /// Return on an empty list item or empty block line leaves it, rather than adding another
    /// empty one, as in Notes and Pages. Returns whether it did.
    func leaveBlockAtEmptyParagraph() -> Bool {
        guard let paragraph = caretParagraph, paragraph.isEmpty else { return false }
        if !paragraph.style.textLists.isEmpty {
            outdentListItems()
            return true
        }
        // An indented checklist item steps out a level first, as a nested list item does.
        if paragraph.role == .checklist, Self.checklistLevel(of: paragraph.style) > 0 {
            indentChecklistItems(by: -1)
            return true
        }
        if paragraph.role?.isBlock == true {
            setRole(.body)
            return true
        }
        return false
    }

    // MARK: - Lists

    private func toggleList(_ format: NSTextList.MarkerFormat, named name: String, startingAt start: Int = 1) {
        giveEmptyLastLineALineBreak()
        // By kind, not exact marker, so a nested bullet (◦) counts as bulleted, as the menu's
        // checkmark shows it.
        if everyParagraph({ $0.textLists.last.flatMap { ListKind($0.markerFormat) } == ListKind(format) }) {
            changeParagraphStyle(named: "Remove \(name)") { $0.textLists = [] }
            return
        }
        // TextKit numbers paragraphs as one list only when they share the same NSTextList
        // object; equal but separate lists each restart at 1. So every selected paragraph gets
        // one list, and it is the list just above when that is the same kind at the same level.
        let level = max(firstParagraphStyle.textLists.count, 1)
        let list = listAbove(atLevel: level, ofFormat: format)
            ?? NSTextList(markerFormat: format, options: [], startingItemNumber: max(1, start))
        changeParagraphStyle(named: name) { style in
            if style.textLists.isEmpty {
                style.textLists = [list]
            } else {
                style.textLists[style.textLists.count - 1] = list
            }
        }
    }

    /// NSTextView strips lists from what is typed next (it keeps the indent, drops the list),
    /// so the empty last line of a note, which has no characters, cannot hold one. This gives
    /// that line a line break of its own to carry the list, with the caret staying in front of
    /// it: the same thing AppKit does when Return continues a list at the end of a note. It
    /// lands in the same undo step as the list command that follows.
    func giveEmptyLastLineALineBreak() {
        guard paragraphRanges.isEmpty, let storage = textStorage else { return }
        let caret = NSRange(location: selectedRange().location, length: 0)
        guard shouldChangeText(in: caret, replacementString: "\n") else { return }
        storage.replaceCharacters(in: caret, with: NSAttributedString(string: "\n", attributes: typingAttributes))
        didChangeText()
        setSelectedRange(caret)
    }

    private func nestListItems() {
        let parents = firstParagraphStyle.textLists
        guard let parent = parents.last else { return }
        // Join a nested list the item above already started, so the numbering runs on.
        let above = paragraphStyleAbove?.textLists ?? []
        let sameParents = above.count > parents.count && zip(above, parents).allSatisfy { $0 === $1 }
        let nested = sameParents ? above[parents.count] : NSTextList(markerFormat: Self.nestedFormat(parent.markerFormat), options: 0)
        changeParagraphStyle(named: "Indent List Item") { style in
            guard !style.textLists.isEmpty, style.textLists.count < Self.maxListDepth else { return }
            style.textLists.append(nested)
        }
    }

    private func outdentListItems() {
        changeParagraphStyle(named: "Outdent List Item") { style in
            if !style.textLists.isEmpty { style.textLists.removeLast() }
        }
    }

    private static let maxListDepth = 8

    /// Each level of nesting gets its own marker, so the levels can be told apart at a glance.
    static func nestedFormat(_ format: NSTextList.MarkerFormat) -> NSTextList.MarkerFormat {
        switch format {
        case .disc: .circle
        case .circle: .square
        case .square: .disc
        case numberedFormat: NSTextList.MarkerFormat("\(NSTextList.MarkerFormat.lowercaseAlpha.rawValue).")
        case NSTextList.MarkerFormat("\(NSTextList.MarkerFormat.lowercaseAlpha.rawValue)."):
            NSTextList.MarkerFormat("\(NSTextList.MarkerFormat.lowercaseRoman.rawValue).")
        case NSTextList.MarkerFormat("\(NSTextList.MarkerFormat.lowercaseRoman.rawValue)."): numberedFormat
        default: format
        }
    }

    private func listAbove(atLevel level: Int, ofFormat format: NSTextList.MarkerFormat) -> NSTextList? {
        guard let above = paragraphStyleAbove?.textLists, above.count >= level,
              above[level - 1].markerFormat == format
        else { return nil }
        return above[level - 1]
    }

    // MARK: - Blocks

    private func toggleBlock(_ role: ParagraphRole) {
        setRole(everyParagraphRole(is: role) ? .body : role)
    }

    /// How many steps a checklist item is indented, beyond the room its circle takes.
    static func checklistLevel(of style: NSParagraphStyle) -> Int {
        max(0, Int(((style.headIndent - NoteFormat.indent(of: .checklist)) / indentStep).rounded()))
    }

    private static let maxChecklistLevel = 6

    /// Moves checklist items in or out by [steps], each by its own level, so a selection of
    /// items at different depths keeps its shape. The circle moves with the text
    /// (`ChecklistFragment`), and the level is saved as the paragraph's indent.
    private func indentChecklistItems(by steps: Int) {
        changeParagraphStyle(named: steps > 0 ? "Indent Checklist Item" : "Outdent Checklist Item") { style in
            let level = min(max(Self.checklistLevel(of: style) + steps, 0), Self.maxChecklistLevel)
            let indent = NoteFormat.indent(of: .checklist) + CGFloat(level) * Self.indentStep
            (style.headIndent, style.firstLineHeadIndent) = (indent, indent)
        }
    }

    // MARK: - Reading paragraphs

    private static let newline = unichar(0x0A)

    private var typingParagraphStyle: NSParagraphStyle {
        typingAttributes[.paragraphStyle] as? NSParagraphStyle ?? defaultParagraphStyle ?? .default
    }

    /// Whether every paragraph the selection touches passes [test]; with none (an empty note,
    /// or the empty last line), what is typed next.
    func everyParagraph(_ test: (NSParagraphStyle) -> Bool) -> Bool {
        let ranges = paragraphRanges
        guard let storage = textStorage, !ranges.isEmpty else { return test(typingParagraphStyle) }
        var result = true
        for range in ranges {
            storage.enumerateAttribute(.paragraphStyle, in: range) { value, _, stop in
                if !test(value as? NSParagraphStyle ?? self.defaultParagraphStyle ?? .default) {
                    result = false
                    stop.pointee = true
                }
            }
        }
        return result
    }

    func everyCharacter(in ranges: [NSRange], hasValue value: Bool, for key: NSAttributedString.Key) -> Bool {
        guard let storage = textStorage, !ranges.isEmpty else { return typingAttributes[key] as? Bool == value }
        var result = true
        for range in ranges {
            storage.enumerateAttribute(key, in: range) { found, _, stop in
                if found as? Bool != value {
                    result = false
                    stop.pointee = true
                }
            }
        }
        return result
    }

    private func everyParagraphRole(is role: ParagraphRole) -> Bool {
        let ranges = paragraphRanges
        guard let storage = textStorage, !ranges.isEmpty else {
            return typingAttributes[Self.roleKey] as? String == role.rawValue
        }
        var result = true
        for range in ranges {
            storage.enumerateAttribute(Self.roleKey, in: range) { value, _, stop in
                if value as? String != role.rawValue {
                    result = false
                    stop.pointee = true
                }
            }
        }
        return result
    }

    var firstParagraphStyle: NSParagraphStyle {
        guard let first = paragraphRanges.first, let storage = textStorage else { return typingParagraphStyle }
        return storage.attribute(.paragraphStyle, at: first.location, effectiveRange: nil) as? NSParagraphStyle ?? typingParagraphStyle
    }

    /// The style of the paragraph just above the first selected one.
    private var paragraphStyleAbove: NSParagraphStyle? {
        let start = paragraphRanges.first?.location ?? (string as NSString).paragraphRange(for: selectedRange()).location
        guard start > 0, let storage = textStorage else { return nil }
        return storage.attribute(.paragraphStyle, at: start - 1, effectiveRange: nil) as? NSParagraphStyle
    }

    /// The paragraph holding a plain caret (no selection, one caret), as it stands.
    private var caretParagraph: (range: NSRange, style: NSParagraphStyle, role: ParagraphRole?, isEmpty: Bool)? {
        guard selectedRanges.count == 1, selectedRange().length == 0, let storage = textStorage else { return nil }
        let text = string as NSString
        let range = text.paragraphRange(for: selectedRange())
        // The empty last line has no characters to carry attributes: it is what is typed next.
        let attributes = range.length > 0 ? storage.attributes(at: range.location, effectiveRange: nil) : typingAttributes
        let style = attributes[.paragraphStyle] as? NSParagraphStyle ?? defaultParagraphStyle ?? .default
        let role = (attributes[Self.roleKey] as? String).flatMap(ParagraphRole.init(rawValue:))
        let isEmpty = text.substring(with: range).trimmingCharacters(in: .newlines).isEmpty
        return (range, style, role, isEmpty)
    }
}
