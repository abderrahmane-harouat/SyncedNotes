//! Turns a run of UTF-16 text into positioned glyphs.
//!
//! This first version maps one code point to one glyph, picking the first font in the
//! fallback chain that has it. The output already carries per-glyph offsets and a cluster
//! index, because kerning (GPOS) and Arabic joining (GSUB) will need both without changing
//! what Kotlin reads.

use crate::font::Font;
use crate::otl;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShapedGlyph {
    /// Index into the font list that was passed in.
    pub font: usize,
    pub glyph: u16,
    /// UTF-16 index of the first code unit this glyph came from.
    pub cluster: u32,
    pub advance: f32,
    pub dx: f32,
    pub dy: f32,
}

/// Code points that must never draw anything, not even a `.notdef` box: joiners, variation
/// selectors, bidi controls and the like. They still occupy their place in the text.
fn is_default_ignorable(cp: u32) -> bool {
    matches!(cp,
        0x00AD | 0x034F | 0x061C | 0x115F | 0x1160 | 0x17B4 | 0x17B5 | 0x180B..=0x180F
        | 0x200B..=0x200F | 0x202A..=0x202E | 0x2060..=0x206F | 0x3164 | 0xFE00..=0xFE0F
        | 0xFEFF | 0xFFA0 | 0xFFF0..=0xFFF8 | 0x1BCA0..=0x1BCA3 | 0x1D173..=0x1D17A
        | 0xE0000..=0xE0FFF)
}

/// Decodes UTF-16 without ever failing: an unpaired surrogate becomes U+FFFD, so text pasted
/// from anywhere still lays out.
pub fn code_points(text: &[u16]) -> impl Iterator<Item = (u32, usize)> + '_ {
    let mut i = 0;
    std::iter::from_fn(move || {
        if i >= text.len() {
            return None;
        }
        let start = i;
        let u = text[i] as u32;
        i += 1;
        if (0xD800..0xDC00).contains(&u) && i < text.len() {
            let v = text[i] as u32;
            if (0xDC00..0xE000).contains(&v) {
                i += 1;
                return Some((0x10000 + ((u - 0xD800) << 10) + (v - 0xDC00), start));
            }
        }
        if (0xD800..0xE000).contains(&u) {
            return Some((0xFFFD, start));
        }
        Some((u, start))
    })
}

/// How an Arabic letter joins its neighbours (Unicode's Joining_Type).
#[derive(Clone, Copy, PartialEq, Debug)]
enum Joining {
    /// Joins neither side: hamza, digits, everything not Arabic.
    None,
    /// Joins only the letter before it: alef, dal, ra, waw...
    Right,
    /// Joins both sides: most letters.
    Dual,
    /// Makes its neighbours join, drawing nothing of its own: tatweel, the zero-width joiner.
    Causing,
    /// Invisible to joining: the vowel marks above and below.
    Transparent,
}

/// Unicode's ArabicShaping.txt, for the Arabic blocks the app's fonts cover.
fn joining(cp: u32) -> Joining {
    use Joining::*;
    match cp {
        0x0640 | 0x200D => Causing,
        0x0610..=0x061A | 0x064B..=0x065F | 0x0670 | 0x06D6..=0x06DC | 0x06DF..=0x06E4 | 0x06E7 | 0x06E8
        | 0x06EA..=0x06ED | 0x08D3..=0x08E1 | 0x08E3..=0x08FF | 0x0300..=0x036F | 0x061C | 0x200B | 0x200E | 0x200F
        | 0x202A..=0x202E | 0x2066..=0x2069 | 0xFE00..=0xFE0F => Transparent,
        0x0622..=0x0625 | 0x0627 | 0x0629 | 0x062F..=0x0632 | 0x0648 | 0x0671..=0x0673 | 0x0675..=0x0677
        | 0x0688..=0x0699 | 0x06C0 | 0x06C3..=0x06CB | 0x06CD | 0x06CF | 0x06D2 | 0x06D3 | 0x06D5 | 0x06EE | 0x06EF
        | 0x0759..=0x075B | 0x076B | 0x076C | 0x0771 | 0x0773 | 0x0774 | 0x0778 | 0x0779 | 0x08AA..=0x08AC | 0x08AE
        | 0x08B1 | 0x08B2 | 0x08B9 => Right,
        0x0620 | 0x0626 | 0x0628 | 0x062A..=0x062E | 0x0633..=0x063F | 0x0641..=0x0647 | 0x0649 | 0x064A
        | 0x066E | 0x066F | 0x0678..=0x0687 | 0x069A..=0x06BF | 0x06C1 | 0x06C2 | 0x06CC | 0x06CE | 0x06D0 | 0x06D1
        | 0x06FA..=0x06FC | 0x06FF | 0x0750..=0x0758 | 0x075C..=0x076A | 0x076D..=0x0770 | 0x0772 | 0x0775..=0x0777
        | 0x077A..=0x077F | 0x08A0..=0x08A9 | 0x08AF | 0x08B0 | 0x08B3..=0x08B8 | 0x08BA..=0x08C8 => Dual,
        _ => None,
    }
}

fn is_arabic(cp: u32) -> bool {
    matches!(cp, 0x0600..=0x06FF | 0x0750..=0x077F | 0x08A0..=0x08FF | 0xFB50..=0xFDFF | 0xFE70..=0xFEFF)
}

/// Characters with no script of their own: spaces, digits, punctuation. They take the font
/// of the text around them when it has them, so an Arabic phrase keeps Arabic spacing.
fn is_common(cp: u32) -> bool {
    matches!(cp, 0x20..=0x40 | 0x5B..=0x60 | 0x7B..=0x7E | 0xA0..=0xBF | 0x2000..=0x206F | 0x060C | 0x061B | 0x061F | 0x066A..=0x066D)
        || is_emoji_joiner(cp)
}

/// What strings emoji together: the zero-width joiner, the variation selectors, the keycap,
/// skin tones and the tags of subdivision flags. They stay in the font of the emoji they follow.
fn is_emoji_joiner(cp: u32) -> bool {
    matches!(cp, 0x200D | 0xFE0E | 0xFE0F | 0x20E3 | 0x1F3FB..=0x1F3FF | 0xE0020..=0xE007F)
}

/// Unicode's Emoji_Presentation (emoji-data.txt, 18.0): what is drawn as a colour emoji even
/// without the emoji selector. The symbol fonts have some of these (⭐, ⬛) in black and
/// white; the rest of what they share with the emoji font (arrows, ☀, ✔) is text by default.
fn is_emoji_presentation(cp: u32) -> bool {
    matches!(cp,
        0x231A..=0x231B | 0x23E9..=0x23EC | 0x23F0 | 0x23F3 | 0x25FD..=0x25FE | 0x2614..=0x2615
        | 0x2648..=0x2653 | 0x267F | 0x2693 | 0x26A1 | 0x26AA..=0x26AB | 0x26BD..=0x26BE | 0x26C4..=0x26C5
        | 0x26CE | 0x26D4 | 0x26EA | 0x26F2..=0x26F3 | 0x26F5 | 0x26FA | 0x26FD | 0x2705 | 0x270A..=0x270B
        | 0x2728 | 0x274C | 0x274E | 0x2753..=0x2755 | 0x2757 | 0x2795..=0x2797 | 0x27B0 | 0x27BF
        | 0x2B1B..=0x2B1C | 0x2B50 | 0x2B55 | 0x1F004 | 0x1F0CF | 0x1F18E | 0x1F191..=0x1F19A
        | 0x1F1E6..=0x1F1FF | 0x1F201 | 0x1F21A | 0x1F22F | 0x1F232..=0x1F236 | 0x1F238..=0x1F23A
        | 0x1F250..=0x1F251 | 0x1F300..=0x1F320 | 0x1F32D..=0x1F335 | 0x1F337..=0x1F37C | 0x1F37E..=0x1F393
        | 0x1F3A0..=0x1F3CA | 0x1F3CF..=0x1F3D3 | 0x1F3E0..=0x1F3F0 | 0x1F3F4 | 0x1F3F8..=0x1F43E | 0x1F440
        | 0x1F442..=0x1F4FC | 0x1F4FF..=0x1F53D | 0x1F54B..=0x1F54E | 0x1F550..=0x1F567 | 0x1F57A
        | 0x1F595..=0x1F596 | 0x1F5A4 | 0x1F5FB..=0x1F64F | 0x1F680..=0x1F6C5 | 0x1F6CC | 0x1F6D0..=0x1F6D2
        | 0x1F6D5..=0x1F6D9 | 0x1F6DC..=0x1F6DF | 0x1F6EB..=0x1F6EC | 0x1F6F4..=0x1F6FC | 0x1F7E0..=0x1F7EB
        | 0x1F7F0 | 0x1F90C..=0x1F93A | 0x1F93C..=0x1F945 | 0x1F947..=0x1F9FF | 0x1FA70..=0x1FA7C
        | 0x1FA80..=0x1FAC6 | 0x1FAC8 | 0x1FACC..=0x1FADD | 0x1FADF..=0x1FAEB | 0x1FAEF..=0x1FAFA)
}

/// Which of the four shapes each letter takes, by the rules of Unicode's chapter 9: a letter
/// joins the one before when that one joins forward, and the next when it joins back;
/// transparent marks are looked through.
fn joining_forms(cps: &[u32]) -> Vec<u32> {
    use crate::otl::{FINA, INIT, ISOL, MEDI};
    let types: Vec<Joining> = cps.iter().map(|&c| joining(c)).collect();
    let mut forms = vec![0u32; cps.len()];
    let mut prev: Option<usize> = None;
    for i in 0..cps.len() {
        let t = types[i];
        if t == Joining::Transparent {
            continue;
        }
        let next = (i + 1..cps.len()).find(|&j| types[j] != Joining::Transparent);
        let joins_back = matches!(t, Joining::Dual | Joining::Right | Joining::Causing)
            && prev.is_some_and(|p| matches!(types[p], Joining::Dual | Joining::Causing));
        let joins_forward = matches!(t, Joining::Dual | Joining::Causing)
            && next.is_some_and(|n| matches!(types[n], Joining::Dual | Joining::Right | Joining::Causing));
        forms[i] = match t {
            Joining::Dual => match (joins_back, joins_forward) {
                (true, true) => MEDI,
                (true, false) => FINA,
                (false, true) => INIT,
                (false, false) => ISOL,
            },
            Joining::Right => if joins_back { FINA } else { ISOL },
            _ => 0,
        };
        prev = Some(i);
    }
    forms
}

/// Features, in the stages they run: each stage's lookups run in the font's order, and a later
/// stage sees what the earlier ones made. Arabic picks the joining forms one form at a time,
/// before the required ligatures (lam-alef) and the optional ones.
fn substitution_stages(arabic: bool) -> Vec<Vec<(otl::Tag, u32)>> {
    use crate::otl::{FINA, GLOBAL, INIT, ISOL, MEDI};
    let mut stages = vec![vec![(*b"ccmp", GLOBAL), (*b"locl", GLOBAL)]];
    if arabic {
        for (tag, mask) in [(*b"isol", ISOL), (*b"fina", FINA), (*b"medi", MEDI), (*b"init", INIT)] {
            stages.push(vec![(tag, mask)]);
        }
        stages.push(vec![(*b"rlig", GLOBAL)]);
    } else {
        stages.push(vec![(*b"rlig", GLOBAL)]);
    }
    stages.push(vec![(*b"calt", GLOBAL), (*b"liga", GLOBAL), (*b"clig", GLOBAL)]);
    stages
}

/// Shapes [text]: picks a font for each character, then runs each stretch in one font through
/// that font's substitutions and positioning. [rtl] is the direction the glyphs will be laid
/// out in, which decides where a mark lands relative to the letter it sits on.
pub fn shape(fonts: &[&Font], text: &[u16], size: f32, rtl: bool, out: &mut Vec<ShapedGlyph>) {
    out.clear();
    let Some(primary) = fonts.first() else { return };
    let tab_advance = {
        let space = primary.glyph_index(' ' as u32);
        4.0 * primary.advance(space) as f32 * size / primary.units_per_em as f32
    };

    let decoded: Vec<(u32, usize)> = code_points(text).collect();
    let cps: Vec<u32> = decoded.iter().map(|&(c, _)| c).collect();
    let forms = joining_forms(&cps);

    // The font for each character: the first in the chain that has it, except that a
    // character of no script stays in the font of the one before when that font has it.
    let mut font_of = vec![0usize; cps.len()];
    for (i, &cp) in cps.iter().enumerate() {
        // Followed by the emoji selector (U+FE0F), a character that has a text form too (♥,
        // a digit before a keycap) is drawn as an emoji when a colour font has it. One that
        // is an emoji by default (⭐, 👍) is too, though a symbol font ahead of the colour one
        // has it: only the text selector (U+FE0E) asks for its text form.
        let selector = cps.get(i + 1).copied();
        let wants_emoji = selector == Some(0xFE0F) || (is_emoji_presentation(cp) && selector != Some(0xFE0E));
        let emoji = if wants_emoji { fonts.iter().position(|f| f.has_color_bitmaps() && f.glyph_index(cp) != 0) } else { None };
        let text = if selector == Some(0xFE0E) { fonts.iter().position(|f| !f.has_color_bitmaps() && f.glyph_index(cp) != 0) } else { None };
        let first = emoji.or(text).or_else(|| fonts.iter().position(|f| f.glyph_index(cp) != 0)).unwrap_or(0);
        // A space or comma after an emoji goes back to a text font: the emoji font's space is
        // as wide as an emoji. Only what strings emoji together stays with them.
        let stays = i > 0 && is_common(cp) && fonts[font_of[i - 1]].glyph_index(cp) != 0
            && (!fonts[font_of[i - 1]].has_color_bitmaps() || is_emoji_joiner(cp));
        font_of[i] = if emoji.is_none() && stays { font_of[i - 1] } else { first };
    }

    let mut start = 0;
    while start < cps.len() {
        let font_index = font_of[start];
        let mut end = start + 1;
        while end < cps.len() && font_of[end] == font_index {
            end += 1;
        }
        shape_stretch(fonts[font_index], font_index, &decoded[start..end], &forms[start..end], size, tab_advance, rtl, out);
        start = end;
    }
}

#[allow(clippy::too_many_arguments)]
fn shape_stretch(
    font: &Font, font_index: usize, chars: &[(u32, usize)], forms: &[u32], size: f32, tab_advance: f32, rtl: bool,
    out: &mut Vec<ShapedGlyph>,
) {
    use crate::otl::{Gdef, Glyph, Layout, GLOBAL, IGNORABLE};
    let (gdef_bytes, gsub, gpos) = font.layout_tables();
    let gdef = Gdef::new(gdef_bytes);
    let arabic = chars.iter().any(|&(c, _)| is_arabic(c));
    let script = if arabic { *b"arab" } else { *b"latn" };

    let mut buf: Vec<Glyph> = Vec::with_capacity(chars.len());
    let mut tabs: Vec<u32> = Vec::new();
    for (k, &(cp, cluster)) in chars.iter().enumerate() {
        let mut mask = GLOBAL | forms[k];
        if is_default_ignorable(cp) {
            // Kept for the substitutions only when the font knows it: a joiner between two
            // emoji is how the font recognises a family, a flag, a skin tone.
            if font.glyph_index(cp) == 0 {
                continue;
            }
            mask |= IGNORABLE;
        }
        let id = if cp == '\t' as u32 { tabs.push(cluster as u32); font.glyph_index(' ' as u32) } else { font.glyph_index(cp) };
        let class = gdef.as_ref().map(|g| g.glyph_class(id)).unwrap_or(0);
        buf.push(Glyph { id, cluster: cluster as u32, mask, class, attach: -1, ..Default::default() });
    }
    let limit = crate::otl::growth_limit(chars.len());

    if let Some(table) = gsub {
        let layout = Layout::new(table, Gdef::new(gdef_bytes), false);
        for stage in substitution_stages(arabic) {
            for (lookup, mask) in layout.lookups(script, &stage) {
                layout.apply(&mut buf, lookup, mask, limit);
            }
        }
    }
    // Joiners and selectors the substitutions did not take into an emoji draw nothing.
    buf.retain(|g| g.mask & IGNORABLE == 0);
    for g in buf.iter_mut() {
        g.advance = font.advance(g.id) as i32;
        // Marks take no room of their own: they sit on the letter before, wherever GPOS puts them.
        if g.class == 3 {
            g.advance = 0;
        }
    }
    if let Some(table) = gpos {
        let layout = Layout::new(table, Gdef::new(gdef_bytes), true);
        let features = [(*b"kern", GLOBAL), (*b"mark", GLOBAL), (*b"mkmk", GLOBAL), (*b"dist", GLOBAL)];
        for (lookup, mask) in layout.lookups(script, &features) {
            layout.apply(&mut buf, lookup, mask, limit);
        }
    }

    // A mark's anchor offset is from the letter it sits on; turn it into one from the mark's
    // own pen position, which depends on which way the pen moves. Marks on marks add up.
    for m in 0..buf.len() {
        let b = buf[m].attach;
        if b < 0 || b as usize >= m {
            continue;
        }
        let b = b as usize;
        let between: i32 = if rtl {
            buf[b + 1..=m].iter().map(|g| g.advance).sum()
        } else {
            -buf[b..m].iter().map(|g| g.advance).sum::<i32>()
        };
        let parent = if buf[b].attach >= 0 { (buf[b].dx, buf[b].dy) } else { (0, 0) };
        buf[m].dx += between + parent.0;
        buf[m].dy += parent.1;
    }

    let scale = size / font.units_per_em as f32;
    for g in &buf {
        let advance = if tabs.contains(&g.cluster) { tab_advance } else { g.advance as f32 * scale };
        out.push(ShapedGlyph {
            font: font_index,
            glyph: g.id,
            cluster: g.cluster,
            advance,
            dx: g.dx as f32 * scale,
            // Font units grow upwards; the screen's y grows downwards.
            dy: -(g.dy as f32) * scale,
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::font::tests::roboto;

    fn utf16(s: &str) -> Vec<u16> {
        s.encode_utf16().collect()
    }

    #[test]
    fn each_letter_becomes_one_glyph_pointing_back_at_its_text() {
        let f = roboto();
        let mut out = Vec::new();
        shape(&[&f], &utf16("ab c"), 20.0, false, &mut out);
        assert_eq!(out.iter().map(|g| g.cluster).collect::<Vec<_>>(), vec![0, 1, 2, 3]);
        assert!(out.iter().all(|g| g.advance > 0.0));
    }

    #[test]
    fn a_character_outside_the_bmp_keeps_the_index_of_its_first_code_unit() {
        let f = roboto();
        let mut out = Vec::new();
        shape(&[&f], &utf16("a\u{1F600}b"), 20.0, false, &mut out);
        assert_eq!(out.iter().map(|g| g.cluster).collect::<Vec<_>>(), vec![0, 1, 3]);
    }

    #[test]
    fn a_lone_surrogate_does_not_stop_shaping() {
        let f = roboto();
        let mut out = Vec::new();
        shape(&[&f], &[0x61, 0xD800, 0x62], 20.0, false, &mut out);
        assert_eq!(out.len(), 3);
    }

    #[test]
    fn joiners_and_variation_selectors_draw_nothing() {
        let f = roboto();
        let mut out = Vec::new();
        shape(&[&f], &utf16("a\u{200D}\u{FE0F}b"), 20.0, false, &mut out);
        assert_eq!(out.iter().map(|g| g.cluster).collect::<Vec<_>>(), vec![0, 3]);
    }

    #[test]
    fn advances_scale_with_the_font_size() {
        let f = roboto();
        let mut small = Vec::new();
        let mut big = Vec::new();
        shape(&[&f], &utf16("m"), 10.0, false, &mut small);
        shape(&[&f], &utf16("m"), 20.0, false, &mut big);
        assert!((big[0].advance - 2.0 * small[0].advance).abs() < 1e-4);
    }

    fn naskh() -> Font {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../assets/fonts/NotoNaskhArabic-Regular.ttf");
        Font::parse(std::fs::read(path).unwrap()).unwrap()
    }

    fn glyphs(fonts: &[&Font], text: &str, rtl: bool) -> Vec<ShapedGlyph> {
        let mut out = Vec::new();
        shape(fonts, &utf16(text), 20.0, rtl, &mut out);
        out
    }

    #[test]
    fn an_arabic_letter_takes_a_different_shape_at_the_start_middle_and_end_of_a_word() {
        let f = naskh();
        let isolated = glyphs(&[&f], "\u{0628}", true)[0].glyph;
        let three = glyphs(&[&f], "\u{0628}\u{0628}\u{0628}", true);
        let ids: Vec<u16> = three.iter().map(|g| g.glyph).collect();
        assert_eq!(ids.len(), 3);
        assert!(ids.iter().all(|&g| g != isolated), "every beh in a word is joined: {ids:?}, alone {isolated}");
        assert!(ids[0] != ids[1] && ids[1] != ids[2], "initial, medial and final differ: {ids:?}");
        assert_eq!(three.iter().map(|g| g.cluster).collect::<Vec<_>>(), vec![0, 1, 2]);
    }

    #[test]
    fn a_letter_after_alef_starts_again_because_alef_joins_only_backwards() {
        let f = naskh();
        // beh alef beh: the second beh cannot join the alef before it.
        let word = glyphs(&[&f], "\u{0628}\u{0627}\u{0628}", true);
        let alone = glyphs(&[&f], "\u{0628}", true)[0].glyph;
        assert_eq!(word.last().unwrap().glyph, alone);
    }

    #[test]
    fn lam_and_alef_become_one_glyph() {
        let f = naskh();
        let lam_alef = glyphs(&[&f], "\u{0644}\u{0627}", true);
        assert_eq!(lam_alef.len(), 1, "{lam_alef:?}");
        assert_eq!(lam_alef[0].cluster, 0);
    }

    #[test]
    fn a_vowel_mark_takes_no_room_and_is_moved_onto_its_letter() {
        let f = naskh();
        let marked = glyphs(&[&f], "\u{0628}\u{064E}", true);
        assert_eq!(marked.len(), 2);
        assert_eq!(marked[1].advance, 0.0);
        // The fatha's outline is drawn above the baseline by the font itself; GPOS moves it
        // from where it would fall onto the anchor of the letter it belongs to.
        assert!(marked[1].dx != 0.0 || marked[1].dy != 0.0, "the mark was attached: {:?}", marked[1]);
    }

    #[test]
    fn a_space_between_arabic_words_stays_in_the_arabic_font() {
        let latin = roboto();
        let arabic = naskh();
        let out = glyphs(&[&latin, &arabic], "\u{0628} \u{0628}", true);
        assert!(out.iter().all(|g| g.font == 1), "{out:?}");
        let english = glyphs(&[&latin, &arabic], "a b", false);
        assert!(english.iter().all(|g| g.font == 0));
    }

    #[test]
    fn latin_text_is_kerned() {
        let f = roboto();
        let pair = glyphs(&[&f], "AV", false);
        let alone = glyphs(&[&f], "A", false);
        assert!(pair[0].advance < alone[0].advance, "A is pulled toward V: {} < {}", pair[0].advance, alone[0].advance);
    }

    fn emoji() -> Font {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../assets/fonts/NotoColorEmoji.ttf");
        Font::parse(std::fs::read(path).unwrap()).unwrap()
    }

    #[test]
    fn an_emoji_is_drawn_from_the_emoji_font() {
        let (latin, colour) = (roboto(), emoji());
        let out = glyphs(&[&latin, &colour], "a\u{1F600}", false);
        assert_eq!(out.len(), 2);
        assert_eq!(out[1].font, 1);
        assert!(out[1].advance > 0.0);
    }

    #[test]
    fn a_skin_tone_a_flag_and_a_family_each_become_one_picture() {
        let (latin, colour) = (roboto(), emoji());
        for sequence in ["\u{1F44D}\u{1F3FD}", "\u{1F1E9}\u{1F1FF}", "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}"] {
            let out = glyphs(&[&latin, &colour], sequence, false);
            assert_eq!(out.len(), 1, "{sequence:?} gave {out:?}");
            assert_eq!(out[0].font, 1);
            assert_eq!(out[0].cluster, 0);
        }
    }

    #[test]
    fn a_heart_with_the_emoji_selector_is_the_red_emoji_not_the_text_heart() {
        let (latin, colour) = (roboto(), emoji());
        let out = glyphs(&[&latin, &colour], "\u{2764}\u{FE0F}", false);
        assert_eq!(out.len(), 1, "{out:?}");
        assert_eq!(out[0].font, 1);
    }

    #[test]
    fn an_emoji_picture_decodes_to_colour() {
        let colour = emoji();
        let glyph = colour.glyph_index(0x1F600);
        let bitmap = colour.color_bitmap(glyph, 32.0).unwrap();
        let image = crate::png::decode(bitmap.png).unwrap();
        assert!(image.width > 64 && image.height > 64);
        // A yellow face: somewhere a pixel is clearly more red than blue.
        assert!(image.rgba.chunks(4).any(|p| p[3] == 255 && p[0] > 200 && p[2] < 100));
    }

    fn symbols() -> (Font, Font) {
        let load = |name: &str| {
            let path = format!("{}/../assets/fonts/{name}", env!("CARGO_MANIFEST_DIR"));
            Font::parse(std::fs::read(path).unwrap()).unwrap()
        };
        (load("NotoSansMath-Regular.ttf"), load("NotoSansSymbols2-Regular.ttf"))
    }

    #[test]
    fn an_arrow_is_drawn_from_the_symbol_fonts_rather_than_as_an_empty_box() {
        let (latin, colour) = (roboto(), emoji());
        let (math, symbols) = symbols();
        let fonts = [&latin, &math, &symbols, &colour];
        for (arrow, font) in [("\u{2192}", 1), ("\u{21D2}", 1), ("\u{27F6}", 1), ("\u{279C}", 2)] {
            let out = glyphs(&fonts, arrow, false);
            assert_eq!((out[0].font, out[0].glyph != 0), (font, true), "{arrow:?} gave {out:?}");
        }
    }

    #[test]
    fn a_character_that_is_an_emoji_by_default_stays_in_colour_though_a_symbol_font_has_it() {
        let (latin, colour) = (roboto(), emoji());
        let (math, symbols) = symbols();
        for star_or_thumb in ["\u{2B50}", "\u{2705}", "\u{1F44D}"] {
            let out = glyphs(&[&latin, &math, &symbols, &colour], star_or_thumb, false);
            assert_eq!(out[0].font, 3, "{star_or_thumb:?} gave {out:?}");
        }
    }

    #[test]
    fn an_arrow_that_is_also_an_emoji_is_text_unless_the_emoji_selector_follows() {
        let (latin, colour) = (roboto(), emoji());
        let (math, symbols) = symbols();
        let fonts = [&latin, &math, &symbols, &colour];
        assert_eq!(glyphs(&fonts, "\u{2194}", false)[0].font, 1);
        let asked = glyphs(&fonts, "\u{2194}\u{FE0F}", false);
        assert_eq!((asked.len(), asked[0].font), (1, 3), "{asked:?}");
    }

    #[test]
    fn the_text_selector_draws_an_emoji_by_default_as_text() {
        let (latin, colour) = (roboto(), emoji());
        let (math, symbols) = symbols();
        let out = glyphs(&[&latin, &math, &symbols, &colour], "\u{2B50}\u{FE0E}", false);
        assert_eq!((out.len(), out[0].font), (1, 1), "{out:?}");
    }

    #[test]
    fn a_space_after_an_emoji_is_a_text_space_not_an_emoji_wide_one() {
        let (latin, colour) = (roboto(), emoji());
        let out = glyphs(&[&latin, &colour], "\u{1F600} a", false);
        assert_eq!(out[1].font, 0, "{out:?}");
    }
}
