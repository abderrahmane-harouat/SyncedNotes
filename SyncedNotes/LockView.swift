import SwiftUI

/// Asks for the passphrase: to choose one the first time, and to unlock every time after.
struct LockView: View {
    let model: NotesModel
    let firstRun: Bool

    @State private var passphrase = ""
    @State private var repeated = ""
    @State private var message: String?
    @FocusState private var focused: Field?

    enum Field { case passphrase, repeated }

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "lock.fill")
                .font(.system(size: 34))
                .foregroundStyle(.secondary)
            Text(firstRun ? "Choose a passphrase" : "Enter your passphrase")
                .font(.title2.bold())
            if firstRun {
                Text("It locks every note. There is no way to recover it, and no copy is kept anywhere, so choose one you will remember.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 340)
            }
            PassphraseField("Passphrase", text: $passphrase, focus: $focused, equals: .passphrase) {
                guard firstRun, repeated.isEmpty else { return submit() }
                // After SwiftUI has finished with the Return; set during it, the change is
                // undone as the field ends editing.
                Task { focused = .repeated }
            }
            if firstRun {
                PassphraseField("The same passphrase again", text: $repeated, focus: $focused, equals: .repeated, onSubmit: submit)
            }
            if let message {
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 340)
            }
            Button(firstRun ? "Create" : "Unlock", action: submit)
                .keyboardShortcut(.defaultAction)
                .disabled(passphrase.isEmpty)
        }
        .textFieldStyle(.roundedBorder)
        .frame(width: 340)
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Ready to type into straight away, without a click first. Asked for once the window is
        // up: a request made before it becomes active is ignored, and typing goes nowhere.
        // (`defaultFocus` is not used: it pulls focus back to this field whenever focus briefly
        // leaves, which undid moving on to the second field with Return.)
        .task {
            try? await Task.sleep(for: .milliseconds(150))
            if focused == nil { focused = .passphrase }
        }
    }

    private func submit() {
        guard !passphrase.isEmpty else { return }
        if firstRun {
            do {
                try model.create(passphrase: passphrase, repeated: repeated)
            } catch {
                message = error.localizedDescription
                repeated = ""
                focused = passphrase.count < NotesModel.shortestPassphrase ? .passphrase : .repeated
            }
        } else if !model.unlock(passphrase: passphrase) {
            message = model.problem ?? "That is not the passphrase."
            model.problem = nil
            passphrase = ""
            focused = .passphrase
        }
    }
}
