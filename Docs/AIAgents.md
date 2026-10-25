# A folder shared with an AI agent

One folder of notes, and the folders inside it, can be shared with an AI agent: Claude Code,
Claude Desktop, opencode, or any other that speaks the Model Context Protocol (MCP). The agent
reads, changes and adds notes there, and sees nothing else. It works on a subscription; no API
key is involved, since it is the agent that talks to its model, not the app.

This is the one way a note leaves the Mac and the phone. What the agent reads, it sends to the
company that runs its model, as it does everything it is given. So nothing is shared until a
folder is chosen, the app says so before it is, and the folder carries a mark in the sidebar for
as long as it is shared. Since notes may hold passwords and keys, the folder shared should be one that
holds none. See [`Privacy.md`](../Privacy.md).

## Sharing a folder

Right-click the folder in the sidebar, choose **Share with AI Agent…**, then **Share Folder**.
The sheet then shows what to paste into each agent, with the token in it, ready to copy. Only
one folder is shared at a time; sharing another takes its place. **Stop Sharing** ends it, and
**New Token** shuts out every agent given the old token.

**Claude Code**, once, in Terminal (`--scope user` makes it there in every project):

```bash
claude mcp add --transport http --scope user syncednotes http://127.0.0.1:48124/mcp --header "Authorization: Bearer <token>"
```

**opencode**, in `~/.config/opencode/opencode.json`:

```json
{
  "mcp": {
    "syncednotes": {
      "type": "remote",
      "url": "http://127.0.0.1:48124/mcp",
      "enabled": true,
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

**Claude Desktop** starts the servers it uses as programs and talks to them on standard input
and output, and its own remote connectors are reached from Anthropic's servers, which cannot
reach this Mac. So the sheet gives it a bridge made of the Mac's own `sh` and `curl`, which
passes each message to the app and prints the answer, nothing to install, to put under
`mcpServers` in `claude_desktop_config.json` (Settings > Developer > Edit Config).

## What the agent can do

| Tool | What it does |
|---|---|
| `list_notes` | The notes in the shared folder and the folders inside it, with their IDs |
| `search_notes` | The notes whose words start with the ones given |
| `read_note` | A note as Markdown |
| `create_note` | A new note from Markdown, in the shared folder or one inside it |
| `replace_in_note` | Replaces one exact stretch of a note's text, keeping the formatting around it |
| `append_to_note` | Adds Markdown at a note's end |
| `rewrite_note` | Replaces a whole note with Markdown (refused for a note with pictures) |
| `create_folder` | A folder inside the shared one |
| `move_note_to_trash` | To the Trash, where it can be restored for 30 days |

Notes are Markdown a line per paragraph, as `MarkdownWriter` and `MarkdownReader` in the
engine write and read it: headings, lists, checklists, quotes, fenced code in its language,
callouts as GitHub's alerts (`> [!NOTE]` blue, `[!TIP]` green, `[!WARNING]` orange,
`[!IMPORTANT]` purple, `[!CAUTION]` pink, `[!GRAY]`), tables as GitHub's (`| a | b |`, the
rule `| --- | --- |` under the first row), dividers, bold, italic, strikethrough, inline code.
Markdown cannot say underline, colours,
highlights or sizes, so `replace_in_note` is the way to change part of a note: it edits the
note's own text in the engine, and everything around the change stays as it was. A note the
engine cannot edit (a picture amid text, a table cell of several paragraphs) is read, never
written, as in the app.

Every change is saved locked, like a change typed in the app, and syncs to the phone. If the
note is open, what was typed in it is saved first, so the agent works on the note as it is on
screen, and the agent's change is then shown, as a change from the phone is.

## How it is kept to the folder

- **Only the folder.** Every call works out the shared folder and the folders inside it from the
  store, and a note outside them, or in the Trash, reads as no note at all: the agent gets the
  same answer as for an ID that never existed, so it cannot learn that other notes exist, nor
  their titles (`AgentNotes`).
- **Only this Mac.** The server (`AgentServer`, MCP's Streamable HTTP transport, answered with
  plain JSON) listens on the loopback address alone, `127.0.0.1`, which nothing on the network
  can reach.
- **Only with the token.** Every request must carry the token, 256 random bits the app makes,
  compared in constant time. It is kept in the app's preferences, apart for each notes folder,
  so a run on the try-out notes never shares the real ones.
- **Not from a web page.** A request with an `Origin` that is not the server's own is refused,
  so a site open in a browser cannot reach the notes through it (DNS rebinding).
- **Only while unlocked.** The notes are never decrypted for the agent outside the app, nor
  written anywhere in plain text. Locked, the server has no key, and answers only that the notes
  are locked; it stops when the app quits.

The agent itself is not sandboxed by the app: it is a program of its own, with whatever else it
has been given. What the app controls is what of the notes it can reach.

## Trying it

`./gradlew :app:run -PtryUnlock -PtryAgentFolder=Work` in `mac/` shares the made-up notes'
"Work" folder at launch; the token is in the try-out notes' own preferences node. Give an agent
only the try-out notes when trying a change, never the real ones.
