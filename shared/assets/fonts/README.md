# Fonts

Shipped inside both apps, so a note looks the same on the phone and the Mac and the engine
never asks the system for a font. Each file is unchanged from its source; the copyright and
licence are also in each font's own `name` table.

| File | Source | Licence |
|---|---|---|
| `Roboto-*.ttf` | Google, Roboto (2011) | Apache License 2.0 |
| `NotoNaskhArabic-*.ttf` | Google, Noto Naskh Arabic (2014) | SIL Open Font License 1.1 |
| `RobotoMono-Regular.ttf` | [googlefonts/RobotoMono](https://github.com/googlefonts/RobotoMono), `fonts/ttf` | SIL Open Font License 1.1 (`RobotoMono-OFL.txt`) |
| `NotoColorEmoji.ttf` | [googlefonts/noto-emoji](https://github.com/googlefonts/noto-emoji), `2D/fonts` | SIL Open Font License 1.1 (`NotoColorEmoji-OFL.txt`) |
| `NotoSansMath-Regular.ttf` | [google/fonts](https://github.com/google/fonts), `ofl/notosansmath` | SIL Open Font License 1.1 (`NotoSansMath-OFL.txt`) |
| `NotoSansSymbols2-Regular.ttf` | [google/fonts](https://github.com/google/fonts), `ofl/notosanssymbols2` | SIL Open Font License 1.1 (`NotoSansSymbols2-OFL.txt`) |

Roboto has none of the arrows (← → ⇒ ⟶ ➜), so they come from the two symbol fonts, after
Roboto and Naskh in every stack and before the emoji font. Both also have some characters
that are emoji by default (⭐, ✅); the shaper draws those from the emoji font anyway
(Unicode's Emoji_Presentation, in `shared/glyphs/src/shape.rs`), as a browser does.
