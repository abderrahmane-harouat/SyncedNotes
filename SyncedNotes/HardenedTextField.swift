import AppKit
import SwiftUI

/// The app's own text fields: search, folder names, a shown passphrase.
///
/// SwiftUI's `TextField` was used before, and its editing component cannot be changed: given
/// another, SwiftUI crashes (tried). So its text services, right-click menu and copying were
/// AppKit's defaults: spelling and predictions on, Look Up, Translate and Services offered,
/// and a copy handed to Universal Clipboard. These fields are AppKit's, with an editing
/// component that refuses all of that, as the note itself does.
final class HardenedFieldEditor: NSTextView {

    // Made with `init(frame:)`, which builds the text system: with no text container, nothing
    // typed had anywhere to go. AppKit may also make one through the other initializer, so
    // both set it up.
    override init(frame: NSRect) {
        super.init(frame: frame)
        setUp()
    }

    override init(frame: NSRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        setUp()
    }

    private func setUp() {
        isFieldEditor = true
        turnOffTextServices()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("Not made from a file.") }

    override func writeSelection(to pasteboard: NSPasteboard, types: [NSPasteboard.PasteboardType]) -> Bool {
        writeSelectionForThisMacOnly(to: pasteboard, types: types)
    }

    override func menu(for event: NSEvent) -> NSMenu? { editingOnlyMenu() }

    /// A force click or three-finger tap would look the word up, Siri knowledge included.
    override func quickLook(with event: NSEvent) {}

    /// The Services menu, and anything else that asks a view for its text to send away.
    override func validRequestor(forSendType sendType: NSPasteboard.PasteboardType?, returnType: NSPasteboard.PasteboardType?) -> Any? { nil }
}

// Each cell sets its editor up again whenever editing starts, which can switch text services
// back on, so they go off once more after it.

final class HardenedTextFieldCell: NSTextFieldCell {
    private lazy var editor = HardenedFieldEditor(frame: .zero)
    override func fieldEditor(for controlView: NSView) -> NSTextView? { editor }
    override func setUpFieldEditorAttributes(_ text: NSText) -> NSText {
        let set = super.setUpFieldEditorAttributes(text)
        (set as? NSTextView)?.turnOffTextServices()
        return set
    }
}

final class HardenedSearchFieldCell: NSSearchFieldCell {
    private lazy var editor = HardenedFieldEditor(frame: .zero)
    override func fieldEditor(for controlView: NSView) -> NSTextView? { editor }
    override func setUpFieldEditorAttributes(_ text: NSText) -> NSText {
        let set = super.setUpFieldEditorAttributes(text)
        (set as? NSTextView)?.turnOffTextServices()
        return set
    }
}

/// An `NSTextField` that edits with `HardenedFieldEditor`, and says when it gains focus.
final class HardenedTextField: NSTextField {
    override class var cellClass: AnyClass? {
        get { HardenedTextFieldCell.self }
        set {}
    }

    var onFocus: (() -> Void)?

    override func becomeFirstResponder() -> Bool {
        let became = super.becomeFirstResponder()
        if became { onFocus?() }
        return became
    }
}

final class HardenedSearchField: NSSearchField {
    override class var cellClass: AnyClass? {
        get { HardenedSearchFieldCell.self }
        set {}
    }
}

/// A one-line text field for SwiftUI, made of `HardenedTextField`.
struct PlainTextField: NSViewRepresentable {
    let placeholder: String
    @Binding var text: String
    /// Whether the field should have the caret; it takes it when this turns true.
    var wantsFocus = false
    var bordered = true
    var onFocusChange: (Bool) -> Void = { _ in }
    var onSubmit: () -> Void = {}
    var onCancel: (() -> Void)?

    func makeNSView(context: Context) -> HardenedTextField {
        let field = HardenedTextField()
        field.placeholderString = placeholder
        field.delegate = context.coordinator
        field.lineBreakMode = .byTruncatingTail
        field.cell?.isScrollable = true
        field.cell?.wraps = false
        if bordered {
            field.bezelStyle = .roundedBezel
        } else {
            (field.isBordered, field.drawsBackground, field.focusRingType) = (false, false, .none)
        }
        field.onFocus = { [weak coordinator = context.coordinator] in coordinator?.parent.onFocusChange(true) }
        return field
    }

    func updateNSView(_ field: HardenedTextField, context: Context) {
        context.coordinator.parent = self
        if field.stringValue != text { field.stringValue = text }
        if wantsFocus, field.currentEditor() == nil {
            // After this update: taking focus inside it would change state SwiftUI is drawing.
            DispatchQueue.main.async { [weak field] in
                guard let field, field.currentEditor() == nil, let window = field.window else { return }
                window.makeFirstResponder(field)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, NSTextFieldDelegate {
        var parent: PlainTextField
        init(_ parent: PlainTextField) { self.parent = parent }

        func controlTextDidChange(_ note: Notification) {
            guard let field = note.object as? NSTextField else { return }
            parent.text = field.stringValue
        }

        func controlTextDidEndEditing(_ note: Notification) {
            parent.onFocusChange(false)
        }

        func control(_ control: NSControl, textView: NSTextView, doCommandBy selector: Selector) -> Bool {
            switch selector {
            case #selector(NSResponder.insertNewline(_:)):
                parent.onSubmit()
                return true
            case #selector(NSResponder.cancelOperation(_:)):
                guard let cancel = parent.onCancel else { return false }
                cancel()
                return true
            default:
                return false
            }
        }
    }
}

/// The list's search field for SwiftUI, made of `HardenedSearchField`.
struct PlainSearchField: NSViewRepresentable {
    let placeholder: String
    @Binding var text: String

    func makeNSView(context: Context) -> HardenedSearchField {
        let field = HardenedSearchField()
        field.delegate = context.coordinator
        field.target = context.coordinator
        field.action = #selector(Coordinator.searched(_:))
        // As each letter is typed, not only on Return.
        field.sendsSearchStringImmediately = true
        return field
    }

    func updateNSView(_ field: HardenedSearchField, context: Context) {
        context.coordinator.text = $text
        field.placeholderString = placeholder
        if field.stringValue != text { field.stringValue = text }
    }

    func makeCoordinator() -> Coordinator { Coordinator(text: $text) }

    final class Coordinator: NSObject, NSSearchFieldDelegate {
        var text: Binding<String>
        init(text: Binding<String>) { self.text = text }

        func controlTextDidChange(_ note: Notification) {
            guard let field = note.object as? NSSearchField else { return }
            text.wrappedValue = field.stringValue
        }

        // The clear button, and Return.
        @objc func searched(_ sender: NSSearchField) {
            text.wrappedValue = sender.stringValue
        }
    }
}
