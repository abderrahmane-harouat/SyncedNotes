import AppKit

/// What keeps the notes away from Apple's own services, app-wide (Privacy.md).
///
/// Apple is treated as a vendor like any other: the app uses what of macOS runs on the Mac
/// alone, and refuses, in code, every service that can carry what is typed or shown elsewhere.
/// The text views do their part themselves (`NoteTextView`, `HardenedFieldEditor`); this is
/// what no single view can do: the menus AppKit fills in by itself, every window, and the
/// editing component of text fields the app does not make (a passphrase's secure field).
enum KeepOnThisMac {

    /// Called once, as the app starts, before any window or menu exists.
    static func install() {
        UserDefaults.standard.register(defaults: [
            // Documented by AppKit: no Start Dictation in the Edit menu. Dictation can send
            // what is said to Apple's servers.
            "NSDisabledDictationMenuItem": true,
            // AppKit's own names for its AutoFill (Passwords, Contacts) suggestions and menu,
            // read from the system's libraries; undocumented, so the menu item is also removed
            // by hand below, in case these stop being read.
            "NSAutoFillHeuristicsEnabled": false,
            "NSAutoFillOrSystemInsertMenuEnabled": false,
            "NSAutoFillSystemInsertMenuEnabled": false,
        ])

        let center = NotificationCenter.default
        // A field's editing component is shared and made by whoever owns the field, SwiftUI's
        // secure field included: whichever it is, its text services go off as it starts.
        center.addObserver(forName: NSControl.textDidBeginEditingNotification, object: nil, queue: .main) { note in
            let editor = note.userInfo?["NSFieldEditor"] as? NSTextView
            MainActor.assumeIsolated { editor?.turnOffTextServices() }
        }
        // AppKit adds Writing Tools, AutoFill and Start Dictation to the Edit menu by itself,
        // and again whenever it rebuilds the menu, so they are taken out each time.
        center.addObserver(forName: NSMenu.didAddItemNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { scheduleMenuCleanUp() }
        }
        center.addObserver(forName: NSApplication.didFinishLaunchingNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { scheduleMenuCleanUp() }
        }
        // Every window, sheets and Settings included: after every event, and as one becomes the
        // window in use, so none is shown for long before it is covered.
        for name in [NSApplication.didUpdateNotification, NSWindow.didBecomeKeyNotification, NSWindow.didBecomeMainNotification] {
            center.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { keepWindowsOutOfCaptures() }
            }
        }
    }

    // MARK: - Windows

    /// Asks macOS to keep every window's content from other processes: screenshots, screen
    /// recording and sharing, and anything else that reads the screen, Apple's assistants
    /// included. The cost: the app cannot be screenshotted or shown in a screen share.
    private static func keepWindowsOutOfCaptures() {
        for window in NSApp.windows where window.sharingType != .none {
            window.sharingType = .none
        }
    }

    // MARK: - Menus

    private static var cleanUpScheduled = false

    /// After the current change to the menus is over: removing an item while AppKit is still
    /// adding to the same menu is not safe.
    private static func scheduleMenuCleanUp() {
        guard !cleanUpScheduled else { return }
        cleanUpScheduled = true
        DispatchQueue.main.async {
            cleanUpScheduled = false
            if let main = NSApp.mainMenu { strip(main) }
        }
    }

    /// Takes out of [menu], and every menu inside it, what hands text to a service: Writing
    /// Tools, AutoFill, Start Dictation, and the Services menu.
    static func strip(_ menu: NSMenu) {
        for item in menu.items.reversed() {
            if isRefused(item) {
                menu.removeItem(item)
            } else if let submenu = item.submenu {
                strip(submenu)
            }
        }
        tidySeparators(in: menu)
    }

    private static func isRefused(_ item: NSMenuItem) -> Bool {
        let id = item.identifier?.rawValue ?? ""
        if id.contains("WritingTools") || id.contains("AutoFill") { return true }
        if let action = item.action, ["startDictation:", "showWritingTools:"].contains(NSStringFromSelector(action)) { return true }
        if let services = NSApp.servicesMenu, item.submenu === services { return true }
        return false
    }

    /// No separator first, last, or next to another, once items around it have gone.
    private static func tidySeparators(in menu: NSMenu) {
        var previousWasSeparator = true
        for item in menu.items {
            if item.isSeparatorItem, previousWasSeparator {
                menu.removeItem(item)
            } else {
                previousWasSeparator = item.isSeparatorItem
            }
        }
        while let last = menu.items.last, last.isSeparatorItem { menu.removeItem(last) }
    }
}

extension NSTextView {

    /// Switches off every text service that could send what is typed off the Mac.
    ///
    /// Per `Privacy.md`, unknown means off, and each is set here rather than left to the
    /// defaults, which change between macOS versions. Some of these are almost certainly local
    /// (quote and dash substitution are plain string rewrites); they stay off until each is
    /// confirmed and the confirmation is written down in `Privacy.md`.
    func turnOffTextServices() {
        writingToolsBehavior = .none
        isContinuousSpellCheckingEnabled = false
        isGrammarCheckingEnabled = false
        isAutomaticSpellingCorrectionEnabled = false
        isAutomaticTextCompletionEnabled = false
        inlinePredictionType = .no
        mathExpressionCompletionType = .no
        isAutomaticTextReplacementEnabled = false
        isAutomaticQuoteSubstitutionEnabled = false
        isAutomaticDashSubstitutionEnabled = false
        isAutomaticDataDetectionEnabled = false
        isAutomaticLinkDetectionEnabled = false
    }

    /// Writes the selection to [pasteboard] for this Mac only: Universal Clipboard would
    /// otherwise hand it to every nearby Apple device on the account.
    func writeSelectionForThisMacOnly(to pasteboard: NSPasteboard, types: [NSPasteboard.PasteboardType]) -> Bool {
        pasteboard.prepareForNewContents(with: .currentHostOnly)
        pasteboard.addTypes(types, owner: nil)
        var wrote = false
        for type in types where writeSelection(to: pasteboard, type: type) { wrote = true }
        return wrote
    }

    /// The only right-click menu a text gets here: editing, and nothing that sends the text
    /// anywhere. AppKit's own adds Look Up, Translate, Search With Google, Share, Services and
    /// Writing Tools, and those cannot be switched off one by one.
    func editingOnlyMenu(pasteAndMatchStyle: Bool = false) -> NSMenu {
        let menu = NSMenu()
        var items: [(String, Selector)] = [("Cut", #selector(cut(_:))), ("Copy", #selector(copy(_:))), ("Paste", #selector(paste(_:)))]
        if pasteAndMatchStyle { items.append(("Paste and Match Style", #selector(pasteAsPlainText(_:)))) }
        for (title, action) in items {
            menu.addItem(withTitle: title, action: action, keyEquivalent: "").target = self
        }
        menu.addItem(.separator())
        menu.addItem(withTitle: "Select All", action: #selector(selectAll(_:)), keyEquivalent: "").target = self
        return menu
    }
}
