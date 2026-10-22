//! The state that lives across calls from Kotlin: loaded fonts and rasterized glyphs, plus
//! the decoders for the byte layouts Kotlin writes. Those layouts are a contract with
//! `com.syncednotes.engine.render` and are documented there and here; change both together.

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

use crate::font::{Font, Transform};
use crate::paint::{Clip, Color, Mask, Path, Target};
use crate::shape::{shape, ShapedGlyph};
use crate::font::OutlineSink;

// Glyph positions are rounded to a quarter pixel horizontally: finer steps look no different
// but multiply the cache, coarser ones make letter spacing visibly uneven.
const SUBPIXEL_STEPS: f32 = 4.0;
// The whole cache is dropped when it grows past this; a note uses a few hundred glyphs.
const MAX_CACHED_GLYPHS: usize = 4096;
// Larger than any glyph a note editor draws; refuses absurd sizes before they allocate.
const MAX_GLYPH_PIXELS: f32 = 2048.0;
const MAX_FALLBACK_FONTS: usize = 8;

pub const SHAPED_GLYPH_BYTES: usize = 24;

pub const OP_RECT: i32 = 1;
pub const OP_GLYPHS: i32 = 2;
pub const OP_PATH: i32 = 3;

pub const SEG_MOVE: i32 = 0;
pub const SEG_LINE: i32 = 1;
pub const SEG_QUAD: i32 = 2;
pub const SEG_CLOSE: i32 = 3;

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct GlyphKey {
    font: u32,
    glyph: u16,
    size_64ths: u32,
    subpixel: u8,
}

struct GlyphMask {
    left: i32,
    top: i32,
    mask: Mask,
}

pub struct Engine {
    fonts: Vec<Font>,
    glyphs: HashMap<GlyphKey, GlyphMask>,
    shaped: Vec<ShapedGlyph>,
}

static ENGINE: LazyLock<Mutex<Engine>> =
    LazyLock::new(|| Mutex::new(Engine { fonts: Vec::new(), glyphs: HashMap::new(), shaped: Vec::new() }));

pub fn with_engine<R>(f: impl FnOnce(&mut Engine) -> R) -> R {
    // A panic while holding the lock leaves only caches behind, never half-written pixels
    // the caller relies on, so carrying on with the poisoned state is safe.
    let mut guard = ENGINE.lock().unwrap_or_else(|e| e.into_inner());
    f(&mut guard)
}

/// Little-endian reader over a byte slice that reports running out instead of panicking.
pub struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Reader<'a> {
        Reader { bytes, pos: 0 }
    }

    pub fn at_end(&self) -> bool {
        self.pos >= self.bytes.len()
    }

    fn take<const N: usize>(&mut self) -> Option<[u8; N]> {
        let b = self.bytes.get(self.pos..self.pos.checked_add(N)?)?;
        self.pos += N;
        b.try_into().ok()
    }

    pub fn i32(&mut self) -> Option<i32> {
        self.take::<4>().map(i32::from_le_bytes)
    }

    pub fn u32(&mut self) -> Option<u32> {
        self.take::<4>().map(u32::from_le_bytes)
    }

    pub fn f32(&mut self) -> Option<f32> {
        self.take::<4>().map(f32::from_le_bytes)
    }

    pub fn u16(&mut self) -> Option<u16> {
        self.take::<2>().map(u16::from_le_bytes)
    }
}

impl Engine {
    /// Returns the new font's id, or -1 when the bytes are not a font we can read.
    pub fn load_font(&mut self, bytes: &[u8]) -> i32 {
        match Font::parse(bytes.to_vec()) {
            Some(f) => {
                self.fonts.push(f);
                (self.fonts.len() - 1) as i32
            }
            None => -1,
        }
    }

    /// unitsPerEm, ascender, descender, lineGap, underline position and thickness,
    /// strikeout position and size; all in font units, y up.
    pub fn metrics(&self, id: i32) -> Option<[i32; 8]> {
        let f = self.fonts.get(usize::try_from(id).ok()?)?;
        Some([
            f.units_per_em as i32,
            f.ascender as i32,
            f.descender as i32,
            f.line_gap as i32,
            f.underline_position as i32,
            f.underline_thickness as i32,
            f.strikeout_position as i32,
            f.strikeout_size as i32,
        ])
    }

    /// Input: i32 fontCount, i32 fontIds[fontCount], f32 size, i32 flags (bit 0: the text is
    /// laid out right to left), i32 length, u16 text[length].
    /// Output: per glyph i32 fontId, i32 glyph, i32 cluster, f32 advance, f32 dx, f32 dy.
    /// Returns the glyph count, or minus the count needed when `output` is too small, or
    /// `i32::MIN` when the input is malformed.
    pub fn shape(&mut self, input: &[u8], output: &mut [u8]) -> i32 {
        const MALFORMED: i32 = i32::MIN;
        let mut r = Reader::new(input);
        let Some(count) = r.i32() else { return MALFORMED };
        let Ok(count) = usize::try_from(count) else { return MALFORMED };
        if count == 0 || count > MAX_FALLBACK_FONTS {
            return MALFORMED;
        }
        let mut ids = Vec::with_capacity(count);
        for _ in 0..count {
            match r.i32().and_then(|i| usize::try_from(i).ok()) {
                Some(i) if i < self.fonts.len() => ids.push(i),
                _ => return MALFORMED,
            }
        }
        let Some(size) = r.f32().filter(|s| s.is_finite() && *s > 0.0 && *s <= MAX_GLYPH_PIXELS) else {
            return MALFORMED;
        };
        let Some(flags) = r.i32() else { return MALFORMED };
        let Some(len) = r.i32().and_then(|l| usize::try_from(l).ok()) else { return MALFORMED };
        let mut text = Vec::with_capacity(len.min(input.len() / 2));
        for _ in 0..len {
            match r.u16() {
                Some(u) => text.push(u),
                None => return MALFORMED,
            }
        }

        let fonts: Vec<&Font> = ids.iter().map(|&i| &self.fonts[i]).collect();
        shape(&fonts, &text, size, flags & 1 != 0, &mut self.shaped);

        let n = self.shaped.len();
        if n * SHAPED_GLYPH_BYTES > output.len() {
            return -(n as i32);
        }
        for (g, out) in self.shaped.iter().zip(output.chunks_exact_mut(SHAPED_GLYPH_BYTES)) {
            out[0..4].copy_from_slice(&(ids[g.font] as i32).to_le_bytes());
            out[4..8].copy_from_slice(&(g.glyph as i32).to_le_bytes());
            out[8..12].copy_from_slice(&(g.cluster as i32).to_le_bytes());
            out[12..16].copy_from_slice(&g.advance.to_le_bytes());
            out[16..20].copy_from_slice(&g.dx.to_le_bytes());
            out[20..24].copy_from_slice(&g.dy.to_le_bytes());
        }
        n as i32
    }

    /// Executes a draw list. Commands, each starting with an i32 opcode:
    /// - RECT: f32 x, y, w, h; u32 argb
    /// - GLYPHS: i32 fontId; f32 size; u32 argb; i32 count; count × (i32 glyph, f32 x, f32 y)
    ///   where (x, y) is the glyph origin on the baseline, in target pixels
    /// - PATH: u32 argb; i32 segments; each an i32 kind then 2 (move, line), 4 (quad) or
    ///   0 (close) f32 coordinates
    /// Returns 0, or -1 if the list was malformed (everything before the fault is drawn).
    pub fn render(&mut self, target: &mut Target, clip: Clip, commands: &[u8]) -> i32 {
        let mut r = Reader::new(commands);
        while !r.at_end() {
            let ok = match r.i32() {
                Some(OP_RECT) => self.rect(&mut r, target, clip),
                Some(OP_GLYPHS) => self.glyph_run(&mut r, target, clip),
                Some(OP_PATH) => Self::path(&mut r, target, clip),
                _ => None,
            };
            if ok.is_none() {
                return -1;
            }
        }
        0
    }

    fn rect(&mut self, r: &mut Reader, target: &mut Target, clip: Clip) -> Option<()> {
        let (x, y, w, h) = (r.f32()?, r.f32()?, r.f32()?, r.f32()?);
        let color = Color::from_argb(r.u32()?);
        target.fill_rect(clip, x, y, w, h, color);
        Some(())
    }

    fn glyph_run(&mut self, r: &mut Reader, target: &mut Target, clip: Clip) -> Option<()> {
        let font_id = usize::try_from(r.i32()?).ok()?;
        let size = r.f32()?;
        let color = Color::from_argb(r.u32()?);
        let count = usize::try_from(r.i32()?).ok()?;
        if font_id >= self.fonts.len() || !(size.is_finite() && size > 0.0 && size <= MAX_GLYPH_PIXELS) {
            return None;
        }
        let size_64ths = (size * 64.0).round() as u32;
        for _ in 0..count {
            let glyph = u16::try_from(r.i32()?).ok()?;
            let (x, y) = (r.f32()?, r.f32()?);
            if !(x.is_finite() && y.is_finite()) || x.abs() > 1e6 || y.abs() > 1e6 {
                continue;
            }
            let whole_x = x.floor();
            let mut subpixel = ((x - whole_x) * SUBPIXEL_STEPS).round();
            let mut origin_x = whole_x as i32;
            if subpixel >= SUBPIXEL_STEPS {
                subpixel = 0.0;
                origin_x += 1;
            }
            // The baseline is snapped to a whole pixel so every line's text is equally crisp.
            let origin_y = y.round() as i32;
            let key = GlyphKey { font: font_id as u32, glyph, size_64ths, subpixel: subpixel as u8 };
            if self.glyphs.len() >= MAX_CACHED_GLYPHS && !self.glyphs.contains_key(&key) {
                self.glyphs.clear();
            }
            let font = &self.fonts[font_id];
            let g = self.glyphs.entry(key).or_insert_with(|| rasterize_glyph(font, glyph, size, subpixel / SUBPIXEL_STEPS));
            target.blend_mask(clip, origin_x + g.left, origin_y + g.top, &g.mask, color);
        }
        Some(())
    }

    fn path(r: &mut Reader, target: &mut Target, clip: Clip) -> Option<()> {
        let color = Color::from_argb(r.u32()?);
        let count = usize::try_from(r.i32()?).ok()?;
        let mut path = Path::new();
        for _ in 0..count {
            match r.i32()? {
                SEG_MOVE => path.move_to(r.f32()?, r.f32()?),
                SEG_LINE => path.line_to(r.f32()?, r.f32()?),
                SEG_QUAD => path.quad_to(r.f32()?, r.f32()?, r.f32()?, r.f32()?),
                SEG_CLOSE => path.close(),
                _ => return None,
            }
        }
        target.fill_path(clip, &path, color);
        Some(())
    }
}

/// Rasterizes one glyph at `size` pixels per em, shifted right by `subpixel` (0..1), with the
/// origin on the baseline. Font units are y-up, pixels are y-down, hence the flip.
fn rasterize_glyph(font: &Font, glyph: u16, size: f32, subpixel: f32) -> GlyphMask {
    let scale = size / font.units_per_em as f32;
    let t = Transform { a: scale, b: 0.0, c: 0.0, d: -scale, e: subpixel, f: 0.0 };
    let mut path = Path::new();
    let empty = GlyphMask { left: 0, top: 0, mask: Mask { width: 0, height: 0, coverage: Vec::new() } };
    if font.outline(glyph, &t, &mut path).is_none() {
        return empty;
    }
    let Some((x0, y0, x1, y1)) = path.bounds() else { return empty };
    if (x1 - x0) > MAX_GLYPH_PIXELS || (y1 - y0) > MAX_GLYPH_PIXELS {
        return empty;
    }
    let area = Clip { left: x0.floor() as i32, top: y0.floor() as i32, right: x1.ceil() as i32 + 1, bottom: y1.ceil() as i32 + 1 };
    GlyphMask { left: area.left, top: area.top, mask: path.rasterize(area) }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine_with_roboto() -> (Engine, i32) {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets/fonts/Roboto-Regular.ttf");
        let mut e = Engine { fonts: Vec::new(), glyphs: HashMap::new(), shaped: Vec::new() };
        let id = e.load_font(&std::fs::read(path).unwrap());
        (e, id)
    }

    fn shape_input(font: i32, size: f32, text: &str) -> Vec<u8> {
        let mut b = Vec::new();
        b.extend(1i32.to_le_bytes());
        b.extend(font.to_le_bytes());
        b.extend(size.to_le_bytes());
        b.extend(0i32.to_le_bytes());
        let units: Vec<u16> = text.encode_utf16().collect();
        b.extend((units.len() as i32).to_le_bytes());
        for u in units {
            b.extend(u.to_le_bytes());
        }
        b
    }

    #[test]
    fn bytes_that_are_not_a_font_are_refused() {
        let (mut e, _) = engine_with_roboto();
        assert_eq!(e.load_font(b"definitely not a font"), -1);
    }

    #[test]
    fn shaping_reports_how_much_room_it_needs_when_the_output_is_too_small() {
        let (mut e, id) = engine_with_roboto();
        let mut small = vec![0u8; 24];
        assert_eq!(e.shape(&shape_input(id, 16.0, "abc"), &mut small), -3);
        let mut enough = vec![0u8; 72];
        assert_eq!(e.shape(&shape_input(id, 16.0, "abc"), &mut enough), 3);
    }

    #[test]
    fn shaping_refuses_an_unknown_font_or_a_length_longer_than_the_input() {
        let (mut e, id) = engine_with_roboto();
        let mut out = vec![0u8; 1024];
        assert_eq!(e.shape(&shape_input(id + 5, 16.0, "a"), &mut out), i32::MIN);
        let mut lying = shape_input(id, 16.0, "a");
        let len_at = lying.len() - 6;
        lying[len_at..len_at + 4].copy_from_slice(&1000i32.to_le_bytes());
        assert_eq!(e.shape(&lying, &mut out), i32::MIN);
    }

    #[test]
    fn a_glyph_run_puts_ink_where_the_letter_is() {
        let (mut e, id) = engine_with_roboto();
        let glyph = e.fonts[id as usize].glyph_index('H' as u32) as i32;
        let mut cmds = Vec::new();
        for v in [OP_GLYPHS, id] {
            cmds.extend(v.to_le_bytes());
        }
        cmds.extend(40.0f32.to_le_bytes());
        cmds.extend(0xFF000000u32.to_le_bytes());
        cmds.extend(1i32.to_le_bytes());
        cmds.extend(glyph.to_le_bytes());
        cmds.extend(10.0f32.to_le_bytes());
        cmds.extend(50.0f32.to_le_bytes());

        let mut px = vec![0u8; 80 * 80 * 4];
        let mut t = Target::new(&mut px, 80, 80, 320).unwrap();
        let all = t.bounds();
        assert_eq!(e.render(&mut t, all, &cmds), 0);
        // The H's left stem sits just right of the origin, above the baseline.
        let stem = (12..18).any(|x| t.pixel(x, 40)[3] == 255);
        assert!(stem, "expected solid ink in the left stem");
        assert_eq!(t.pixel(5, 40)[3], 0, "nothing left of the glyph");
        assert_eq!(t.pixel(14, 60)[3], 0, "nothing below the baseline");
    }

    #[test]
    fn a_truncated_draw_list_is_reported_after_drawing_what_was_complete() {
        let (mut e, _) = engine_with_roboto();
        let mut cmds = Vec::new();
        cmds.extend(OP_RECT.to_le_bytes());
        for v in [0.0f32, 0.0, 2.0, 2.0] {
            cmds.extend(v.to_le_bytes());
        }
        cmds.extend(0xFFFF0000u32.to_le_bytes());
        cmds.extend(OP_RECT.to_le_bytes());
        cmds.extend(1.0f32.to_le_bytes());
        let mut px = vec![0u8; 4 * 4 * 4];
        let mut t = Target::new(&mut px, 4, 4, 16).unwrap();
        let all = t.bounds();
        assert_eq!(e.render(&mut t, all, &cmds), -1);
        assert_eq!(t.pixel(0, 0), [255, 0, 0, 255]);
    }
}
