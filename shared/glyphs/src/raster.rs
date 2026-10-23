//! Anti-aliased scanline rasterizer for filled paths (glyphs, handles, anything with a curve).
//!
//! It uses the signed-area accumulation technique popularised by Raph Levien's font-rs: each
//! edge adds the exact area it sweeps to an accumulation buffer, and a running sum across each
//! row turns those deltas into coverage. Coverage is exact for straight edges, needs no sorting
//! of edges, and the inner loop is plain arithmetic over a flat array.

use crate::font::OutlineSink;

pub struct Rasterizer {
    width: usize,
    height: usize,
    // Two extra columns: an edge on the right border writes one or two cells past `width`.
    stride: usize,
    acc: Vec<f32>,
}

impl Rasterizer {
    pub fn new(width: usize, height: usize) -> Rasterizer {
        let stride = width + 2;
        Rasterizer { width, height, stride, acc: vec![0.0; stride * height] }
    }

    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    /// Adds one edge. Coordinates may lie outside the buffer: the edge is clipped vertically,
    /// and horizontally it is clamped to the borders, which keeps the winding right for every
    /// pixel inside (a part of the path left of the buffer still counts for pixels to its right).
    pub fn line(&mut self, x0: f32, y0: f32, x1: f32, y1: f32) {
        if !(x0.is_finite() && y0.is_finite() && x1.is_finite() && y1.is_finite()) || y0 == y1 {
            return;
        }
        let (dir, x0, y0, x1, y1) = if y0 < y1 { (1.0, x0, y0, x1, y1) } else { (-1.0, x1, y1, x0, y0) };
        let dxdy = (x1 - x0) / (y1 - y0);
        let top = y0.max(0.0);
        let bottom = y1.min(self.height as f32);
        if top >= bottom {
            return;
        }
        let wf = self.width as f32;
        let mut x = x0 + (top - y0) * dxdy;
        let first_row = top as usize;
        let end_row = (bottom.ceil() as usize).min(self.height);
        for row in first_row..end_row {
            let row_top = (row as f32).max(top);
            let row_bottom = ((row + 1) as f32).min(bottom);
            let dy = row_bottom - row_top;
            if dy <= 0.0 {
                continue;
            }
            let x_next = x + dxdy * dy;
            let d = dy * dir;
            let (xa, xb) = {
                let a = x.clamp(0.0, wf);
                let b = x_next.clamp(0.0, wf);
                if a < b { (a, b) } else { (b, a) }
            };
            self.accumulate_row(row * self.stride, xa, xb, d);
            x = x_next;
        }
    }

    /// Spreads the signed height `d` of an edge crossing one row from `x0` to `x1` (x0 <= x1)
    /// over the cells it touches, as the area to the right of the edge within each cell.
    fn accumulate_row(&mut self, base: usize, x0: f32, x1: f32, d: f32) {
        let acc = &mut self.acc[base..base + self.stride];
        let x0i = x0.floor() as usize;
        let x1_ceil = x1.ceil();
        let x1i = x1_ceil as usize;
        if x1i <= x0i + 1 {
            // The edge stays within one cell: split by where its midpoint falls.
            let mid = 0.5 * (x0 + x1) - x0i as f32;
            acc[x0i] += d - d * mid;
            acc[x0i + 1] += d * mid;
        } else {
            let s = 1.0 / (x1 - x0);
            let x0f = x0 - x0i as f32;
            let first = 0.5 * s * (1.0 - x0f) * (1.0 - x0f);
            let x1f = x1 - x1_ceil + 1.0;
            let last = 0.5 * s * x1f * x1f;
            acc[x0i] += d * first;
            if x1i == x0i + 2 {
                acc[x0i + 1] += d * (1.0 - first - last);
            } else {
                let second = s * (1.5 - x0f);
                acc[x0i + 1] += d * (second - first);
                for cell in &mut acc[x0i + 2..x1i - 1] {
                    *cell += d * s;
                }
                let before_last = second + (x1i - x0i - 3) as f32 * s;
                acc[x1i - 1] += d * (1.0 - before_last - last);
            }
            acc[x1i] += d * last;
        }
    }

    pub fn quad(&mut self, x0: f32, y0: f32, cx: f32, cy: f32, x1: f32, y1: f32) {
        // Split into enough lines that each deviates from the curve by well under a pixel;
        // the deviation of a quadratic shrinks with the square of the number of pieces.
        let ddx = x0 - 2.0 * cx + x1;
        let ddy = y0 - 2.0 * cy + y1;
        let dev_sq = ddx * ddx + ddy * ddy;
        if !(dev_sq >= 0.333) {
            self.line(x0, y0, x1, y1);
            return;
        }
        let pieces = 1 + (3.0 * dev_sq).sqrt().sqrt().floor().min(256.0) as usize;
        let mut px = x0;
        let mut py = y0;
        for i in 1..=pieces {
            let t = i as f32 / pieces as f32;
            let mt = 1.0 - t;
            let x = mt * mt * x0 + 2.0 * mt * t * cx + t * t * x1;
            let y = mt * mt * y0 + 2.0 * mt * t * cy + t * t * y1;
            self.line(px, py, x, y);
            px = x;
            py = y;
        }
    }

    /// Converts the accumulated edges into 8-bit coverage, `width` bytes per row. Overlapping
    /// shapes wound the same way saturate at full coverage, which is what glyph outlines and
    /// our own handle shapes rely on.
    pub fn coverage(&self, out: &mut [u8]) {
        for row in 0..self.height {
            let acc = &self.acc[row * self.stride..row * self.stride + self.width];
            let dst = &mut out[row * self.width..(row + 1) * self.width];
            let mut sum = 0.0f32;
            for (cell, px) in acc.iter().zip(dst.iter_mut()) {
                sum += *cell;
                *px = (sum.abs().min(1.0) * 255.0 + 0.5) as u8;
            }
        }
    }
}

/// Feeds an outline into a rasterizer, translated by (dx, dy). Contours are closed even when the
/// source forgets to, because an open contour would leave a streak of coverage to the right.
pub struct RasterSink<'a> {
    pub raster: &'a mut Rasterizer,
    pub dx: f32,
    pub dy: f32,
    start: (f32, f32),
    current: (f32, f32),
}

impl<'a> RasterSink<'a> {
    pub fn new(raster: &'a mut Rasterizer, dx: f32, dy: f32) -> RasterSink<'a> {
        RasterSink { raster, dx, dy, start: (0.0, 0.0), current: (0.0, 0.0) }
    }

    pub fn finish(&mut self) {
        self.close();
    }
}

impl OutlineSink for RasterSink<'_> {
    fn move_to(&mut self, x: f32, y: f32) {
        self.close();
        self.start = (x + self.dx, y + self.dy);
        self.current = self.start;
    }

    fn line_to(&mut self, x: f32, y: f32) {
        let p = (x + self.dx, y + self.dy);
        self.raster.line(self.current.0, self.current.1, p.0, p.1);
        self.current = p;
    }

    fn quad_to(&mut self, cx: f32, cy: f32, x: f32, y: f32) {
        let p = (x + self.dx, y + self.dy);
        self.raster.quad(self.current.0, self.current.1, cx + self.dx, cy + self.dy, p.0, p.1);
        self.current = p;
    }

    fn close(&mut self) {
        if self.current != self.start {
            self.raster.line(self.current.0, self.current.1, self.start.0, self.start.1);
            self.current = self.start;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fill_rect(r: &mut Rasterizer, x0: f32, y0: f32, x1: f32, y1: f32) {
        let mut s = RasterSink::new(r, 0.0, 0.0);
        s.move_to(x0, y0);
        s.line_to(x1, y0);
        s.line_to(x1, y1);
        s.line_to(x0, y1);
        s.finish();
    }

    #[test]
    fn a_pixel_aligned_square_is_fully_covered_inside_and_empty_outside() {
        let mut r = Rasterizer::new(8, 8);
        fill_rect(&mut r, 2.0, 2.0, 6.0, 6.0);
        let mut out = vec![0u8; 64];
        r.coverage(&mut out);
        for y in 0..8 {
            for x in 0..8 {
                let inside = (2..6).contains(&x) && (2..6).contains(&y);
                assert_eq!(out[y * 8 + x], if inside { 255 } else { 0 }, "pixel {x},{y}");
            }
        }
    }

    #[test]
    fn a_half_pixel_edge_gives_half_coverage() {
        let mut r = Rasterizer::new(4, 1);
        fill_rect(&mut r, 1.5, 0.0, 3.0, 1.0);
        let mut out = vec![0u8; 4];
        r.coverage(&mut out);
        assert_eq!(out, vec![0, 128, 255, 0]);
    }

    #[test]
    fn a_path_wider_than_the_buffer_is_clipped_without_losing_its_winding() {
        let mut r = Rasterizer::new(4, 2);
        fill_rect(&mut r, -10.0, -5.0, 2.0, 50.0);
        let mut out = vec![0u8; 8];
        r.coverage(&mut out);
        assert_eq!(out, vec![255, 255, 0, 0, 255, 255, 0, 0]);
    }

    #[test]
    fn the_same_shape_drawn_twice_still_saturates_at_full_coverage() {
        let mut r = Rasterizer::new(4, 4);
        fill_rect(&mut r, 0.0, 0.0, 4.0, 4.0);
        fill_rect(&mut r, 0.0, 0.0, 4.0, 4.0);
        let mut out = vec![0u8; 16];
        r.coverage(&mut out);
        assert!(out.iter().all(|&c| c == 255));
    }

    #[test]
    fn non_finite_coordinates_are_ignored() {
        let mut r = Rasterizer::new(4, 4);
        r.line(f32::NAN, 0.0, 2.0, 4.0);
        r.line(0.0, f32::INFINITY, 2.0, 4.0);
        let mut out = vec![0u8; 16];
        r.coverage(&mut out);
        assert!(out.iter().all(|&c| c == 0));
    }
}
