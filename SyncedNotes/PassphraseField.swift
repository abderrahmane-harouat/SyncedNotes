import Carbon.HIToolbox
import SwiftUI

/// A passphrase field with an eye button that shows what was typed, and hides it again.
///
/// A `SecureField` does more than hide the text: while it has focus, macOS turns on secure
/// input, so no other app can watch the keystrokes, and its text cannot be copied. The shown
/// field does neither by itself, so it does both by hand: secure input is turned on while it
/// has focus, and it is the app's own `PlainTextField`, which offers no text service, copies
/// for this Mac only, and is not marked as a password field, which would invite the Passwords
/// app and its iCloud Keychain (Privacy.md).
struct PassphraseField<Value: Hashable>: View {
    let title: String
    @Binding var text: String
    let focus: FocusState<Value?>.Binding
    let value: Value
    /// Return: as `.onSubmit` would be, which does not reach the app's own field.
    let onSubmit: () -> Void

    @State private var shown = false
    /// Whether this field turned secure input on, so it turns it off exactly once.
    @State private var holdsSecureInput = false

    init(_ title: String, text: Binding<String>, focus: FocusState<Value?>.Binding, equals value: Value, onSubmit: @escaping () -> Void = {}) {
        self.title = title
        _text = text
        self.focus = focus
        self.value = value
        self.onSubmit = onSubmit
    }

    var body: some View {
        HStack(spacing: 4) {
            if shown {
                // Focus is passed by hand: SwiftUI's focus does not reach an AppKit field.
                PlainTextField(
                    placeholder: title, text: $text, wantsFocus: focus.wrappedValue == value,
                    onFocusChange: { has in
                        if has { focus.wrappedValue = value } else if focus.wrappedValue == value { focus.wrappedValue = nil }
                    },
                    onSubmit: onSubmit
                )
            } else {
                SecureField(title, text: $text)
                    .focused(focus, equals: value)
                    .onSubmit(onSubmit)
            }
            Button {
                let hadFocus = focus.wrappedValue == value
                shown.toggle()
                // The field is a new one now; the caret goes back into it, as it was.
                if hadFocus { Task { @MainActor in focus.wrappedValue = value } }
            } label: {
                Image(systemName: shown ? "eye.slash" : "eye")
                    .foregroundStyle(.secondary)
                    .frame(width: 20)
            }
            .buttonStyle(.borderless)
            .help(shown ? "Hide Passphrase" : "Show Passphrase")
            .accessibilityLabel(shown ? "Hide Passphrase" : "Show Passphrase")
        }
        .onChange(of: shown && focus.wrappedValue == value, initial: true) { _, needed in
            holdSecureInput(needed)
        }
        .onDisappear { holdSecureInput(false) }
    }

    /// Secure input is counted by the system: each turn-on needs its own turn-off, or it stays
    /// on for the whole Mac after the app has gone on.
    private func holdSecureInput(_ needed: Bool) {
        guard needed != holdsSecureInput else { return }
        if needed { EnableSecureEventInput() } else { DisableSecureEventInput() }
        holdsSecureInput = needed
    }
}
