# Architecture

How SyncedNotes is built, and why.

## The app is SwiftUI; the editor is AppKit

Windows, menus, and layout are SwiftUI. The text view is AppKit's `NSTextView`, placed inside
SwiftUI through `NSViewRepresentable`.

**The text view owns the text.** SwiftUI never writes the text back into the view while
the user is typing. Pushing the string back on every change is what makes a wrapped text view
jump its cursor and lose its selection. SwiftUI
only hands the view a new document when the user switches to another one.

If the bridge ever fights us, the way out is an AppKit app around the same editor. The
editor code does not change; only the app around it does.

## Lists and blocks

**Lists are TextKit's own `NSTextList`s, drawn by the app.** The list is recorded on the
paragraph, and its bullets and numbers are not characters in the text, so they cannot be
half-deleted or copied as stray `•`s. TextKit 2 would draw them itself, but lays them out its
own fixed way (marker 11 points in, text at 36, tiny glyph bullets, a hyphen for a dash),
ignoring the paragraph's indents. So list paragraphs reach layout without their lists
(`ListLayout`, the content storage's delegate), indented 24 points a level, and each
paragraph's layout fragment (`NoteFragment`) draws its marker: bullets as shapes (● ○ ▪ by
level), an en dash, numbers ending just before the text. The text is untouched, so saving,
copying and undo see the lists as before. Facts that shape the code, found by testing:

- Paragraphs number as one list only when they share the *same* `NSTextList` object; equal
  but separate objects each restart at 1. So list commands join the list above rather than
  making a new one.
- NSTextView strips lists from what is typed next, so the empty last line of a note cannot
  hold one. Starting a list there first gives the line a line break of its own, as AppKit does
  itself when Return continues a list at the end of a note.
- TextKit redraws only the paragraphs an edit touched, so an item added or removed would leave
  the numbers below it stale on screen. Edits that can renumber (a line added or removed, or
  formatting changed) mark the paragraphs' views as needing to be drawn; asking TextKit to lay
  them out again did not draw them.
- On TextKit 2, undo and redo change the text without `didChangeText`, unlike TextKit 1. The
  editor reports them itself, so they are saved, and redrawn, like any edit.

**Quotes, code blocks and dividers are paragraph roles**, recorded under the same attribute as
headings, and drawn by TextKit 2 layout fragments (`BlockLayout`): a bar beside a quote, a panel
behind code, a line for a divider. The text stays exactly what was typed. A divider is one
attachment character on its own paragraph, which keeps it atomic; the line is drawn by the
paragraph, because TextKit 2 never drew a custom attachment's own image or view here.

## Checklists, pictures, tables

**Checklist items are a paragraph role**, like quotes, with the checked state recorded on the
item's text (`checkedKey`). The circle is drawn by the item's layout fragment, and the click
target is computed from the same rectangle, so what is drawn and what can be clicked cannot
drift apart.

**Pictures keep their original bytes.** An attachment holds either an image or original data,
never both: setting `image` quietly replaces the data with a re-encoded copy. So a picture is
its bytes, and its size for layout comes from a decoded copy kept alongside.

**Tables are not supported by TextKit 2.** Tested, not assumed: an `NSTextTable` does not force
the fallback to TextKit 1 (an earlier guess), it is ignored, and each cell is drawn as a plain
paragraph. So tables follow Apple Notes' own design, read from the class names in its
frameworks: a table is an attachment (`ICTableTextAttachment` there, `TableAttachment` here)
holding its own model of cells, shown through a TextKit 2 view provider by a table view whose
cells are text views. The cells in the attachment are the only source of truth; TextKit makes
a new view whenever the table comes back into sight, and it is built from them.

What made it work, each found the hard way or in the research:

- `tracksTextAttachmentViewBounds` is set in the provider's initializer, not in `loadView`,
  or TextKit ignores the provider's bounds (Apple's advice on its developer forums).
- Attachment views only exist once the viewport is laid out (`layoutViewport()`); until then
  there is nothing to focus.
- A cell can exist a moment before it is in the window. Focusing it then fails and leaves
  nothing focused, and typing goes to the window. So focus waits for the window.
- The caret never rests beside a table: the table is one character on its own line, and text
  typed beside it lands in the table's paragraph while looking as if it were below it.
- Rebuilding the cells (Undo, adding a column) keeps a cell focused, or focus is lost.

## Privacy

The native app follows the rule in [`Privacy.md`](../Privacy.md): the notes never leave the two
devices, not even to Apple. Its "Mac, native Swift app" section lists every system feature
this app must leave off. Design choices here must fit it.
