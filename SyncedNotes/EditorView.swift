import AppKit
import SwiftUI

/// The editor: AppKit's `NSTextView` on TextKit 2, placed in SwiftUI.
///
/// The text view owns the text. SwiftUI builds it once and never writes a string back into it
/// while the user types (see `Docs/Architect.md`): that round trip is what makes a wrapped
/// text view lose its caret and selection.
struct EditorView: NSViewRepresentable {

    /// Where the editor reports what the selection looks like. SwiftUI only reads it.
    let formatting: FormattingModel
    let model: NotesModel
    /// The note to show. Its text goes into the view only when a different note is chosen.
    let document: EditorDocument

    final class Coordinator {
        var shown: Data?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeNSView(context: Context) -> NSScrollView {
        let textView = NoteTextView.makeEditor()
        watchForTextKit1Fallback(textView)
        textView.onFormattingChange = { [formatting] in formatting.current = $0 }
        textView.onTextChange = { [model] in model.contentChanged() }
        model.editorAppeared(textView)
        textView.textContainerInset = NSSize(width: 20, height: 20)

        // Grows downwards with its text and follows the window's width, so the scroll view
        // scrolls vertically and lines wrap instead of running off to the right.
        textView.isVerticallyResizable = true
        textView.isHorizontallyResizable = false
        textView.autoresizingMask = [.width]
        textView.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: .greatestFiniteMagnitude)
        textView.textContainer?.widthTracksTextView = true

        let scrollView = NSScrollView()
        scrollView.hasVerticalScroller = true
        scrollView.documentView = textView
        return scrollView
    }

    func updateNSView(_ scrollView: NSScrollView, context: Context) {
        guard let textView = scrollView.documentView as? NoteTextView else { return }
        // A note in the Trash is shown, not edited.
        textView.isEditable = !document.inTrash
        // The text view owns the text: the only time it is replaced is when another note is
        // chosen. Never while typing, which is what loses the caret and the selection.
        guard context.coordinator.shown != document.id else { return }
        context.coordinator.shown = document.id
        textView.textStorage?.setAttributedString(document.text)
        textView.noteID = document.id
        // An empty note starts with its title, as the first line is the note's name in the
        // list anyway; Return ends it and the body follows (`NoteTextView.insertNewline`).
        textView.typingAttributes = NoteTextView.typingAttributes(for: document.text.length == 0 ? .title : .body)
        textView.setSelectedRange(NSRange(location: 0, length: 0))
        // Undo belongs to the note it was made in, not the next one.
        textView.undoManager?.removeAllActions()
        textView.scroll(.zero)
        model.editorAppeared(textView)
        textView.takeFocusWhenShown()
    }

    /// Fails loudly in debug builds if anything drops the text view back to TextKit 1.
    ///
    /// The fallback happens without an error and cannot be undone, so the first sign of it
    /// would otherwise be a subtle layout bug much later, far from whatever caused it.
    private func watchForTextKit1Fallback(_ textView: NSTextView) {
        #if DEBUG
        NotificationCenter.default.addObserver(
            forName: NSTextView.willSwitchToNSLayoutManagerNotification,
            object: textView,
            queue: .main
        ) { _ in
            assertionFailure("The editor fell back to TextKit 1: something read `layoutManager`.")
        }
        #endif
    }
}
