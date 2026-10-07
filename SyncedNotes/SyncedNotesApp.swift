import SwiftUI
import SyncedNotesCore

@main
struct SyncedNotesApp: App {
    @State private var model = NotesModel()

    init() {
        // Before any window or menu exists: see KeepOnThisMac.
        KeepOnThisMac.install()
    }

    var body: some Scene {
        // One window: the same note open in two windows would each save over the other.
        Window("SyncedNotes", id: "notes") {
            RootView(model: model)
        }
        .commands {
            NotesMenu(model: model)
            FormatMenu()
        }
        // Where Mac apps keep such things: the app menu's Settings…, ⌘,.
        Settings {
            SettingsView(model: model)
        }
    }
}

/// The lock screen until the notes are unlocked, then the notes.
struct RootView: View {
    let model: NotesModel

    var body: some View {
        switch model.phase {
        case .locked(let firstRun):
            LockView(model: model, firstRun: firstRun)
                .frame(minWidth: 480, minHeight: 360)
        case .unlocked:
            NotesView(model: model)
        }
    }
}

/// Folders, the notes of the one chosen, and the note being written: three columns, as in
/// Apple Notes.
struct NotesView: View {
    @Bindable var model: NotesModel
    @State private var formatting = FormattingModel()
    /// Notes waiting for "Delete Permanently" to be confirmed.
    @State private var confirmDelete: Set<Data>?
    @State private var confirmEmptyTrash = false

    var body: some View {
        NavigationSplitView {
            FolderSidebar(model: model)
                .navigationSplitViewColumnWidth(min: 180, ideal: 210)
        } content: {
            // Several notes are selected with ⌘-click and ⇧-click, to move or delete together.
            List(selection: Binding(get: { model.selection }, set: { model.select(notes: $0) })) {
                ForEach(model.summaries) { note in
                    NoteRow(model: model, note: note, confirmDelete: $confirmDelete)
                        .tag(note.id)
                }
            }
            .onDeleteCommand {
                let ids = model.selection
                guard !ids.isEmpty else { return }
                if model.showingTrash { confirmDelete = ids } else { model.moveToTrash(ids) }
            }
            .navigationTitle(listTitle)
            .navigationSplitViewColumnWidth(min: 220, ideal: 260)
            .safeAreaInset(edge: .top) {
                VStack(spacing: 0) {
                    // The app's own search field, not SwiftUI's `.searchable`: what is searched
                    // for is note content, and SwiftUI's field offers it to text services and
                    // Universal Clipboard (Privacy.md).
                    PlainSearchField(placeholder: model.showingTrash ? "Search the Trash" : "Search \(listTitle)", text: $model.search)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                    if model.showingTrash {
                        TrashHeader(model: model, confirmEmptyTrash: $confirmEmptyTrash)
                    }
                }
            }
            .overlay {
                if model.summaries.isEmpty {
                    Text(emptyListText)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding()
                }
            }
            .toolbar {
                Button("Lock Notes", systemImage: "lock", action: model.lock)
                    .help("Lock Notes (⌃⌘L)")
                // In plain sight beside Lock, not only in menus: from Settings and the File menu
                // alone, the user could not find it.
                Button("Change Passphrase", systemImage: "key") { model.changingPassphrase = true }
                    .help("Change Passphrase…")
                Button("New Note", systemImage: "square.and.pencil", action: model.newNote)
                    .help("New Note (⌘N)")
            }
        } detail: {
            if let document = model.document {
                VStack(spacing: 0) {
                    if document.inTrash {
                        TrashedNoteBanner(model: model, id: document.id, confirmDelete: $confirmDelete)
                    }
                    EditorView(formatting: formatting, model: model, document: document)
                }
                .focusedSceneValue(\.formatting, formatting)
                .toolbar { FormatToolbar(formatting: formatting) }
            } else if model.selectedNotes.count > 1 {
                SelectedNotesPane(model: model, confirmDelete: $confirmDelete)
            } else {
                Text(model.summaries.isEmpty ? "" : (model.showingTrash ? "Choose a note to restore it or delete it for good." : "Choose a note."))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 900, minHeight: 420)
        .onDisappear { model.saveNow() }
        .confirmationDialog(
            (confirmDelete?.count ?? 0) > 1 ? "Delete these \(confirmDelete?.count ?? 0) notes permanently?" : "Delete this note permanently?",
            isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })
        ) {
            Button("Delete Permanently", role: .destructive) {
                if let ids = confirmDelete { model.deletePermanently(ids) }
                confirmDelete = nil
            }
        } message: {
            Text((confirmDelete?.count ?? 0) > 1
                 ? "They cannot be restored afterwards, except from a backup that has them."
                 : "It cannot be restored afterwards, except from a backup that has it.")
        }
        .sheet(isPresented: $model.changingPassphrase) {
            ChangePassphraseSheet(model: model)
        }
        .confirmationDialog("Empty the Trash?", isPresented: $confirmEmptyTrash) {
            Button("Empty Trash", role: .destructive, action: model.emptyTrash)
        } message: {
            Text("The \(model.trashCount) \(model.trashCount == 1 ? "note" : "notes") in it will be deleted for good, and cannot be restored afterwards, except from a backup that has them.")
        }
        .alert("Something went wrong", isPresented: Binding(get: { model.problem != nil }, set: { if !$0 { model.problem = nil } })) {
            Button("OK") { model.problem = nil }
        } message: {
            Text(model.problem ?? "")
        }
        .alert("Done", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
            Button("OK") { model.notice = nil }
        } message: {
            Text(model.notice ?? "")
        }
        .sheet(isPresented: Binding(get: { model.importWaitingForPassphrase != nil }, set: { if !$0 { model.importWaitingForPassphrase = nil } })) {
            BackupPassphraseSheet(model: model)
        }
    }

    private var listTitle: String {
        switch model.place {
        case .allNotes: "All Notes"
        case .noFolder: "Notes"
        case .folder(let id): model.name(of: id)
        case .trash: "Trash"
        }
    }

    private var emptyListText: String {
        if !model.search.isEmpty { return "No notes match." }
        return switch model.place {
        case .trash: "The Trash is empty."
        case .folder: "No notes in this folder. ⌘N makes one here, or drag notes onto it."
        default: "No notes yet. ⌘N makes one."
        }
    }
}

/// A note in the list: its name and date, where it is when the list shows every folder, and
/// what can be done with it.
private struct NoteRow: View {
    let model: NotesModel
    let note: NoteStore.Summary
    @Binding var confirmDelete: Set<Data>?

    /// What the menu and a drag act on: the whole selection when this note is part of it, as
    /// in Finder, or this note alone.
    private var targets: Set<Data> {
        model.selection.contains(note.id) ? model.selection : [note.id]
    }

    private var many: String { targets.count > 1 ? "\(targets.count) Notes" : "" }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(note.title.isEmpty ? "New Note" : note.title)
                .lineLimit(1)
                .foregroundStyle(note.title.isEmpty ? .secondary : .primary)
            HStack(spacing: 6) {
                if let trashed = note.trashed {
                    Text("Deleted \(trashed, format: .dateTime.day().month().hour().minute())")
                } else {
                    Text(note.updated, format: .dateTime.day().month().hour().minute())
                    if model.place == .allNotes, let folder = note.folder {
                        Label(model.name(of: folder), systemImage: "folder")
                            .labelStyle(.titleAndIcon)
                            .lineLimit(1)
                    }
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .draggable(FolderDrop.notes(targets))
        .contextMenu {
            if model.showingTrash {
                Button(targets.count > 1 ? "Restore \(many)" : "Restore", systemImage: "arrow.uturn.backward") { model.restoreFromTrash(targets) }
                Button(targets.count > 1 ? "Delete \(many) Permanently…" : "Delete Permanently…", systemImage: "trash.slash", role: .destructive) { confirmDelete = targets }
            } else {
                MoveNotesMenu(model: model, ids: targets, title: targets.count > 1 ? "Move \(many) To" : "Move To")
                Divider()
                Button(targets.count > 1 ? "Move \(many) to Trash" : "Move to Trash", systemImage: "trash", role: .destructive) { model.moveToTrash(targets) }
            }
        }
    }
}

/// Where notes can go: Notes, out of every folder, or any folder. Where all of them already
/// are is not offered.
struct MoveNotesMenu: View {
    let model: NotesModel
    let ids: Set<Data>
    var title = "Move To"

    var body: some View {
        let folders = Set(model.summaries.filter { ids.contains($0.id) }.map(\.folder))
        let current = folders.count == 1 ? folders.first! : nil
        Menu(title) {
            Button("Notes") { model.moveNotes(ids, to: nil) }
                .disabled(folders == [nil])
            if !model.folders.isEmpty { Divider() }
            FolderChoices(model: model, current: current) { model.moveNotes(ids, to: $0) }
        }
    }
}

/// In place of the note, while several are selected: what can be done with all of them.
private struct SelectedNotesPane: View {
    let model: NotesModel
    @Binding var confirmDelete: Set<Data>?

    var body: some View {
        let ids = model.selectedNotes
        VStack(spacing: 14) {
            Image(systemName: "square.stack.3d.up")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("\(ids.count) notes selected")
                .font(.title2.bold())
            Text(model.showingTrash ? "Restore them, or delete them for good." : "Drag them onto a folder, or:")
                .foregroundStyle(.secondary)
            HStack {
                if model.showingTrash {
                    Button("Restore", systemImage: "arrow.uturn.backward") { model.restoreFromTrash(ids) }
                    Button("Delete Permanently…", systemImage: "trash.slash", role: .destructive) { confirmDelete = ids }
                } else {
                    MoveNotesMenu(model: model, ids: ids)
                        .fixedSize()
                    Button("Move to Trash", systemImage: "trash", role: .destructive) { model.moveToTrash(ids) }
                }
            }
            .controlSize(.large)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Above the list while it shows the Trash: Empty Trash, and how long notes stay.
struct TrashHeader: View {
    let model: NotesModel
    @Binding var confirmEmptyTrash: Bool

    // The words on a line of their own, as wide as the column. Beside the button, and told to
    // take the height they needed, they were measured one word to a line, and that height
    // stretched the whole window past its edges: the sidebar and the list slid up under the
    // toolbar, out of reach.
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Notes are deleted for good 30 days after they were moved here.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            Button("Empty Trash", role: .destructive) { confirmEmptyTrash = true }
                .buttonStyle(.borderless)
                .disabled(model.trashCount == 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .padding(.bottom, 6)
    }
}

/// Above a note opened from the Trash: it is shown, not edited, until it is restored.
struct TrashedNoteBanner: View {
    let model: NotesModel
    let id: Data
    @Binding var confirmDelete: Set<Data>?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "trash")
                .foregroundStyle(.secondary)
            Text("This note is in the Trash.")
            Spacer()
            Button("Restore", systemImage: "arrow.uturn.backward") { model.restoreFromTrash(id) }
            Button("Delete Permanently…", role: .destructive) { confirmDelete = [id] }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.quaternary.opacity(0.5))
    }
}

/// The formatting tools above the note.
struct FormatToolbar: ToolbarContent {
    let formatting: FormattingModel

    var body: some ToolbarContent {
        ToolbarItemGroup {
            Menu("Text Style", systemImage: "textformat.size") {
                FormatMenuItem(.title, formatting.current)
                FormatMenuItem(.heading, formatting.current)
                FormatMenuItem(.subheading, formatting.current)
                FormatMenuItem(.body, formatting.current)
                Divider()
                FormatMenuItem(.bigger, formatting.current)
                FormatMenuItem(.smaller, formatting.current)
            }
            .help("Text Style and Size")
            // The lists in use, one click each. The dashed list is in the Format menu, and
            // typing "- " starts one.
            ControlGroup {
                FormatToggle(.bulletedList, formatting.current)
                FormatToggle(.numberedList, formatting.current)
                FormatToggle(.checklist, formatting.current)
            }
            Button(FormatCommand.insertTable.title, systemImage: FormatCommand.insertTable.symbol ?? "tablecells", action: FormatCommand.insertTable.send)
                .help("Table")
            // The styles reached for mid-sentence. Italic, Underline and Strikethrough are in
            // the Format menu, with their shortcuts.
            FormatToggle(.bold, formatting.current)
            ControlGroup {
                FormatToggle(.highlight, formatting.current)
                HighlightColorMenu(formatting: formatting.current)
            }
            TextColorMenu(formatting: formatting.current)
        }
    }
}

/// New Note, the backups, and Lock, in the File menu.
struct NotesMenu: Commands {
    let model: NotesModel

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("New Note", action: model.newNote)
                .keyboardShortcut("n")
                .disabled(model.phase != .unlocked)
            Button("New Folder") { model.newFolder() }
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .disabled(model.phase != .unlocked)
            Divider()
            Button("Export Backup…", action: model.exportBackup)
                .keyboardShortcut("e", modifiers: [.command, .shift])
                .disabled(model.phase != .unlocked)
            Button("Import Backup…", action: model.importBackup)
                .keyboardShortcut("i", modifiers: [.command, .shift])
                .disabled(model.phase != .unlocked)
            Divider()
            Button("Change Passphrase…") { model.changingPassphrase = true }
                .disabled(model.phase != .unlocked)
            Button("Lock Notes", action: model.lock)
                .keyboardShortcut("l", modifiers: [.command, .control])
                .disabled(model.phase != .unlocked)
        }
    }
}

/// The Format menu, with checkmarks for whatever is on in the focused window.
struct FormatMenu: Commands {
    @FocusedValue(\.formatting) private var model

    var body: some Commands {
        let now = model?.current
        CommandMenu("Format") {
            FormatMenuItem(.title, now).keyboardShortcut("1", modifiers: [.command, .option])
            FormatMenuItem(.heading, now).keyboardShortcut("2", modifiers: [.command, .option])
            FormatMenuItem(.subheading, now).keyboardShortcut("3", modifiers: [.command, .option])
            FormatMenuItem(.body, now).keyboardShortcut("0", modifiers: [.command, .option])
            Divider()
            FormatMenuItem(.bulletedList, now).keyboardShortcut("7", modifiers: [.command, .shift])
            FormatMenuItem(.dashedList, now).keyboardShortcut("8", modifiers: [.command, .shift])
            FormatMenuItem(.numberedList, now).keyboardShortcut("9", modifiers: [.command, .shift])
            FormatMenuItem(.checklist, now).keyboardShortcut("l", modifiers: [.command, .shift])
            FormatMenuItem(.markChecked, now).keyboardShortcut("u", modifiers: [.command, .shift])
            Divider()
            Menu("Table") {
                FormatMenuItem(.insertTable, now).keyboardShortcut("t", modifiers: [.command, .option])
                Divider()
                FormatMenuItem(.addRowAbove, now)
                FormatMenuItem(.addRowBelow, now)
                FormatMenuItem(.addColumnBefore, now)
                FormatMenuItem(.addColumnAfter, now)
                Divider()
                FormatMenuItem(.deleteTableRow, now)
                FormatMenuItem(.deleteTableColumn, now)
                Divider()
                FormatMenuItem(.convertTableToText, now)
                FormatMenuItem(.deleteTable, now)
            }
            FormatMenuItem(.blockQuote, now).keyboardShortcut("'")
            FormatMenuItem(.codeBlock, now)
            FormatMenuItem(.divider, now)
            Divider()
            FormatMenuItem(.bold, now).keyboardShortcut("b")
            FormatMenuItem(.italic, now).keyboardShortcut("i")
            FormatMenuItem(.underline, now).keyboardShortcut("u")
            FormatMenuItem(.strikethrough, now).keyboardShortcut("x", modifiers: [.command, .shift])
            FormatMenuItem(.highlight, now).keyboardShortcut("h", modifiers: [.command, .shift])
            Menu("Highlight Color") {
                HighlightColorItems(formatting: now)
            }
            FormatMenuItem(.inlineCode, now)
            Divider()
            Menu("Text Color") {
                TextColorItems(formatting: now)
            }
            Divider()
            FormatMenuItem(.bigger, now).keyboardShortcut("+")
            FormatMenuItem(.smaller, now).keyboardShortcut("-")
            Divider()
            Menu("Transformations") {
                FormatMenuItem(.uppercase, now)
                FormatMenuItem(.lowercase, now)
                FormatMenuItem(.capitalize, now)
            }
            Divider()
            FormatMenuItem(.clearFormatting, now)
            Divider()
            Menu("Alignment") {
                FormatMenuItem(.alignLeft, now).keyboardShortcut("{")
                FormatMenuItem(.alignCenter, now).keyboardShortcut("|")
                FormatMenuItem(.justify, now)
                FormatMenuItem(.alignRight, now).keyboardShortcut("}")
            }
            Menu("Writing Direction") {
                FormatMenuItem(.directionNatural, now)
                FormatMenuItem(.directionLeftToRight, now)
                FormatMenuItem(.directionRightToLeft, now)
            }
            FormatMenuItem(.increaseIndent, now).keyboardShortcut("]")
            FormatMenuItem(.decreaseIndent, now).keyboardShortcut("[")
            Menu("Line Spacing") {
                FormatMenuItem(.lineSpacing(1), now)
                FormatMenuItem(.lineSpacing(1.15), now)
                FormatMenuItem(.lineSpacing(1.5), now)
                FormatMenuItem(.lineSpacing(2), now)
            }
        }
    }
}

extension FocusedValues {
    /// The focused window's formatting, so the menu bar can show checkmarks for it.
    @Entry var formatting: FormattingModel?
}

/// A toolbar button that stays pressed while its style is on at the selection.
///
/// Clicking it only sends the command. The pressed state is never set by the click itself: it
/// follows what the text view then reports, so it cannot disagree with the text.
private struct FormatToggle: View {
    let command: FormatCommand
    let formatting: Formatting
    init(_ command: FormatCommand, _ formatting: Formatting) { (self.command, self.formatting) = (command, formatting) }

    var body: some View {
        Toggle(isOn: Binding(get: { formatting.isOn(command) ?? false }, set: { _ in command.send() })) {
            Label(command.title, systemImage: command.symbol ?? "textformat")
        }
        .toggleStyle(.button)
        .help(command.title)
    }
}

/// A menu item: with a checkmark when its style is on, a plain item for an action.
private struct FormatMenuItem: View {
    let command: FormatCommand
    let formatting: Formatting?
    init(_ command: FormatCommand, _ formatting: Formatting?) { (self.command, self.formatting) = (command, formatting) }

    var body: some View {
        if let isOn = (formatting ?? Formatting()).isOn(command) {
            Toggle(isOn: Binding(get: { formatting != nil && isOn }, set: { _ in command.send() })) { label }
        } else {
            Button(action: command.send) { label }
        }
    }

    @ViewBuilder private var label: some View {
        if let marker = command.listMarker {
            Label { Text(command.title) } icon: { Image(nsImage: ListMarkerImage.make(marker)) }
        } else {
            Text(command.title)
        }
    }
}

/// A list's marker as a menu icon, drawn as `NoteFragment` draws it in the note.
enum ListMarkerImage {
    static func make(_ marker: ListMarker) -> NSImage {
        let font = NSFont.menuFont(ofSize: 0)
        let image = NSImage(size: NSSize(width: 16, height: 16), flipped: false) { bounds in
            let middle = CGPoint(x: bounds.midX, y: bounds.midY)
            switch marker {
            case .disc, .circle, .square:
                let size = (font.xHeight * 0.8).rounded()
                NSBezierPath(ovalIn: CGRect(x: middle.x - size / 2, y: middle.y - size / 2, width: size, height: size)).fill()
            case .text(let string):
                let text = NSAttributedString(string: string, attributes: [.font: NSFont.monospacedDigitSystemFont(ofSize: font.pointSize, weight: .medium)])
                let size = text.size()
                text.draw(at: CGPoint(x: middle.x - size.width / 2, y: middle.y - size.height / 2))
            }
            return true
        }
        // Drawn in black and marked as a template, so the menu colours it like its text,
        // white when highlighted.
        image.isTemplate = true
        return image
    }
}

/// The highlight colours, each with its swatch and a checkmark on the selection's colour.
private struct HighlightColorItems: View {
    let formatting: Formatting?

    var body: some View {
        ForEach(HighlightColor.allCases, id: \.self) { color in
            let command = FormatCommand.highlightColor(color)
            Toggle(isOn: Binding(get: { formatting?.isOn(command) == true }, set: { _ in command.send() })) {
                Label { Text(color.title) } icon: { Image(nsImage: color.swatch) }
            }
        }
        Divider()
        Button(FormatCommand.removeHighlight.title, action: FormatCommand.removeHighlight.send)
    }
}

/// Beside the highlighter button: pick the colour. Its dot shows the colour the button uses.
private struct HighlightColorMenu: View {
    let formatting: Formatting

    var body: some View {
        Menu {
            HighlightColorItems(formatting: formatting)
        } label: {
            Label { Text("Highlight Color") } icon: { Image(nsImage: (formatting.highlight ?? NoteTextView.lastHighlight).swatch) }
        }
        .help("Highlight Color")
    }
}

/// The text colours, each with its swatch and a checkmark on the selection's colour, then the
/// system colour panel for any other.
private struct TextColorItems: View {
    let formatting: Formatting?

    var body: some View {
        FormatMenuItem(.automaticColor, formatting)
        Divider()
        ForEach(TextColor.allCases, id: \.self) { color in
            let command = FormatCommand.color(color)
            Toggle(isOn: Binding(get: { formatting?.isOn(command) == true }, set: { _ in command.send() })) {
                Label { Text(color.title) } icon: { Image(nsImage: color.swatch) }
            }
        }
        Divider()
        FormatMenuItem(.textColor, formatting).keyboardShortcut("c", modifiers: [.command, .shift])
    }
}

/// The text colour, in the toolbar. Its dot shows the selection's colour, when it has one.
private struct TextColorMenu: View {
    let formatting: Formatting

    var body: some View {
        Menu {
            TextColorItems(formatting: formatting)
        } label: {
            if let color = formatting.textColor {
                Label { Text("Text Color") } icon: { Image(nsImage: color.swatch) }
            } else {
                Label("Text Color", systemImage: "paintpalette")
            }
        }
        .help("Text Color")
    }
}

/// Asks for the passphrase of the vault a backup came from, when it is not this one's: a
/// backup made before the passphrase was changed, or on the other device before pairing.
struct BackupPassphraseSheet: View {
    let model: NotesModel
    @State private var passphrase = ""
    @State private var wrong = false
    @FocusState private var focused: Bool?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("This backup was locked with another passphrase")
                .font(.headline)
            Text("Enter the passphrase it was made with. Its notes are then locked again with your current one.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            PassphraseField("The backup's passphrase", text: $passphrase, focus: $focused, equals: true, onSubmit: submit)
                .textFieldStyle(.roundedBorder)
            if wrong {
                Text("That is not this backup's passphrase.")
                    .font(.callout)
                    .foregroundStyle(.red)
            }
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { model.importWaitingForPassphrase = nil }
                    .keyboardShortcut(.cancelAction)
                Button("Import", action: submit)
                    .keyboardShortcut(.defaultAction)
                    .disabled(passphrase.isEmpty)
            }
        }
        .padding(20)
        .frame(width: 380)
        .task {
            try? await Task.sleep(for: .milliseconds(150))
            focused = true
        }
    }

    private func submit() {
        guard !passphrase.isEmpty else { return }
        if !model.importBackup(passphrase: passphrase) {
            wrong = true
            passphrase = ""
            focused = true
        }
    }
}
