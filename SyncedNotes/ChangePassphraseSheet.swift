import SwiftUI

/// Asks for the passphrase in use and a new one, changes it, and shows that it worked. Shown
/// over the notes (File menu) or over Settings, and closes itself either way.
struct ChangePassphraseSheet: View {
    let model: NotesModel
    @Environment(\.dismiss) private var dismiss

    private enum Stage { case asking, working, done }
    private enum Field { case current, new, repeated }

    @State private var stage = Stage.asking
    @State private var current = ""
    @State private var new = ""
    @State private var repeated = ""
    @State private var message: String?
    @FocusState private var focused: Field?

    var body: some View {
        Group {
            switch stage {
            case .asking, .working: form
            case .done: success
            }
        }
        .padding(24)
        .frame(width: 400)
        .animation(.smooth(duration: 0.3), value: stage)
        // While the notes are being locked again, the sheet stays: closing it then would leave
        // the change running unseen.
        .interactiveDismissDisabled(stage == .working)
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Change Passphrase")
                .font(.title2.bold())
            Text("Every note and picture is locked again with the new passphrase. The old one will no longer open anything on this Mac.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            PassphraseField("Current passphrase", text: $current, focus: $focused, equals: .current) { focused = .new }
            PassphraseField("New passphrase", text: $new, focus: $focused, equals: .new) { focused = .repeated }
            PassphraseField("The new passphrase again", text: $repeated, focus: $focused, equals: .repeated, onSubmit: change)
            if let message {
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                if stage == .working {
                    ProgressView().controlSize(.small)
                    Text("Locking every note again…")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Change Passphrase", action: change)
                    .keyboardShortcut(.defaultAction)
                    .disabled(current.isEmpty || new.isEmpty || repeated.isEmpty)
            }
            .disabled(stage == .working)
        }
        .textFieldStyle(.roundedBorder)
        .disabled(stage == .working)
        .task {
            try? await Task.sleep(for: .milliseconds(150))
            if focused == nil { focused = .current }
        }
    }

    private var success: some View {
        VStack(spacing: 14) {
            SuccessCheck()
                .frame(width: 76, height: 76)
                .padding(.top, 6)
            Text("Passphrase changed")
                .font(.title2.bold())
            Text("Use the new one from now on. Backups made before still open only with the old passphrase, so export a new one.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button("Export Backup…") {
                    dismiss()
                    // Once the sheet has gone: a save panel over a closing sheet is refused.
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(350))
                        model.exportBackup()
                    }
                }
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
    }

    private func change() {
        guard stage == .asking, !current.isEmpty, !new.isEmpty, !repeated.isEmpty else { return }
        message = nil
        stage = .working
        Task { @MainActor in
            do {
                try await model.changePassphrase(current: current, new: new, repeated: repeated)
                // Not kept a moment longer than needed.
                (current, new, repeated) = ("", "", "")
                stage = .done
            } catch {
                message = error.localizedDescription
                stage = .asking
                if message == "That is not your current passphrase." {
                    current = ""
                    focused = .current
                } else {
                    repeated = ""
                    focused = .new
                }
            }
        }
    }
}

/// A green circle that draws itself round, then a tick drawn stroke by stroke, with a small
/// bounce as it lands.
private struct SuccessCheck: View {
    @State private var ring: CGFloat = 0
    @State private var tick: CGFloat = 0
    @State private var landed = false

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.green.opacity(0.14))
                .scaleEffect(landed ? 1 : 0.6)
            Circle()
                .trim(from: 0, to: ring)
                .stroke(Color.green, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Tick()
                .trim(from: 0, to: tick)
                .stroke(Color.green, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                .padding(22)
        }
        .scaleEffect(landed ? 1 : 0.85)
        .accessibilityElement()
        .accessibilityLabel("Passphrase changed")
        .onAppear {
            withAnimation(.easeOut(duration: 0.45)) { ring = 1 }
            withAnimation(.easeOut(duration: 0.3).delay(0.35)) { tick = 1 }
            withAnimation(.spring(response: 0.35, dampingFraction: 0.5).delay(0.55)) { landed = true }
        }
    }
}

private struct Tick: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.midY + rect.height * 0.02))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.36, y: rect.maxY - rect.height * 0.12))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + rect.height * 0.12))
        return path
    }
}
