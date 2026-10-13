//! Turns a run of UTF-16 text into positioned glyphs.
//!
//! This first version maps one code point to one glyph, picking the first font in the
//! fallback chain that has it. The output already carries per-glyph offsets and a cluster
//! index, because kerning (GPOS) and Arabic joining (GSUB) will need both without changing
//! what Kotlin reads.

use crate::font::Font;

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

pub fn shape(fonts: &[&Font], text: &[u16], size: f32, out: &mut Vec<ShapedGlyph>) {
    out.clear();
    let Some(primary) = fonts.first() else { return };
    let tab_advance = {
        let space = primary.glyph_index(' ' as u32);
        4.0 * primary.advance(space) as f32 * size / primary.units_per_em as f32
    };

    for (cp, cluster) in code_points(text) {
        if is_default_ignorable(cp) {
            continue;
        }
        if cp == '\t' as u32 {
            let space = primary.glyph_index(' ' as u32);
            out.push(ShapedGlyph { font: 0, glyph: space, cluster: cluster as u32, advance: tab_advance, dx: 0.0, dy: 0.0 });
            continue;
        }
        let mut chosen = (0usize, 0u16);
        for (i, f) in fonts.iter().enumerate() {
            let g = f.glyph_index(cp);
            if g != 0 {
                chosen = (i, g);
                break;
            }
        }
        let font = fonts[chosen.0];
        let advance = font.advance(chosen.1) as f32 * size / font.units_per_em as f32;
        out.push(ShapedGlyph { font: chosen.0, glyph: chosen.1, cluster: cluster as u32, advance, dx: 0.0, dy: 0.0 });
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
        shape(&[&f], &utf16("ab c"), 20.0, &mut out);
        assert_eq!(out.iter().map(|g| g.cluster).collect::<Vec<_>>(), vec![0, 1, 2, 3]);
        assert!(out.iter().all(|g| g.advance > 0.0));
    }

    #[test]
    fn a_character_outside_the_bmp_keeps_the_index_of_its_first_code_unit() {
        let f = roboto();
        let mut out = Vec::new();
        shape(&[&f], &utf16("a\u{1F600}b"), 20.0, &mut out);
        assert_eq!(out.iter().map(|g| g.cluster).collect::<Vec<_>>(), vec![0, 1, 3]);
    }

    #[test]
    fn a_lone_surrogate_does_not_stop_shaping() {
        let f = roboto();
        let mut out = Vec::new();
        shape(&[&f], &[0x61, 0xD800, 0x62], 20.0, &mut out);
        assert_eq!(out.len(), 3);
    }

    #[test]
    fn joiners_and_variation_selectors_draw_nothing() {
        let f = roboto();
        let mut out = Vec::new();
        shape(&[&f], &utf16("a\u{200D}\u{FE0F}b"), 20.0, &mut out);
        assert_eq!(out.iter().map(|g| g.cluster).collect::<Vec<_>>(), vec![0, 3]);
    }

    #[test]
    fn advances_scale_with_the_font_size() {
        let f = roboto();
        let mut small = Vec::new();
        let mut big = Vec::new();
        shape(&[&f], &utf16("m"), 10.0, &mut small);
        shape(&[&f], &utf16("m"), 20.0, &mut big);
        assert!((big[0].advance - 2.0 * small[0].advance).abs() < 1e-4);
    }
}
