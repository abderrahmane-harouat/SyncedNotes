import SwiftUI

/// Settings (⌘,): where the passphrase is changed. Under Backup it was out of sight, in a menu
/// no one would look in for it.
struct SettingsView: View {
    let model: NotesModel
    @State private var changing = false

    var body: some View {
        Form {
            Section {
                LabeledContent("Passphrase") {
                    Button("Change Passphrase…") { changing = true }
                        .disabled(model.phase != .unlocked)
                }
            } header: {
                Label("Security", systemImage: "lock")
            } footer: {
                Text(model.phase == .unlocked
                     ? "Your passphrase locks every note and picture on this Mac. Changing it locks them all again with the new one."
                     : "Unlock your notes first to change the passphrase.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .fixedSize(horizontal: false, vertical: true)
        // Its own sheet, over this window: the notes window has one of its own for the File
        // menu, and sharing one switch would open both.
        .sheet(isPresented: $changing) {
            ChangePassphraseSheet(model: model)
        }
    }
}
