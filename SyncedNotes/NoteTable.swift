import AppKit

// Tables, built the way Apple Notes builds its own.
//
// TextKit 2 has no table support: an NSTextTable in the editor is silently ignored and each cell
// drawn as a plain line. Notes (whose classes can be listed from its frameworks) makes a table
// an attachment, `ICTableTextAttachment`, holding its own table model, `ICTable`, and shown by
// an embedded table view with text views for its cells. So here: `TableAttachment` holds the
// cells, TextKit asks it for a view through `TableViewProvider`, and `TableView` lays out one
// `TableCellTextView` per cell. The behaviour follows Apple's guide to tables in Notes: a new
// table is 2 x 2, Tab and the arrows move between cells, Tab or Return in the last cell adds a
// row, rows and columns are added and removed from a menu, and a table can be turned into text.

// MARK: - The table

/// A table in a note: one attachment character in the note's text, holding the whole table.
nonisolated final class TableAttachment: NSTextAttachment {

    /// The cells, row by row: the table's one source of truth. TextKit makes a new view for the
    /// table whenever it comes back into sight, and each view is built from this.
    var cells: [[NSAttributedString]]

    /// The view on screen now, if there is one.
    weak var view: TableView?

    var rowCount: Int { cells.count }
    var columnCount: Int { cells.first?.count ?? 0 }

    init(rows: Int, columns: Int) {
        cells = Array(repeating: Array(repeating: NSAttributedString(), count: columns), count: rows)
        super.init(data: nil, ofType: nil)
        // A blank image: without one, TextKit 2 draws a generic document icon under the table.
        image = NSImage(size: NSSize(width: 1, height: 1))
    }

    required init?(coder: NSCoder) {
        cells = [[NSAttributedString()]]
        super.init(coder: coder)
    }

    override func viewProvider(
        for parentView: NSView?,
        location: any NSTextLocation,
        textContainer: NSTextContainer?
    ) -> NSTextAttachmentViewProvider? {
        TableViewProvider(
            textAttachment: self,
            parentView: parentView,
            textLayoutManager: textContainer?.textLayoutManager,
            location: location
        )
    }
}

/// Gives TextKit the table's view, and its size before the view even exists.
nonisolated final class TableViewProvider: NSTextAttachmentViewProvider {

    override init(
        textAttachment: NSTextAttachment,
        parentView: NSView?,
        textLayoutManager: NSTextLayoutManager?,
        location: any NSTextLocation
    ) {
        super.init(textAttachment: textAttachment, parentView: parentView, textLayoutManager: textLayoutManager, location: location)
        // Here, not in loadView: TextKit asks for the bounds before it loads the view, and only
        // uses the ones below if this is already set (Apple's advice on its developer forums).
        tracksTextAttachmentViewBounds = true
    }

    override func loadView() {
        guard let table = textAttachment as? TableAttachment else { return }
        // Main thread only: TextKit loads attachment views there, and every edit to a table comes
        // from its view. Swift cannot see that across TextKit's nonisolated methods.
        nonisolated(unsafe) let onMain = table
        view = MainActor.assumeIsolated { TableView(table: onMain) }
    }

    override func attachmentBounds(
        for attributes: [NSAttributedString.Key: Any],
        location: any NSTextLocation,
        textContainer: NSTextContainer?,
        proposedLineFragment: CGRect,
        position: CGPoint
    ) -> CGRect {
        guard let table = textAttachment as? TableAttachment else { return .zero }
        // The full width of the text, a hair short so TextKit keeps it on its own line.
        let width = max(proposedLineFragment.width - position.x - 1, TableMetrics.minimumWidth)
        return CGRect(x: 0, y: 0, width: width, height: TableMetrics.height(of: table.cells, width: width))
    }
}

/// Where the table's rows and columns go. Worked out from the cells alone, so the size TextKit
/// reserves for a table and the layout its view draws always agree.
nonisolated enum TableMetrics {
    static let cellPadding = NSSize(width: 7, height: 5)
    static let minimumWidth: CGFloat = 120

    /// Equal columns filling the width; the last takes whatever rounding leaves over.
    static func columnWidths(count: Int, width: CGFloat) -> [CGFloat] {
        guard count > 0 else { return [] }
        let each = (width / CGFloat(count)).rounded(.down)
        return (0..<count).map { $0 == count - 1 ? width - each * CGFloat(count - 1) : each }
    }

    /// Each row as tall as its tallest cell: text wraps inside its cell, and the row grows.
    static func rowHeights(of cells: [[NSAttributedString]], width: CGFloat) -> [CGFloat] {
        let widths = columnWidths(count: cells.first?.count ?? 0, width: width)
        return cells.map { row in
            let tallest = zip(row, widths).map { textHeight($0, width: $1 - 2 * cellPadding.width) }.max() ?? 0
            return tallest + 2 * cellPadding.height
        }
    }

    static func height(of cells: [[NSAttributedString]], width: CGFloat) -> CGFloat {
        rowHeights(of: cells, width: width).reduce(0, +) + 1
    }

    private static func textHeight(_ text: NSAttributedString, width: CGFloat) -> CGFloat {
        // An empty cell is one line of body text tall, the height the caret needs.
        let measured = text.length > 0 ? text : NSAttributedString(string: " ", attributes: [.font: NSFont.systemFont(ofSize: NoteTextView.bodySize)])
        let bounds = measured.boundingRect(
            with: NSSize(width: max(width, 1), height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading]
        )
        return ceil(bounds.height)
    }
}

// MARK: - The view

/// The table on screen: its cells, and the lines between them.
final class TableView: NSView {

    let table: TableAttachment
    private(set) var cellViews: [[TableCellTextView]] = []

    override var isFlipped: Bool { true }

    init(table: TableAttachment) {
        self.table = table
        super.init(frame: .zero)
        table.view = self
        rebuild()
    }

    required init?(coder: NSCoder) { fatalError("Tables are built from their attachment, not from a nib.") }

    /// The note the table sits in.
    var host: NoteTextView? {
        var view = superview
        while let current = view {
            if let note = current as? NoteTextView, !(note is TableCellTextView) { return note }
            view = current.superview
        }
        return nil
    }

    /// Makes the cell views afresh from the table's cells.
    ///
    /// If a cell was being edited, the cell at the same place (or the nearest one left, when
    /// rows or columns went away) takes over. Otherwise the edited cell simply disappears, as it
    /// did on Undo, and with it the window's focus: typing afterwards went nowhere.
    func rebuild() {
        let editing = (window?.firstResponder as? TableCellTextView).flatMap(position(of:))
        cellViews.joined().forEach { $0.removeFromSuperview() }
        cellViews = table.cells.map { row in
            row.map { content in
                let cell = TableCellTextView.make(content: content)
                cell.table = self
                addSubview(cell)
                return cell
            }
        }
        needsLayout = true
        needsDisplay = true
        if let (row, column) = editing, table.rowCount > 0 {
            layoutSubtreeIfNeeded()
            focus(row: min(row, table.rowCount - 1), column: min(column, table.columnCount - 1))
        }
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        needsLayout = true
    }

    override func layout() {
        super.layout()
        let widths = TableMetrics.columnWidths(count: table.columnCount, width: bounds.width)
        let heights = TableMetrics.rowHeights(of: table.cells, width: bounds.width)
        var y: CGFloat = 0
        for (row, cells) in cellViews.enumerated() {
            var x: CGFloat = 0
            for (column, cell) in cells.enumerated() {
                cell.frame = NSRect(x: x, y: y, width: widths[column], height: heights[row])
                x += widths[column]
            }
            y += heights[row]
        }
        needsDisplay = true
    }

    override func draw(_ dirtyRect: NSRect) {
        let widths = TableMetrics.columnWidths(count: table.columnCount, width: bounds.width)
        let heights = TableMetrics.rowHeights(of: table.cells, width: bounds.width)
        let grid = NSBezierPath()
        grid.lineWidth = 1
        // Half-point offsets put each one-point line on whole pixels, so it stays sharp; the
        // last line is pulled in by a point so it is not clipped at the table's edge.
        var y: CGFloat = 0
        for height in [0] + heights {
            y += height
            let line = min(y, bounds.height - 1) + 0.5
            grid.move(to: NSPoint(x: 0, y: line))
            grid.line(to: NSPoint(x: bounds.width, y: line))
        }
        var x: CGFloat = 0
        for width in [0] + widths {
            x += width
            let line = min(x, bounds.width - 1) + 0.5
            grid.move(to: NSPoint(x: line, y: 0))
            grid.line(to: NSPoint(x: line, y: bounds.height))
        }
        NSColor.tertiaryLabelColor.setStroke()
        grid.stroke()
    }

    // MARK: Cells

    func position(of cell: TableCellTextView) -> (row: Int, column: Int)? {
        for (row, cells) in cellViews.enumerated() {
            if let column = cells.firstIndex(where: { $0 === cell }) { return (row, column) }
        }
        return nil
    }

    /// Keeps the table's cells up to date as a cell is edited, and resizes the table in the note
    /// when a row grows or shrinks.
    func cellDidChange(_ cell: TableCellTextView) {
        guard let (row, column) = position(of: cell), let storage = cell.textStorage else { return }
        let before = TableMetrics.height(of: table.cells, width: bounds.width)
        table.cells[row][column] = NSAttributedString(attributedString: storage)
        if TableMetrics.height(of: table.cells, width: bounds.width) != before {
            host?.tableDidChangeSize(table)
        }
        // A cell's text is the note's content too: the note has to be saved.
        host?.onTextChange?()
        needsLayout = true
    }

    func focus(row: Int, column: Int, atEnd: Bool = true) {
        guard cellViews.indices.contains(row), cellViews[row].indices.contains(column), window != nil else { return }
        let cell = cellViews[row][column]
        window?.makeFirstResponder(cell)
        cell.setSelectedRange(NSRange(location: atEnd ? (cell.string as NSString).length : 0, length: 0))
    }

    /// Tab: the next cell, or at the last one a new row, as in Notes.
    func moveToNextCell(from cell: TableCellTextView) {
        guard let (row, column) = position(of: cell) else { return }
        if column + 1 < table.columnCount {
            focus(row: row, column: column + 1)
        } else if row + 1 < table.rowCount {
            focus(row: row + 1, column: 0)
        } else {
            addRow(at: table.rowCount, focusColumn: 0, named: "Add Row")
        }
    }

    /// Shift-Tab: the previous cell. At the first there is nowhere to go.
    func moveToPreviousCell(from cell: TableCellTextView, atEnd: Bool = true) {
        guard let (row, column) = position(of: cell) else { return }
        if column > 0 {
            focus(row: row, column: column - 1, atEnd: atEnd)
        } else if row > 0 {
            focus(row: row - 1, column: table.columnCount - 1, atEnd: atEnd)
        }
    }

    /// The right arrow at the end of a cell: the next cell, or out of the table after the last.
    func moveRight(from cell: TableCellTextView) {
        guard let (row, column) = position(of: cell) else { return }
        if column + 1 < table.columnCount {
            focus(row: row, column: column + 1, atEnd: false)
        } else if row + 1 < table.rowCount {
            focus(row: row + 1, column: 0, atEnd: false)
        } else {
            host?.placeCaret(beside: table, after: true)
        }
    }

    /// The left arrow at the start of a cell: the previous cell, or out of the table before it.
    func moveLeft(from cell: TableCellTextView) {
        guard let (row, column) = position(of: cell) else { return }
        if row == 0 && column == 0 {
            host?.placeCaret(beside: table, after: false)
        } else {
            moveToPreviousCell(from: cell, atEnd: true)
        }
    }

    /// Up or down past a cell's edge: the cell above or below, or out of the table.
    func moveVertically(from cell: TableCellTextView, by step: Int) {
        guard let (row, column) = position(of: cell) else { return }
        let target = row + step
        if table.cells.indices.contains(target) {
            focus(row: target, column: column, atEnd: step < 0)
        } else {
            host?.placeCaret(beside: table, after: step > 0)
        }
    }

    func isLastCell(_ cell: TableCellTextView) -> Bool {
        guard let (row, column) = position(of: cell) else { return false }
        return row == table.rowCount - 1 && column == table.columnCount - 1
    }

    // MARK: Rows and columns

    func addRow(at index: Int, focusColumn column: Int, named name: String) {
        change(named: name, focus: (index, column)) { cells, columns in
            cells.insert(Array(repeating: NSAttributedString(), count: columns), at: index)
        }
    }

    func addColumn(at index: Int, focusRow row: Int, named name: String) {
        change(named: name, focus: (row, index)) { cells, _ in
            for row in cells.indices { cells[row].insert(NSAttributedString(), at: index) }
        }
    }

    /// The last row can't be deleted on its own: deleting it deletes the table, as in Notes.
    func deleteRow(_ row: Int, focusColumn column: Int) {
        guard table.rowCount > 1 else { return host?.replaceTable(table, with: NSAttributedString(), named: "Delete Table") ?? () }
        change(named: "Delete Row", focus: (min(row, table.rowCount - 2), column)) { cells, _ in
            cells.remove(at: row)
        }
    }

    func deleteColumn(_ column: Int, focusRow row: Int) {
        guard table.columnCount > 1 else { return host?.replaceTable(table, with: NSAttributedString(), named: "Delete Table") ?? () }
        change(named: "Delete Column", focus: (row, min(column, table.columnCount - 2))) { cells, _ in
            for row in cells.indices { cells[row].remove(at: column) }
        }
    }

    /// The table as text: a paragraph per row, cells separated by tabs.
    func asText() -> NSAttributedString {
        let text = NSMutableAttributedString()
        let body: [NSAttributedString.Key: Any] = [.font: NoteTextView.bodyFont, .foregroundColor: NSColor.textColor]
        for (index, row) in table.cells.enumerated() {
            for (column, cell) in row.enumerated() {
                if column > 0 { text.append(NSAttributedString(string: "\t", attributes: body)) }
                text.append(cell)
            }
            if index < table.rowCount - 1 { text.append(NSAttributedString(string: "\n", attributes: body)) }
        }
        return text
    }

    /// Applies a change to the rows and columns as one undoable step.
    private func change(named name: String, focus target: (Int, Int), _ edit: (inout [[NSAttributedString]], Int) -> Void) {
        let before = table.cells
        edit(&table.cells, table.columnCount)
        registerUndo(restoring: before, named: name)
        rebuild()
        host?.tableDidChangeSize(table)
        host?.onTextChange?()
        layoutSubtreeIfNeeded()
        focus(row: target.0, column: target.1)
    }

    /// Undo puts the cells back as they were, and registers the way forward again for Redo.
    private func registerUndo(restoring cells: [[NSAttributedString]], named name: String) {
        guard let undo = host?.undoManager ?? window?.undoManager else { return }
        undo.registerUndo(withTarget: table) { table in
            MainActor.assumeIsolated {
                let now = table.cells
                table.cells = cells
                table.view?.registerUndo(restoring: now, named: name)
                table.view?.rebuild()
                table.view?.host?.tableDidChangeSize(table)
                table.view?.host?.onTextChange?()
            }
        }
        undo.setActionName(name)
    }
}

// MARK: - A cell

/// One cell's text. A note text view in its own right, so every character style and
/// ⌘B-style shortcut works inside cells, with keys added to move around the table.
final class TableCellTextView: NoteTextView {

    weak var table: TableView?

    static func make(content: NSAttributedString) -> TableCellTextView {
        let cell = TableCellTextView(usingTextLayoutManager: true)
        // Set up before the content goes in: setting up sets the font, and would restyle it.
        cell.configureAsNoteText()
        cell.drawsBackground = false
        cell.textContainerInset = TableMetrics.cellPadding
        cell.textContainer?.lineFragmentPadding = 0
        cell.textContainer?.widthTracksTextView = true
        cell.isVerticallyResizable = false
        cell.isHorizontallyResizable = false
        cell.textStorage?.setAttributedString(content)
        return cell
    }

    override func becomeFirstResponder() -> Bool {
        let accepted = super.becomeFirstResponder()
        // The toolbar and menus follow the cell being edited, as they follow the note.
        if accepted {
            onFormattingChange = table?.host?.onFormattingChange
            formattingMayHaveChanged()
        }
        return accepted
    }

    override func didChangeText() {
        super.didChangeText()
        table?.cellDidChange(self)
    }

    override func insertTab(_ sender: Any?) { table?.moveToNextCell(from: self) }

    override func insertBacktab(_ sender: Any?) { table?.moveToPreviousCell(from: self) }

    /// Return in the last cell adds a row, as in Notes; anywhere else it breaks the line
    /// inside the cell. ⌥Return always breaks the line.
    override func insertNewline(_ sender: Any?) {
        if let table, table.isLastCell(self) {
            table.moveToNextCell(from: self)
        } else {
            super.insertNewline(sender)
        }
    }

    override func moveUp(_ sender: Any?) {
        let before = selectedRange()
        super.moveUp(sender)
        // Already on the first line: nothing moved, so it is time to leave the cell.
        if selectedRange() == before { table?.moveVertically(from: self, by: -1) }
    }

    override func moveDown(_ sender: Any?) {
        let before = selectedRange()
        super.moveDown(sender)
        if selectedRange() == before { table?.moveVertically(from: self, by: 1) }
    }

    override func moveLeft(_ sender: Any?) {
        if selectedRange() == NSRange(location: 0, length: 0) { return table?.moveLeft(from: self) ?? () }
        super.moveLeft(sender)
    }

    override func moveRight(_ sender: Any?) {
        if selectedRange() == NSRange(location: (string as NSString).length, length: 0) { return table?.moveRight(from: self) ?? () }
        super.moveRight(sender)
    }

    /// No tables inside tables.
    override func insertTable(_ sender: Any?) { NSSound.beep() }

    // MARK: Row and column commands, from the menus and the cell's own right-click menu

    @objc func addRowAbove(_ sender: Any?) {
        guard let (row, column) = table?.position(of: self) else { return }
        table?.addRow(at: row, focusColumn: column, named: "Add Row Above")
    }

    @objc func addRowBelow(_ sender: Any?) {
        guard let (row, column) = table?.position(of: self) else { return }
        table?.addRow(at: row + 1, focusColumn: column, named: "Add Row Below")
    }

    @objc func addColumnBefore(_ sender: Any?) {
        guard let (row, column) = table?.position(of: self) else { return }
        table?.addColumn(at: column, focusRow: row, named: "Add Column Before")
    }

    @objc func addColumnAfter(_ sender: Any?) {
        guard let (row, column) = table?.position(of: self) else { return }
        table?.addColumn(at: column + 1, focusRow: row, named: "Add Column After")
    }

    @objc func deleteTableRow(_ sender: Any?) {
        guard let (row, column) = table?.position(of: self) else { return }
        table?.deleteRow(row, focusColumn: column)
    }

    @objc func deleteTableColumn(_ sender: Any?) {
        guard let (row, column) = table?.position(of: self) else { return }
        table?.deleteColumn(column, focusRow: row)
    }

    @objc func convertTableToText(_ sender: Any?) {
        guard let table else { return }
        table.host?.replaceTable(table.table, with: table.asText(), named: "Convert to Text")
    }

    @objc func deleteTable(_ sender: Any?) {
        guard let table else { return }
        table.host?.replaceTable(table.table, with: NSAttributedString(), named: "Delete Table")
    }

    /// Only these: the cell's editing and the table's own commands. AppKit's default menu also
    /// offers Look Up, Translate and search, which the privacy rule keeps out.
    override func menu(for event: NSEvent) -> NSMenu? {
        let menu = NSMenu()
        for (title, action) in [("Cut", #selector(cut(_:))), ("Copy", #selector(copy(_:))), ("Paste", #selector(paste(_:)))] {
            menu.addItem(withTitle: title, action: action, keyEquivalent: "").target = self
        }
        let table: [(String, Selector)?] = [
            nil,
            ("Add Row Above", #selector(addRowAbove(_:))), ("Add Row Below", #selector(addRowBelow(_:))),
            ("Add Column Before", #selector(addColumnBefore(_:))), ("Add Column After", #selector(addColumnAfter(_:))),
            nil,
            ("Delete Row", #selector(deleteTableRow(_:))), ("Delete Column", #selector(deleteTableColumn(_:))),
            nil,
            ("Convert to Text", #selector(convertTableToText(_:))), ("Delete Table", #selector(deleteTable(_:))),
        ]
        for item in table {
            if let (title, action) = item {
                menu.addItem(withTitle: title, action: action, keyEquivalent: "").target = self
            } else {
                menu.addItem(.separator())
            }
        }
        return menu
    }
}

// MARK: - The note around a table

extension NoteTextView {

    /// A 2 x 2 table on a line of its own, with the caret in its first cell, as in Notes.
    @objc func insertTable(_ sender: Any?) {
        let table = TableAttachment(rows: 2, columns: 2)
        let text = string as NSString
        let at = selectedRange()
        let plain: [NSAttributedString.Key: Any] = [
            .font: baseFont, .foregroundColor: NSColor.textColor, .paragraphStyle: NSParagraphStyle.default,
        ]
        let insertion = NSMutableAttributedString()
        if at.location > 0, text.character(at: at.location - 1) != 0x0A {
            insertion.append(NSAttributedString(string: "\n", attributes: typingAttributes))
        }
        let tableCharacter = NSMutableAttributedString(attachment: table)
        tableCharacter.addAttributes(plain, range: NSRange(location: 0, length: tableCharacter.length))
        insertion.append(tableCharacter)
        insertion.append(NSAttributedString(string: "\n", attributes: plain))
        insertText(insertion, replacementRange: at)
        typingAttributes = plain
        undoManager?.setActionName("Insert Table")
        focusFirstCell(of: table, attempts: 20)
    }

    /// The table's view only exists once TextKit has laid out the part of the note it is in,
    /// which it does lazily, and it can exist a moment before it is in the window. Focusing a
    /// cell before then fails, and leaves nothing focused at all: typing then goes to the
    /// window, and Tab walks the toolbar. So lay it out now, and wait until it is on screen.
    private func focusFirstCell(of table: TableAttachment, attempts: Int) {
        textLayoutManager?.textViewportLayoutController.layoutViewport()
        if let view = table.view, view.window != nil {
            view.layoutSubtreeIfNeeded()
            view.focus(row: 0, column: 0)
        } else if attempts > 0 {
            Task { @MainActor [weak self] in self?.focusFirstCell(of: table, attempts: attempts - 1) }
        }
    }

    func range(of table: TableAttachment) -> NSRange? {
        guard let storage = textStorage else { return nil }
        var found: NSRange?
        storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length)) { value, range, stop in
            if value as? TableAttachment === table {
                found = range
                stop.pointee = true
            }
        }
        return found
    }

    /// A table's height changed: have TextKit measure it again and move the text below it.
    func tableDidChangeSize(_ table: TableAttachment) {
        guard let range = range(of: table),
              let layout = textLayoutManager, let content = layout.textContentManager,
              let start = content.location(content.documentRange.location, offsetBy: range.location),
              let end = content.location(start, offsetBy: range.length),
              let textRange = NSTextRange(location: start, end: end)
        else { return }
        layout.invalidateLayout(for: textRange)
        layout.textViewportLayoutController.layoutViewport()
        needsDisplay = true
    }

    /// Replaces a table with [replacement] (nothing, to delete it) as one undoable step, and
    /// puts the caret just after.
    func replaceTable(_ table: TableAttachment, with replacement: NSAttributedString, named name: String) {
        guard let range = range(of: table), shouldChangeText(in: range, replacementString: replacement.string) else { return }
        textStorage?.replaceCharacters(in: range, with: replacement)
        didChangeText()
        undoManager?.setActionName(name)
        window?.makeFirstResponder(self)
        setSelectedRange(NSRange(location: range.location + replacement.length, length: 0))
    }

    /// Leaves a table by the arrow keys: onto the line below it, or the end of the line above.
    ///
    /// Not just beside it: a table is one character on a line of its own, and a caret left on
    /// that line types into the table's own paragraph. It looks as if it were below the table,
    /// and is not. At the very start of a note there is no line above, so it stays put.
    func placeCaret(beside table: TableAttachment, after: Bool) {
        guard let range = range(of: table), let storage = textStorage else { return }
        let text = storage.string as NSString
        var target: Int
        if after {
            target = range.location + range.length
            if target < text.length, text.character(at: target) == 0x0A { target += 1 }
        } else {
            guard range.location > 0 else { return }
            target = range.location - 1
        }
        window?.makeFirstResponder(self)
        setSelectedRange(NSRange(location: target, length: 0))
    }

    /// A caret that lands beside a table, by the arrow keys or a click in the table's margin,
    /// goes into the table instead: its first cell when moving forward, its last when moving
    /// back. Only the note does this, not the cells inside a table.
    func enterTableIfCaretIsBesideOne(movingForward: Bool) {
        guard !(self is TableCellTextView), selectedRanges.count == 1, selectedRange().length == 0,
              let storage = textStorage
        else { return }
        let at = selectedRange().location
        let before = at > 0 ? storage.attribute(.attachment, at: at - 1, effectiveRange: nil) as? TableAttachment : nil
        let after = at < storage.length ? storage.attribute(.attachment, at: at, effectiveRange: nil) as? TableAttachment : nil
        guard let table = after ?? before, let view = table.view, view.window != nil else { return }
        if movingForward {
            view.focus(row: 0, column: 0, atEnd: false)
        } else {
            view.focus(row: table.rowCount - 1, column: table.columnCount - 1)
        }
    }
}
