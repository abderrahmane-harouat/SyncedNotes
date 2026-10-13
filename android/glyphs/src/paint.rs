//! Compositing into a pixel buffer: rectangles, glyph masks and filled paths.
//!
//! Pixels are RGBA bytes, premultiplied, which is the layout of an Android `ARGB_8888` bitmap in
//! memory. Working on bytes rather than packed integers keeps this independent of endianness.

use crate::font::OutlineSink;
use crate::raster::{RasterSink, Rasterizer};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Color {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl Color {
    /// From the 0xAARRGGBB form Kotlin uses, not premultiplied.
    pub fn from_argb(argb: u32) -> Color {
        Color { a: (argb >> 24) as u8, r: (argb >> 16) as u8, g: (argb >> 8) as u8, b: argb as u8 }
    }
}

/// A clip rectangle in whole pixels, right and bottom exclusive.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Clip {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl Clip {
    pub fn is_empty(&self) -> bool {
        self.left >= self.right || self.top >= self.bottom
    }

    pub fn intersect(&self, o: &Clip) -> Clip {
        Clip {
            left: self.left.max(o.left),
            top: self.top.max(o.top),
            right: self.right.min(o.right),
            bottom: self.bottom.min(o.bottom),
        }
    }
}

pub struct Target<'a> {
    pixels: &'a mut [u8],
    width: usize,
    height: usize,
    stride: usize,
}

impl<'a> Target<'a> {
    /// Refuses a buffer too small for the size it claims, so no write can land past its end.
    pub fn new(pixels: &'a mut [u8], width: usize, height: usize, stride: usize) -> Option<Target<'a>> {
        if stride < width.checked_mul(4)? {
            return None;
        }
        if height > 0 && pixels.len() < stride.checked_mul(height - 1)?.checked_add(width * 4)? {
            return None;
        }
        Some(Target { pixels, width, height, stride })
    }

    pub fn bounds(&self) -> Clip {
        Clip { left: 0, top: 0, right: self.width as i32, bottom: self.height as i32 }
    }

    pub fn pixel(&self, x: usize, y: usize) -> [u8; 4] {
        let i = y * self.stride + x * 4;
        [self.pixels[i], self.pixels[i + 1], self.pixels[i + 2], self.pixels[i + 3]]
    }

    fn blend(&mut self, x: usize, y: usize, c: Color, coverage: u32) {
        let sa = (c.a as u32 * coverage + 127) / 255;
        if sa == 0 {
            return;
        }
        let i = y * self.stride + x * 4;
        let px = &mut self.pixels[i..i + 4];
        if sa == 255 {
            px.copy_from_slice(&[c.r, c.g, c.b, 255]);
            return;
        }
        let inv = 255 - sa;
        px[0] = ((c.r as u32 * sa + px[0] as u32 * inv + 127) / 255) as u8;
        px[1] = ((c.g as u32 * sa + px[1] as u32 * inv + 127) / 255) as u8;
        px[2] = ((c.b as u32 * sa + px[2] as u32 * inv + 127) / 255) as u8;
        px[3] = (sa + (px[3] as u32 * inv + 127) / 255) as u8;
    }

    /// Fills a rectangle with exact coverage on fractional edges, so a caret or a selection
    /// edge that falls between pixels looks the same as one drawn as a path.
    pub fn fill_rect(&mut self, clip: Clip, x: f32, y: f32, w: f32, h: f32, color: Color) {
        if !(x.is_finite() && y.is_finite() && w.is_finite() && h.is_finite()) || w <= 0.0 || h <= 0.0 {
            return;
        }
        let (x1, y1) = (x + w, y + h);
        let area = Clip {
            left: x.floor().max(-1e6) as i32,
            top: y.floor().max(-1e6) as i32,
            right: x1.ceil().min(1e6) as i32,
            bottom: y1.ceil().min(1e6) as i32,
        };
        let c = clip.intersect(&self.bounds()).intersect(&area);
        if c.is_empty() {
            return;
        }
        for py in c.top..c.bottom {
            let cy = (y1.min(py as f32 + 1.0) - y.max(py as f32)).clamp(0.0, 1.0);
            for px in c.left..c.right {
                let cx = (x1.min(px as f32 + 1.0) - x.max(px as f32)).clamp(0.0, 1.0);
                let coverage = (cx * cy * 255.0 + 0.5) as u32;
                self.blend(px as usize, py as usize, color, coverage);
            }
        }
    }

    /// Blends an 8-bit coverage mask whose top-left pixel sits at (left, top).
    pub fn blend_mask(&mut self, clip: Clip, left: i32, top: i32, mask: &Mask, color: Color) {
        let area = Clip {
            left,
            top,
            right: left.saturating_add(mask.width as i32),
            bottom: top.saturating_add(mask.height as i32),
        };
        let c = clip.intersect(&self.bounds()).intersect(&area);
        if c.is_empty() {
            return;
        }
        for py in c.top..c.bottom {
            let row = (py - top) as usize * mask.width;
            for px in c.left..c.right {
                let coverage = mask.coverage[row + (px - left) as usize];
                if coverage != 0 {
                    self.blend(px as usize, py as usize, color, coverage as u32);
                }
            }
        }
    }

    pub fn fill_path(&mut self, clip: Clip, path: &Path, color: Color) {
        let Some((min_x, min_y, max_x, max_y)) = path.bounds() else { return };
        let area = Clip {
            left: min_x.floor().max(-1e6) as i32,
            top: min_y.floor().max(-1e6) as i32,
            right: max_x.ceil().min(1e6) as i32 + 1,
            bottom: max_y.ceil().min(1e6) as i32 + 1,
        };
        let c = clip.intersect(&self.bounds()).intersect(&area);
        if c.is_empty() {
            return;
        }
        let mask = path.rasterize(c);
        self.blend_mask(c, c.left, c.top, &mask, color);
    }
}

pub struct Mask {
    pub width: usize,
    pub height: usize,
    pub coverage: Vec<u8>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Segment {
    Move(f32, f32),
    Line(f32, f32),
    Quad(f32, f32, f32, f32),
    Close,
}

/// A recorded outline, so its bounds are known before anything is rasterized.
#[derive(Default)]
pub struct Path {
    segments: Vec<Segment>,
}

impl Path {
    pub fn new() -> Path {
        Path::default()
    }

    pub fn bounds(&self) -> Option<(f32, f32, f32, f32)> {
        let mut b: Option<(f32, f32, f32, f32)> = None;
        let mut add = |x: f32, y: f32| {
            if !(x.is_finite() && y.is_finite()) {
                return;
            }
            b = Some(match b {
                None => (x, y, x, y),
                Some((a, c, d, e)) => (a.min(x), c.min(y), d.max(x), e.max(y)),
            });
        };
        for s in &self.segments {
            match *s {
                Segment::Move(x, y) | Segment::Line(x, y) => add(x, y),
                // The control point bounds the curve, so including it is safe if slightly loose.
                Segment::Quad(cx, cy, x, y) => {
                    add(cx, cy);
                    add(x, y);
                }
                Segment::Close => {}
            }
        }
        b
    }

    /// Rasterizes the part of the path inside `area` into a mask the size of `area`.
    pub fn rasterize(&self, area: Clip) -> Mask {
        let width = (area.right - area.left).max(0) as usize;
        let height = (area.bottom - area.top).max(0) as usize;
        let mut raster = Rasterizer::new(width, height);
        {
            let mut sink = RasterSink::new(&mut raster, -area.left as f32, -area.top as f32);
            for s in &self.segments {
                match *s {
                    Segment::Move(x, y) => sink.move_to(x, y),
                    Segment::Line(x, y) => sink.line_to(x, y),
                    Segment::Quad(cx, cy, x, y) => sink.quad_to(cx, cy, x, y),
                    Segment::Close => sink.close(),
                }
            }
            sink.finish();
        }
        let mut coverage = vec![0u8; width * height];
        raster.coverage(&mut coverage);
        Mask { width, height, coverage }
    }
}

impl OutlineSink for Path {
    fn move_to(&mut self, x: f32, y: f32) {
        self.segments.push(Segment::Move(x, y));
    }
    fn line_to(&mut self, x: f32, y: f32) {
        self.segments.push(Segment::Line(x, y));
    }
    fn quad_to(&mut self, cx: f32, cy: f32, x: f32, y: f32) {
        self.segments.push(Segment::Quad(cx, cy, x, y));
    }
    fn close(&mut self) {
        self.segments.push(Segment::Close);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const WHITE: Color = Color { r: 255, g: 255, b: 255, a: 255 };
    const RED: Color = Color { r: 255, g: 0, b: 0, a: 255 };

    #[test]
    fn a_buffer_smaller_than_its_claimed_size_is_refused() {
        let mut px = vec![0u8; 15];
        assert!(Target::new(&mut px, 2, 2, 8).is_none());
        let mut px = vec![0u8; 16];
        assert!(Target::new(&mut px, 2, 2, 8).is_some());
    }

    #[test]
    fn a_rectangle_never_writes_outside_the_clip() {
        let mut px = vec![0u8; 4 * 4 * 4];
        let mut t = Target::new(&mut px, 4, 4, 16).unwrap();
        let clip = Clip { left: 1, top: 1, right: 3, bottom: 3 };
        t.fill_rect(clip, -100.0, -100.0, 1000.0, 1000.0, RED);
        for y in 0..4 {
            for x in 0..4 {
                let inside = (1..3).contains(&x) && (1..3).contains(&y);
                assert_eq!(t.pixel(x, y)[0], if inside { 255 } else { 0 });
            }
        }
    }

    #[test]
    fn a_half_transparent_colour_over_white_blends_halfway() {
        let mut px = vec![0u8; 4];
        let mut t = Target::new(&mut px, 1, 1, 4).unwrap();
        let all = t.bounds();
        t.fill_rect(all, 0.0, 0.0, 1.0, 1.0, WHITE);
        t.fill_rect(all, 0.0, 0.0, 1.0, 1.0, Color { r: 0, g: 0, b: 0, a: 128 });
        assert_eq!(t.pixel(0, 0), [127, 127, 127, 255]);
    }

    #[test]
    fn a_path_is_filled_only_inside_its_outline() {
        let mut px = vec![0u8; 8 * 8 * 4];
        let mut t = Target::new(&mut px, 8, 8, 32).unwrap();
        let mut p = Path::new();
        p.move_to(2.0, 2.0);
        p.line_to(6.0, 2.0);
        p.line_to(6.0, 6.0);
        p.line_to(2.0, 6.0);
        p.close();
        let all = t.bounds();
        t.fill_path(all, &p, RED);
        assert_eq!(t.pixel(3, 3), [255, 0, 0, 255]);
        assert_eq!(t.pixel(1, 3), [0, 0, 0, 0]);
        assert_eq!(t.pixel(6, 3), [0, 0, 0, 0]);
    }
}
