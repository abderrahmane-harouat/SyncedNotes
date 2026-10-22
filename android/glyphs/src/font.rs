//! Reads TrueType fonts (OpenType with `glyf` outlines).
//!
//! A font file is input we did not write, so every read is bounds-checked and a malformed file
//! yields `None` rather than a panic or an out-of-range read.

/// Receives a glyph outline, already transformed into the caller's coordinate space.
pub trait OutlineSink {
    fn move_to(&mut self, x: f32, y: f32);
    fn line_to(&mut self, x: f32, y: f32);
    fn quad_to(&mut self, cx: f32, cy: f32, x: f32, y: f32);
    fn close(&mut self);
}

/// An affine transform: x' = a·x + c·y + e, y' = b·x + d·y + f.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Transform {
    pub a: f32,
    pub b: f32,
    pub c: f32,
    pub d: f32,
    pub e: f32,
    pub f: f32,
}

impl Transform {
    pub const IDENTITY: Transform = Transform { a: 1.0, b: 0.0, c: 0.0, d: 1.0, e: 0.0, f: 0.0 };

    pub fn apply(&self, x: f32, y: f32) -> (f32, f32) {
        (self.a * x + self.c * y + self.e, self.b * x + self.d * y + self.f)
    }

    /// `self` applied after `inner`.
    pub fn then(&self, inner: &Transform) -> Transform {
        Transform {
            a: self.a * inner.a + self.c * inner.b,
            b: self.b * inner.a + self.d * inner.b,
            c: self.a * inner.c + self.c * inner.d,
            d: self.b * inner.c + self.d * inner.d,
            e: self.a * inner.e + self.c * inner.f + self.e,
            f: self.b * inner.e + self.d * inner.f + self.f,
        }
    }
}

#[derive(Clone, Copy, Debug)]
enum Cmap {
    /// Segment mapping, BMP only.
    Format4(usize),
    /// Segmented coverage, the full Unicode range.
    Format12(usize),
}

#[derive(Clone, Copy, Debug)]
struct Table {
    offset: usize,
    length: usize,
}

pub struct Font {
    data: Vec<u8>,
    pub units_per_em: u16,
    pub ascender: i16,
    pub descender: i16,
    pub line_gap: i16,
    /// Where the type designer put underline and strikethrough, in font units (y up).
    pub underline_position: i16,
    pub underline_thickness: i16,
    pub strikeout_position: i16,
    pub strikeout_size: i16,
    num_glyphs: u16,
    long_loca: bool,
    num_h_metrics: u16,
    loca: Table,
    glyf: Table,
    hmtx: Table,
    cmap: Option<Cmap>,
    /// OpenType layout: glyph classes, substitutions (Arabic joining, ligatures), positioning.
    gdef: Option<Table>,
    gsub: Option<Table>,
    gpos: Option<Table>,
}

// Composite glyphs nest; a hostile file could make them recurse forever.
const MAX_COMPOSITE_DEPTH: u32 = 8;

fn bytes(d: &[u8], offset: usize, len: usize) -> Option<&[u8]> {
    d.get(offset..offset.checked_add(len)?)
}

fn u8_at(d: &[u8], o: usize) -> Option<u8> {
    d.get(o).copied()
}

fn i8_at(d: &[u8], o: usize) -> Option<i8> {
    d.get(o).map(|&b| b as i8)
}

fn u16_at(d: &[u8], o: usize) -> Option<u16> {
    bytes(d, o, 2).map(|b| u16::from_be_bytes([b[0], b[1]]))
}

fn i16_at(d: &[u8], o: usize) -> Option<i16> {
    u16_at(d, o).map(|v| v as i16)
}

fn u32_at(d: &[u8], o: usize) -> Option<u32> {
    bytes(d, o, 4).map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}

fn f2dot14_at(d: &[u8], o: usize) -> Option<f32> {
    i16_at(d, o).map(|v| v as f32 / 16384.0)
}

impl Font {
    pub fn parse(data: Vec<u8>) -> Option<Font> {
        let d = &data[..];
        let num_tables = u16_at(d, 4)? as usize;
        let find = |tag: &[u8; 4]| -> Option<Table> {
            for i in 0..num_tables {
                let record = 12 + 16 * i;
                if bytes(d, record, 4)? == tag {
                    let offset = u32_at(d, record + 8)? as usize;
                    let length = u32_at(d, record + 12)? as usize;
                    bytes(d, offset, length)?;
                    return Some(Table { offset, length });
                }
            }
            None
        };

        let head = find(b"head")?;
        let hhea = find(b"hhea")?;
        let maxp = find(b"maxp")?;
        let hmtx = find(b"hmtx")?;
        // Only TrueType outlines for now; a CFF font has no glyf/loca and is refused outright.
        let loca = find(b"loca")?;
        let glyf = find(b"glyf")?;

        let units_per_em = u16_at(d, head.offset + 18)?;
        if units_per_em == 0 {
            return None;
        }
        let long_loca = i16_at(d, head.offset + 50)? != 0;
        let num_glyphs = u16_at(d, maxp.offset + 4)?;
        let num_h_metrics = u16_at(d, hhea.offset + 34)?;
        if num_h_metrics == 0 || (num_h_metrics as usize) * 4 > hmtx.length {
            return None;
        }

        let cmap = find(b"cmap").and_then(|t| Self::pick_cmap(d, t));
        let (gdef, gsub, gpos) = (find(b"GDEF"), find(b"GSUB"), find(b"GPOS"));

        // Both tables are optional in practice; fall back to proportions typical of text faces.
        let em = units_per_em as i32;
        let (underline_position, underline_thickness) = find(b"post")
            .and_then(|t| Some((i16_at(d, t.offset + 8)?, i16_at(d, t.offset + 10)?)))
            .filter(|&(_, thickness)| thickness > 0)
            .unwrap_or((-(em / 10) as i16, (em / 20) as i16));
        let (strikeout_size, strikeout_position) = find(b"OS/2")
            .and_then(|t| Some((i16_at(d, t.offset + 26)?, i16_at(d, t.offset + 28)?)))
            .filter(|&(size, _)| size > 0)
            .unwrap_or(((em / 20) as i16, (em / 4) as i16));

        Some(Font {
            units_per_em,
            ascender: i16_at(d, hhea.offset + 4)?,
            descender: i16_at(d, hhea.offset + 6)?,
            line_gap: i16_at(d, hhea.offset + 8)?,
            underline_position,
            underline_thickness,
            strikeout_position,
            strikeout_size,
            num_glyphs,
            long_loca,
            num_h_metrics,
            loca,
            glyf,
            hmtx,
            cmap,
            gdef,
            gsub,
            gpos,
            data,
        })
    }

    fn pick_cmap(d: &[u8], table: Table) -> Option<Cmap> {
        let count = u16_at(d, table.offset + 2)? as usize;
        let mut best: Option<(u8, Cmap)> = None;
        for i in 0..count {
            let record = table.offset + 4 + 8 * i;
            let platform = u16_at(d, record)?;
            let encoding = u16_at(d, record + 2)?;
            let sub = table.offset + u32_at(d, record + 4)? as usize;
            let format = u16_at(d, sub)?;
            // Prefer full-Unicode tables so characters outside the BMP (emoji, historic
            // scripts) map at all; fall back to the BMP-only table.
            let rank = match (platform, encoding, format) {
                (3, 10, 12) | (0, 4, 12) | (0, 6, 12) => 2,
                (3, 1, 4) | (0, 3, 4) | (0, 1, 4) | (0, 0, 4) => 1,
                _ => continue,
            };
            let cmap = if format == 12 { Cmap::Format12(sub) } else { Cmap::Format4(sub) };
            if best.map_or(true, |(r, _)| rank > r) {
                best = Some((rank, cmap));
            }
        }
        best.map(|(_, c)| c)
    }

    /// The GDEF, GSUB and GPOS tables' bytes, each when the font has it.
    pub fn layout_tables(&self) -> (Option<&[u8]>, Option<&[u8]>, Option<&[u8]>) {
        let slice = |t: Option<Table>| t.and_then(|t| bytes(&self.data, t.offset, t.length));
        (slice(self.gdef), slice(self.gsub), slice(self.gpos))
    }

    pub fn num_glyphs(&self) -> u16 {
        self.num_glyphs
    }

    /// The glyph for a code point, or 0 (`.notdef`) when the font does not have one.
    pub fn glyph_index(&self, cp: u32) -> u16 {
        let found = match self.cmap {
            Some(Cmap::Format4(o)) => self.cmap4(o, cp),
            Some(Cmap::Format12(o)) => self.cmap12(o, cp),
            None => None,
        };
        match found {
            Some(g) if g < self.num_glyphs => g,
            _ => 0,
        }
    }

    fn cmap4(&self, sub: usize, cp: u32) -> Option<u16> {
        if cp > 0xFFFF {
            return None;
        }
        let d = &self.data[..];
        let c = cp as u16;
        let seg_count = (u16_at(d, sub + 6)? / 2) as usize;
        let ends = sub + 14;
        let starts = ends + 2 * seg_count + 2;
        let deltas = starts + 2 * seg_count;
        let range_offsets = deltas + 2 * seg_count;

        // endCode is sorted; the first segment whose end is >= c is the only candidate.
        let (mut lo, mut hi) = (0usize, seg_count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            if u16_at(d, ends + 2 * mid)? < c {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= seg_count {
            return None;
        }
        let start = u16_at(d, starts + 2 * lo)?;
        if c < start {
            return None;
        }
        let delta = u16_at(d, deltas + 2 * lo)?;
        let range_offset_pos = range_offsets + 2 * lo;
        let range_offset = u16_at(d, range_offset_pos)? as usize;
        if range_offset == 0 {
            return Some(c.wrapping_add(delta));
        }
        let at = range_offset_pos + range_offset + 2 * (c - start) as usize;
        let g = u16_at(d, at)?;
        if g == 0 { Some(0) } else { Some(g.wrapping_add(delta)) }
    }

    fn cmap12(&self, sub: usize, cp: u32) -> Option<u16> {
        let d = &self.data[..];
        let groups = u32_at(d, sub + 12)? as usize;
        let (mut lo, mut hi) = (0usize, groups);
        while lo < hi {
            let mid = (lo + hi) / 2;
            let g = sub + 16 + 12 * mid;
            let start = u32_at(d, g)?;
            let end = u32_at(d, g + 4)?;
            if cp < start {
                hi = mid;
            } else if cp > end {
                lo = mid + 1;
            } else {
                let glyph = u32_at(d, g + 8)?.checked_add(cp - start)?;
                return u16::try_from(glyph).ok();
            }
        }
        None
    }

    /// Horizontal advance in font units.
    pub fn advance(&self, glyph: u16) -> u16 {
        let d = &self.data[..];
        let i = (glyph.min(self.num_h_metrics - 1)) as usize;
        u16_at(d, self.hmtx.offset + 4 * i).unwrap_or(0)
    }

    fn glyph_range(&self, glyph: u16) -> Option<(usize, usize)> {
        if glyph >= self.num_glyphs {
            return None;
        }
        let d = &self.data[..];
        let i = glyph as usize;
        let (start, end) = if self.long_loca {
            (u32_at(d, self.loca.offset + 4 * i)? as usize, u32_at(d, self.loca.offset + 4 * i + 4)? as usize)
        } else {
            (
                u16_at(d, self.loca.offset + 2 * i)? as usize * 2,
                u16_at(d, self.loca.offset + 2 * i + 2)? as usize * 2,
            )
        };
        if end < start || end > self.glyf.length {
            return None;
        }
        Some((self.glyf.offset + start, end - start))
    }

    /// Emits the outline of `glyph` in font units (y up) through `transform` into `sink`.
    /// An empty glyph (a space) emits nothing and still succeeds.
    pub fn outline(&self, glyph: u16, transform: &Transform, sink: &mut impl OutlineSink) -> Option<()> {
        self.outline_at_depth(glyph, transform, sink, 0)
    }

    fn outline_at_depth(&self, glyph: u16, t: &Transform, sink: &mut impl OutlineSink, depth: u32) -> Option<()> {
        if depth > MAX_COMPOSITE_DEPTH {
            return None;
        }
        let (offset, length) = self.glyph_range(glyph)?;
        if length == 0 {
            return Some(());
        }
        let d = bytes(&self.data, offset, length)?;
        let contours = i16_at(d, 0)?;
        if contours >= 0 {
            self.simple_outline(d, contours as usize, t, sink)
        } else {
            self.composite_outline(d, t, sink, depth)
        }
    }

    fn simple_outline(&self, d: &[u8], contours: usize, t: &Transform, sink: &mut impl OutlineSink) -> Option<()> {
        const ON_CURVE: u8 = 0x01;
        const X_SHORT: u8 = 0x02;
        const Y_SHORT: u8 = 0x04;
        const REPEAT: u8 = 0x08;
        const X_SAME_OR_POSITIVE: u8 = 0x10;
        const Y_SAME_OR_POSITIVE: u8 = 0x20;

        if contours == 0 {
            return Some(());
        }
        let mut p = 10;
        let mut ends = Vec::with_capacity(contours);
        for _ in 0..contours {
            ends.push(u16_at(d, p)? as usize);
            p += 2;
        }
        let points = *ends.last()? + 1;
        let instructions = u16_at(d, p)? as usize;
        p += 2 + instructions;

        let mut flags = Vec::with_capacity(points);
        while flags.len() < points {
            let f = u8_at(d, p)?;
            p += 1;
            flags.push(f);
            if f & REPEAT != 0 {
                let n = u8_at(d, p)?;
                p += 1;
                for _ in 0..n {
                    if flags.len() < points {
                        flags.push(f);
                    }
                }
            }
        }

        let mut xs = Vec::with_capacity(points);
        let mut x: i32 = 0;
        for &f in &flags {
            if f & X_SHORT != 0 {
                let dx = u8_at(d, p)? as i32;
                p += 1;
                x += if f & X_SAME_OR_POSITIVE != 0 { dx } else { -dx };
            } else if f & X_SAME_OR_POSITIVE == 0 {
                x += i16_at(d, p)? as i32;
                p += 2;
            }
            xs.push(x);
        }
        let mut ys = Vec::with_capacity(points);
        let mut y: i32 = 0;
        for &f in &flags {
            if f & Y_SHORT != 0 {
                let dy = u8_at(d, p)? as i32;
                p += 1;
                y += if f & Y_SAME_OR_POSITIVE != 0 { dy } else { -dy };
            } else if f & Y_SAME_OR_POSITIVE == 0 {
                y += i16_at(d, p)? as i32;
                p += 2;
            }
            ys.push(y);
        }

        let mut start = 0;
        let mut contour = Vec::new();
        for &end in &ends {
            if end < start || end >= points {
                return None;
            }
            contour.clear();
            for i in start..=end {
                let (tx, ty) = t.apply(xs[i] as f32, ys[i] as f32);
                contour.push((tx, ty, flags[i] & ON_CURVE != 0));
            }
            emit_contour(&contour, sink);
            start = end + 1;
        }
        Some(())
    }

    fn composite_outline(&self, d: &[u8], t: &Transform, sink: &mut impl OutlineSink, depth: u32) -> Option<()> {
        const ARGS_ARE_WORDS: u16 = 0x0001;
        const ARGS_ARE_XY: u16 = 0x0002;
        const HAVE_SCALE: u16 = 0x0008;
        const MORE_COMPONENTS: u16 = 0x0020;
        const HAVE_XY_SCALE: u16 = 0x0040;
        const HAVE_2X2: u16 = 0x0080;

        let mut p = 10;
        loop {
            let flags = u16_at(d, p)?;
            let component = u16_at(d, p + 2)?;
            p += 4;
            let (arg1, arg2) = if flags & ARGS_ARE_WORDS != 0 {
                let v = (i16_at(d, p)? as f32, i16_at(d, p + 2)? as f32);
                p += 4;
                v
            } else {
                let v = (i8_at(d, p)? as f32, i8_at(d, p + 1)? as f32);
                p += 2;
                v
            };
            // Anchoring components by matching point numbers is rare in text fonts; such a
            // component is placed at the origin rather than refusing the whole glyph.
            let (dx, dy) = if flags & ARGS_ARE_XY != 0 { (arg1, arg2) } else { (0.0, 0.0) };
            let mut local = Transform { e: dx, f: dy, ..Transform::IDENTITY };
            if flags & HAVE_SCALE != 0 {
                let s = f2dot14_at(d, p)?;
                local.a = s;
                local.d = s;
                p += 2;
            } else if flags & HAVE_XY_SCALE != 0 {
                local.a = f2dot14_at(d, p)?;
                local.d = f2dot14_at(d, p + 2)?;
                p += 4;
            } else if flags & HAVE_2X2 != 0 {
                local.a = f2dot14_at(d, p)?;
                local.b = f2dot14_at(d, p + 2)?;
                local.c = f2dot14_at(d, p + 4)?;
                local.d = f2dot14_at(d, p + 6)?;
                p += 8;
            }
            self.outline_at_depth(component, &t.then(&local), sink, depth + 1)?;
            if flags & MORE_COMPONENTS == 0 {
                return Some(());
            }
        }
    }
}

fn midpoint(a: (f32, f32, bool), b: (f32, f32, bool)) -> (f32, f32) {
    ((a.0 + b.0) / 2.0, (a.1 + b.1) / 2.0)
}

/// TrueType contours are quadratic splines where two consecutive off-curve points imply an
/// on-curve point halfway between them.
fn emit_contour(pts: &[(f32, f32, bool)], sink: &mut impl OutlineSink) {
    let n = pts.len();
    if n == 0 {
        return;
    }
    let mut pending: Option<(f32, f32)> = None;
    match pts.iter().position(|p| p.2) {
        Some(first_on) => {
            sink.move_to(pts[first_on].0, pts[first_on].1);
            // Walk once around, ending back on the starting on-curve point.
            for k in 1..=n {
                step(pts[(first_on + k) % n], &mut pending, sink);
            }
        }
        None => {
            // All off-curve: start on the implied point between the last and first.
            let start = midpoint(pts[n - 1], pts[0]);
            sink.move_to(start.0, start.1);
            for &p in pts {
                step(p, &mut pending, sink);
            }
            if let Some(c) = pending.take() {
                sink.quad_to(c.0, c.1, start.0, start.1);
            }
        }
    }
    sink.close();
}

fn step(p: (f32, f32, bool), pending: &mut Option<(f32, f32)>, sink: &mut impl OutlineSink) {
    if p.2 {
        match pending.take() {
            Some(c) => sink.quad_to(c.0, c.1, p.0, p.1),
            None => sink.line_to(p.0, p.1),
        }
    } else {
        if let Some(c) = *pending {
            let m = ((c.0 + p.0) / 2.0, (c.1 + p.1) / 2.0);
            sink.quad_to(c.0, c.1, m.0, m.1);
        }
        *pending = Some((p.0, p.1));
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub fn roboto() -> Font {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets/fonts/Roboto-Regular.ttf");
        Font::parse(std::fs::read(path).expect("Roboto in the app's assets")).expect("Roboto parses")
    }

    #[derive(Default)]
    struct Counter {
        moves: usize,
        segments: usize,
        closes: usize,
    }

    impl OutlineSink for Counter {
        fn move_to(&mut self, _: f32, _: f32) {
            self.moves += 1;
        }
        fn line_to(&mut self, _: f32, _: f32) {
            self.segments += 1;
        }
        fn quad_to(&mut self, _: f32, _: f32, _: f32, _: f32) {
            self.segments += 1;
        }
        fn close(&mut self) {
            self.closes += 1;
        }
    }

    #[test]
    fn roboto_reports_its_metrics() {
        let f = roboto();
        assert_eq!(f.units_per_em, 2048);
        assert!(f.ascender > 0 && f.descender < 0);
        assert!(f.underline_position < 0 && f.underline_thickness > 0);
        assert!(f.strikeout_position > 0 && f.strikeout_size > 0);
    }

    #[test]
    fn letters_map_to_real_glyphs_and_unknown_characters_to_notdef() {
        let f = roboto();
        assert_ne!(f.glyph_index('a' as u32), 0);
        assert_ne!(f.glyph_index('é' as u32), 0);
        assert_eq!(f.glyph_index(0x10FFFD), 0);
    }

    #[test]
    fn the_letter_o_has_two_closed_contours() {
        let f = roboto();
        let mut c = Counter::default();
        f.outline(f.glyph_index('o' as u32), &Transform::IDENTITY, &mut c).unwrap();
        assert_eq!((c.moves, c.closes), (2, 2));
        assert!(c.segments > 4);
    }

    #[test]
    fn a_space_has_an_advance_but_no_outline() {
        let f = roboto();
        let g = f.glyph_index(' ' as u32);
        let mut c = Counter::default();
        f.outline(g, &Transform::IDENTITY, &mut c).unwrap();
        assert_eq!(c.moves, 0);
        assert!(f.advance(g) > 0);
    }

    #[test]
    fn a_truncated_file_is_refused_instead_of_read_past_its_end() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets/fonts/Roboto-Regular.ttf");
        let bytes = std::fs::read(path).unwrap();
        for cut in [0, 3, 12, 100, 400, 4000] {
            let font = Font::parse(bytes[..cut].to_vec());
            assert!(font.is_none(), "a {cut}-byte prefix should not parse");
        }
    }
}
