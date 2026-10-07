import SwiftUI
import SyncedNotesCore

/// The sidebar: All Notes, the notes in no folder, the folders, and the Trash.
///
/// The folder tree is drawn as a flat list, each row indented by its depth with its own
/// disclosure arrow, rather than with SwiftUI's outline: every row is then an ordinary list
/// row, which the list selects, and which takes drops and a context menu the same way.
struct FolderSidebar: View {
    @Bindable var model: NotesModel
    /// A folder waiting for its deletion to be confirmed.
    @State private var confirmDelete: Data?

    var body: some View {
        List(selection: Binding(get: { model.place }, set: { if let place = $0 { model.show(place) } })) {
            PlaceRow(title: "All Notes", systemImage: "tray.full", count: model.noteCounts.values.reduce(0, +))
                .tag(NotesModel.Place.allNotes)
            PlaceRow(title: "Notes", systemImage: "note.text", count: model.noteCounts[nil] ?? 0)
                .dropDestination(for: String.self) { items, _ in FolderDrop.perform(items, into: nil, model: model) }
                .help("Notes in no folder")
                .tag(NotesModel.Place.noFolder)
            Section("Folders") {
                ForEach(visibleFolders, id: \.id) { folder in
                    FolderRow(model: model, folder: folder, confirmDelete: $confirmDelete)
                        .tag(NotesModel.Place.folder(folder.id))
                }
            }
            Section {
                PlaceRow(title: "Trash", systemImage: model.trashCount > 0 ? "trash.fill" : "trash", count: model.trashCount)
                    .help("Deleted notes stay here for 30 days, and can be restored")
                    .tag(NotesModel.Place.trash)
            }
        }
        .onDeleteCommand {
            if case .folder(let id) = model.place { confirmDelete = id }
        }
        // Backups and new folders in plain sight, under the list, not only in the menus.
        .safeAreaInset(edge: .bottom) {
            HStack {
                // In the label colour, as Backup beside it is. A borderless button draws its
                // label in a muted grey, which made it look unavailable, and ignores a colour
                // given to it; a plain one draws the label as it is given.
                Button { model.newFolder() } label: {
                    Label("New Folder", systemImage: "folder.badge.plus")
                        .foregroundStyle(.primary)
                        .contentShape(Rectangle())
                }
                    .buttonStyle(.plain)
                    .lineLimit(1)
                    .fixedSize()
                    .help("New Folder (⇧⌘N)")
                Spacer()
                Menu {
                    Button("Export Backup…", systemImage: "square.and.arrow.up", action: model.exportBackup)
                    Button("Import Backup…", systemImage: "square.and.arrow.down", action: model.importBackup)
                } label: {
                    Label("Backup", systemImage: "externaldrive")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .help("Export or import an encrypted backup of every note")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .confirmationDialog(
            deleteQuestion,
            isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })
        ) {
            Button("Delete Folder", role: .destructive) {
                if let id = confirmDelete { model.deleteFolder(id) }
                confirmDelete = nil
            }
        } message: {
            Text(deleteMessage)
        }
    }

    /// The folders whose parents are all open, in tree order.
    private var visibleFolders: [NoteStore.Folder] {
        model.folders.filter { folder in
            var parent = folder.parent
            while let id = parent {
                guard model.expandedFolders.contains(id) else { return false }
                parent = model.folders.first { $0.id == id }?.parent
            }
            return true
        }
    }

    private var deleteQuestion: String {
        "Delete “\(model.name(of: confirmDelete))”?"
    }

    private var deleteMessage: String {
        guard let id = confirmDelete else { return "" }
        let count = model.noteCount(deletingFolder: id)
        let inside = model.folders.contains { $0.parent == id } ? " and the folders inside it" : ""
        guard count > 0 else { return "The folder\(inside) will be deleted. There are no notes in it." }
        return "The folder\(inside) will be deleted, and \(count == 1 ? "the note" : "its \(count) notes") moved to the Trash, where \(count == 1 ? "it" : "they") can be restored for 30 days."
    }
}

/// What a note or a folder is while it is dragged: its ID, as text, with what it is in front.
///
/// Plain text rather than a type of the app's own, which would need declaring to the system;
/// dropped anywhere else it is only an ID, with nothing of the note in it.
enum FolderDrop {
    static func note(_ id: Data) -> String { "syncednotes-note:" + id.hexString }
    /// Several notes dragged together, a line each.
    static func notes(_ ids: Set<Data>) -> String { ids.map(note).sorted().joined(separator: "\n") }
    static func folder(_ id: Data) -> String { "syncednotes-folder:" + id.hexString }

    /// Moves the dragged notes and folders into [folder], or to the top with nil.
    @discardableResult
    static func perform(_ items: [String], into folder: Data?, model: NotesModel) -> Bool {
        var moved = false
        var notes: Set<Data> = []
        for item in items.flatMap({ $0.split(separator: "\n").map(String.init) }) {
            if let id = id(in: item, after: "syncednotes-note:") {
                notes.insert(id)
                moved = true
            } else if let id = id(in: item, after: "syncednotes-folder:") {
                model.moveFolder(id, into: folder)
                moved = true
            }
        }
        if !notes.isEmpty { model.moveNotes(notes, to: folder) }
        return moved
    }

    private static func id(in item: String, after prefix: String) -> Data? {
        guard item.hasPrefix(prefix) else { return nil }
        let hex = Array(item.dropFirst(prefix.count))
        guard hex.count == 32 else { return nil }
        let bytes = stride(from: 0, to: 32, by: 2).compactMap { UInt8(String(hex[$0..<$0 + 2]), radix: 16) }
        return bytes.count == 16 ? Data(bytes) : nil
    }
}

/// A fixed place in the sidebar, with how many notes it holds.
private struct PlaceRow: View {
    let title: String
    let systemImage: String
    let count: Int

    var body: some View {
        Label(title, systemImage: systemImage)
            .badge(count)
    }
}

/// A folder in the sidebar: indented by its depth, open or closed, renamed in place.
private struct FolderRow: View {
    @Bindable var model: NotesModel
    let folder: NoteStore.Folder
    @Binding var confirmDelete: Data?
    @State private var draft = ""

    private var hasFolders: Bool { model.folders.contains { $0.parent == folder.id } }
    private var isOpen: Bool { model.expandedFolders.contains(folder.id) }
    private var isRenaming: Bool { model.renamingFolder == folder.id }

    var body: some View {
        HStack(spacing: 2) {
            Button {
                if isOpen { model.expandedFolders.remove(folder.id) } else { model.expandedFolders.insert(folder.id) }
            } label: {
                // A target the size of the row's height, not of the arrow: the arrow alone was
                // so small that clicks beside it were missed and looked like the app not working.
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.secondary)
                    .rotationEffect(.degrees(isOpen ? 90 : 0))
                    .animation(.snappy(duration: 0.15), value: isOpen)
                    .frame(width: 24, height: 24)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .opacity(hasFolders ? 1 : 0)
            .disabled(!hasFolders)
            .accessibilityLabel(isOpen ? "Close Folder" : "Open Folder")
            if isRenaming {
                Label {
                    // The app's own field: a folder's name is note content (Privacy.md).
                    PlainTextField(
                        placeholder: "Folder Name", text: $draft, wantsFocus: true, bordered: false,
                        onFocusChange: { has in
                            // Clicking elsewhere keeps what was typed, as in Finder.
                            if !has, isRenaming { model.renameFolder(folder.id, to: draft) }
                        },
                        onSubmit: { model.renameFolder(folder.id, to: draft) },
                        onCancel: { model.renamingFolder = nil }
                    )
                    .onAppear { draft = folder.name }
                } icon: {
                    Image(systemName: "folder")
                }
            } else {
                Label(folder.name, systemImage: "folder")
                    .badge(model.noteCounts[folder.id] ?? 0)
            }
        }
        .padding(.leading, CGFloat(model.depth(of: folder)) * 16 - 6)
        .draggable(FolderDrop.folder(folder.id))
        .dropDestination(for: String.self) { items, _ in
            FolderDrop.perform(items, into: folder.id, model: model)
        }
        .contextMenu {
            Button("New Folder Inside", systemImage: "folder.badge.plus") { model.newFolder(in: folder.id) }
            Button("Rename", systemImage: "pencil") { model.renamingFolder = folder.id }
            Menu("Move To") {
                Button("Top Level") { model.moveFolder(folder.id, into: nil) }
                    .disabled(folder.parent == nil)
                Divider()
                FolderChoices(model: model, excluding: folder.id, current: folder.parent) { model.moveFolder(folder.id, into: $0) }
            }
            Divider()
            Button("Delete Folder…", systemImage: "trash", role: .destructive) { confirmDelete = folder.id }
        }
    }
}

/// Every folder as a menu item, indented to show the tree, for "Move To" menus. [excluding]
/// leaves out a folder and everything inside it: a folder cannot move into itself.
struct FolderChoices: View {
    let model: NotesModel
    var excluding: Data? = nil
    /// Where the thing being moved is now, shown checked and not offered.
    let current: Data?
    let choose: (Data) -> Void

    var body: some View {
        ForEach(choices, id: \.id) { folder in
            let indent = String(repeating: "    ", count: model.depth(of: folder))
            Button(indent + folder.name) { choose(folder.id) }
                .disabled(folder.id == current)
        }
    }

    private var choices: [NoteStore.Folder] {
        guard let excluding else { return model.folders }
        return model.folders.filter { folder in
            var step: Data? = folder.id
            while let id = step {
                if id == excluding { return false }
                step = model.folders.first { $0.id == id }?.parent
            }
            return true
        }
    }
}
