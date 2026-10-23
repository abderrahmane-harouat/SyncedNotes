//! PNG images: colour emoji are PNGs inside their font, and pictures in notes often are too.
//!
//! The decoder is ours, with no crate, like the rest of this one: zlib's inflate (RFC 1950 and
//! 1951), PNG's chunks, filters, palettes and transparency, for every colour type at 8 bits, and
//! at 1, 2, 4 and 16 where PNG allows them. Interlaced images are refused for now. The input is
//! untrusted, so every read is checked, sizes are capped before anything is allocated, and the
//! checksums are verified: a damaged image is refused, never half drawn.

/// An image as premultiplied RGBA bytes, row after row, the compositor's own layout.
pub struct Image {
    pub width: usize,
    pub height: usize,
    pub rgba: Vec<u8>,
}

// Far beyond any emoji or picture a note shows; refuses decompression bombs early.
const MAX_SIDE: usize = 8192;
const MAX_PIXELS: usize = 32 * 1024 * 1024;

pub fn decode(data: &[u8]) -> Option<Image> {
    const SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
    if data.get(..8)? != SIGNATURE {
        return None;
    }
    let mut p = 8;
    let mut header: Option<(usize, usize, u8, u8, u8)> = None;
    let mut palette: Vec<[u8; 4]> = Vec::new();
    let mut transparent: Option<[u16; 3]> = None;
    let mut compressed: Vec<u8> = Vec::new();
    loop {
        let length = u32::from_be_bytes(data.get(p..p + 4)?.try_into().ok()?) as usize;
        let kind: [u8; 4] = data.get(p + 4..p + 8)?.try_into().ok()?;
        let body = data.get(p + 8..p + 8usize.checked_add(length)?)?;
        let crc = u32::from_be_bytes(data.get(p + 8 + length..p + 12 + length)?.try_into().ok()?);
        if crc32(&data[p + 4..p + 8 + length]) != crc {
            return None;
        }
        match &kind {
            b"IHDR" => {
                if body.len() < 13 {
                    return None;
                }
                let w = u32::from_be_bytes(body[0..4].try_into().ok()?) as usize;
                let h = u32::from_be_bytes(body[4..8].try_into().ok()?) as usize;
                let (depth, color, interlace) = (body[8], body[9], body[12]);
                if w == 0 || h == 0 || w > MAX_SIDE || h > MAX_SIDE || w * h > MAX_PIXELS || body[10] != 0 || body[11] != 0 || interlace != 0 {
                    return None;
                }
                let valid = match color {
                    0 => matches!(depth, 1 | 2 | 4 | 8 | 16),
                    3 => matches!(depth, 1 | 2 | 4 | 8),
                    2 | 4 | 6 => matches!(depth, 8 | 16),
                    _ => false,
                };
                if !valid {
                    return None;
                }
                header = Some((w, h, depth, color, interlace));
            }
            b"PLTE" => {
                palette = body.chunks_exact(3).map(|c| [c[0], c[1], c[2], 255]).collect();
            }
            b"tRNS" => match header?.3 {
                3 => {
                    for (i, &a) in body.iter().enumerate() {
                        if let Some(entry) = palette.get_mut(i) {
                            entry[3] = a;
                        }
                    }
                }
                0 if body.len() >= 2 => transparent = Some([u16::from_be_bytes([body[0], body[1]]), 0, 0]),
                2 if body.len() >= 6 => {
                    transparent = Some([
                        u16::from_be_bytes([body[0], body[1]]),
                        u16::from_be_bytes([body[2], body[3]]),
                        u16::from_be_bytes([body[4], body[5]]),
                    ])
                }
                _ => {}
            },
            b"IDAT" => compressed.extend_from_slice(body),
            b"IEND" => break,
            _ => {}
        }
        p += 12 + length;
    }
    let (width, height, depth, color, _) = header?;
    let channels = match color {
        0 | 3 => 1,
        4 => 2,
        2 => 3,
        _ => 4,
    };
    let bits_per_pixel = channels * depth as usize;
    let row_bytes = (width * bits_per_pixel).div_ceil(8);
    let bpp = bits_per_pixel.div_ceil(8).max(1);
    let raw = zlib_decompress(&compressed, (row_bytes + 1).checked_mul(height)?)?;
    if raw.len() != (row_bytes + 1) * height {
        return None;
    }
    let rows = unfilter(&raw, row_bytes, height, bpp)?;

    let mut rgba = vec![0u8; width * height * 4];
    for y in 0..height {
        let row = &rows[y * row_bytes..(y + 1) * row_bytes];
        for x in 0..width {
            // A sample at any depth, scaled to 8 bits; the raw value too, for tRNS.
            let sample = |channel: usize| -> (u8, u16) {
                match depth {
                    16 => {
                        let i = (x * channels + channel) * 2;
                        let v = u16::from_be_bytes([row[i], row[i + 1]]);
                        ((v >> 8) as u8, v)
                    }
                    8 => {
                        let v = row[x * channels + channel];
                        (v, v as u16)
                    }
                    d => {
                        let bit = x * d as usize;
                        let v = (row[bit / 8] >> (8 - d as usize - bit % 8)) & ((1u8 << d) - 1);
                        let scaled = if color == 3 { v } else { (v as u32 * 255 / ((1u32 << d) - 1)) as u8 };
                        (scaled, v as u16)
                    }
                }
            };
            let px = match color {
                0 => {
                    let (g, raw) = sample(0);
                    let a = if transparent.is_some_and(|t| t[0] == raw) { 0 } else { 255 };
                    [g, g, g, a]
                }
                2 => {
                    let (r, rr) = sample(0);
                    let (g, gr) = sample(1);
                    let (b, br) = sample(2);
                    let a = if transparent.is_some_and(|t| t == [rr, gr, br]) { 0 } else { 255 };
                    [r, g, b, a]
                }
                3 => *palette.get(sample(0).0 as usize)?,
                4 => {
                    let (g, _) = sample(0);
                    [g, g, g, sample(1).0]
                }
                _ => [sample(0).0, sample(1).0, sample(2).0, sample(3).0],
            };
            let a = px[3] as u32;
            let o = (y * width + x) * 4;
            rgba[o] = ((px[0] as u32 * a + 127) / 255) as u8;
            rgba[o + 1] = ((px[1] as u32 * a + 127) / 255) as u8;
            rgba[o + 2] = ((px[2] as u32 * a + 127) / 255) as u8;
            rgba[o + 3] = px[3];
        }
    }
    Some(Image { width, height, rgba })
}

/// PNG's per-row filters, undone in place of a copy.
fn unfilter(raw: &[u8], row_bytes: usize, height: usize, bpp: usize) -> Option<Vec<u8>> {
    let mut out = vec![0u8; row_bytes * height];
    for y in 0..height {
        let filter = raw[y * (row_bytes + 1)];
        let src = &raw[y * (row_bytes + 1) + 1..(y + 1) * (row_bytes + 1)];
        let (done, rest) = out.split_at_mut(y * row_bytes);
        let prior: &[u8] = if y > 0 { &done[(y - 1) * row_bytes..] } else { &[] };
        let cur = &mut rest[..row_bytes];
        for i in 0..row_bytes {
            let a = if i >= bpp { cur[i - bpp] } else { 0 };
            let b = if y > 0 { prior[i] } else { 0 };
            let c = if y > 0 && i >= bpp { prior[i - bpp] } else { 0 };
            let predicted = match filter {
                0 => 0,
                1 => a,
                2 => b,
                3 => ((a as u16 + b as u16) / 2) as u8,
                4 => {
                    let p = a as i16 + b as i16 - c as i16;
                    let (pa, pb, pc) = ((p - a as i16).abs(), (p - b as i16).abs(), (p - c as i16).abs());
                    if pa <= pb && pa <= pc { a } else if pb <= pc { b } else { c }
                }
                _ => return None,
            };
            cur[i] = src[i].wrapping_add(predicted);
        }
    }
    Some(out)
}

fn crc32(data: &[u8]) -> u32 {
    static TABLE: std::sync::LazyLock<[u32; 256]> = std::sync::LazyLock::new(|| {
        let mut t = [0u32; 256];
        for (n, entry) in t.iter_mut().enumerate() {
            let mut c = n as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB88320 ^ (c >> 1) } else { c >> 1 };
            }
            *entry = c;
        }
        t
    });
    let mut c = 0xFFFF_FFFFu32;
    for &b in data {
        c = TABLE[((c ^ b as u32) & 0xFF) as usize] ^ (c >> 8);
    }
    c ^ 0xFFFF_FFFF
}

/// zlib (RFC 1950): a two-byte header, deflate data, and an Adler-32 of what it holds.
/// [expected] caps the output: a stream that would grow past it is refused.
pub fn zlib_decompress(data: &[u8], expected: usize) -> Option<Vec<u8>> {
    let (cmf, flg) = (*data.first()?, *data.get(1)?);
    if cmf & 0x0F != 8 || (cmf as u16 * 256 + flg as u16) % 31 != 0 || flg & 0x20 != 0 {
        return None;
    }
    let out = inflate(data.get(2..)?, expected)?;
    let tail = data.len().checked_sub(4)?;
    let adler = u32::from_be_bytes(data.get(tail..)?.try_into().ok()?);
    if adler32(&out) != adler {
        return None;
    }
    Some(out)
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for chunk in data.chunks(5552) {
        for &x in chunk {
            a += x as u32;
            b += a;
        }
        a %= 65521;
        b %= 65521;
    }
    (b << 16) | a
}

struct Bits<'a> {
    data: &'a [u8],
    pos: usize,
    buffer: u64,
    count: u32,
}

impl<'a> Bits<'a> {
    fn need(&mut self, n: u32) -> Option<()> {
        while self.count < n {
            let byte = *self.data.get(self.pos)?;
            self.pos += 1;
            self.buffer |= (byte as u64) << self.count;
            self.count += 8;
        }
        Some(())
    }

    fn take(&mut self, n: u32) -> Option<u32> {
        if n == 0 {
            return Some(0);
        }
        self.need(n)?;
        let v = (self.buffer & ((1u64 << n) - 1)) as u32;
        self.buffer >>= n;
        self.count -= n;
        Some(v)
    }

    fn align(&mut self) {
        let drop = self.count % 8;
        self.buffer >>= drop;
        self.count -= drop;
    }
}

/// A canonical Huffman code, decoded a bit at a time: small, and fast enough for images.
struct Huffman {
    counts: [u16; 16],
    symbols: Vec<u16>,
}

impl Huffman {
    fn new(lengths: &[u8]) -> Option<Huffman> {
        let mut counts = [0u16; 16];
        for &l in lengths {
            counts[l as usize] += 1;
        }
        counts[0] = 0;
        // Over-subscribed codes are malformed; incomplete ones are allowed (a lone code).
        let mut left = 1i32;
        for &c in &counts[1..] {
            left = (left << 1) - c as i32;
            if left < 0 {
                return None;
            }
        }
        let mut offsets = [0u16; 16];
        for len in 1..15 {
            offsets[len + 1] = offsets[len] + counts[len];
        }
        let mut symbols = vec![0u16; lengths.len()];
        for (symbol, &l) in lengths.iter().enumerate() {
            if l != 0 {
                symbols[offsets[l as usize] as usize] = symbol as u16;
                offsets[l as usize] += 1;
            }
        }
        Some(Huffman { counts, symbols })
    }

    fn decode(&self, bits: &mut Bits) -> Option<u16> {
        let (mut code, mut first, mut index) = (0i32, 0i32, 0i32);
        for len in 1..16 {
            code |= bits.take(1)? as i32;
            let count = self.counts[len] as i32;
            if code - count < first {
                return self.symbols.get((index + (code - first)) as usize).copied();
            }
            index += count;
            first += count;
            first <<= 1;
            code <<= 1;
        }
        None
    }
}

const LENGTH_BASE: [u16; 29] = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258];
const LENGTH_EXTRA: [u8; 29] = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0];
const DIST_BASE: [u16; 30] = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577];
const DIST_EXTRA: [u8; 30] = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13];

/// Deflate (RFC 1951): stored, fixed-Huffman and dynamic-Huffman blocks.
pub fn inflate(data: &[u8], limit: usize) -> Option<Vec<u8>> {
    let mut bits = Bits { data, pos: 0, buffer: 0, count: 0 };
    let mut out: Vec<u8> = Vec::with_capacity(limit.min(1 << 24));
    loop {
        let last = bits.take(1)? == 1;
        match bits.take(2)? {
            0 => {
                bits.align();
                let len = bits.take(16)? as usize;
                let nlen = bits.take(16)? as usize;
                if len != !nlen & 0xFFFF || out.len() + len > limit {
                    return None;
                }
                for _ in 0..len {
                    out.push(bits.take(8)? as u8);
                }
            }
            kind @ (1 | 2) => {
                let (lit, dist) = if kind == 1 {
                    let mut lengths = [0u8; 288];
                    lengths[..144].fill(8);
                    lengths[144..256].fill(9);
                    lengths[256..280].fill(7);
                    lengths[280..].fill(8);
                    (Huffman::new(&lengths)?, Huffman::new(&[5u8; 30])?)
                } else {
                    dynamic_codes(&mut bits)?
                };
                loop {
                    let symbol = lit.decode(&mut bits)? as usize;
                    if symbol < 256 {
                        if out.len() >= limit {
                            return None;
                        }
                        out.push(symbol as u8);
                    } else if symbol == 256 {
                        break;
                    } else {
                        let i = symbol - 257;
                        let length = *LENGTH_BASE.get(i)? as usize + bits.take(*LENGTH_EXTRA.get(i)? as u32)? as usize;
                        let d = dist.decode(&mut bits)? as usize;
                        let distance = *DIST_BASE.get(d)? as usize + bits.take(*DIST_EXTRA.get(d)? as u32)? as usize;
                        if distance > out.len() || out.len() + length > limit {
                            return None;
                        }
                        let start = out.len() - distance;
                        for k in 0..length {
                            let b = out[start + k];
                            out.push(b);
                        }
                    }
                }
            }
            _ => return None,
        }
        if last {
            return Some(out);
        }
    }
}

fn dynamic_codes(bits: &mut Bits) -> Option<(Huffman, Huffman)> {
    const ORDER: [usize; 19] = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];
    let literals = bits.take(5)? as usize + 257;
    let distances = bits.take(5)? as usize + 1;
    let code_lengths = bits.take(4)? as usize + 4;
    if literals > 286 || distances > 30 {
        return None;
    }
    let mut lengths_of_lengths = [0u8; 19];
    for &i in ORDER.iter().take(code_lengths) {
        lengths_of_lengths[i] = bits.take(3)? as u8;
    }
    let code = Huffman::new(&lengths_of_lengths)?;
    let mut lengths = vec![0u8; literals + distances];
    let mut i = 0;
    while i < lengths.len() {
        let symbol = code.decode(bits)?;
        let (value, repeat) = match symbol {
            0..=15 => (symbol as u8, 1),
            16 => (*lengths.get(i.checked_sub(1)?)?, 3 + bits.take(2)? as usize),
            17 => (0, 3 + bits.take(3)? as usize),
            18 => (0, 11 + bits.take(7)? as usize),
            _ => return None,
        };
        if i + repeat > lengths.len() {
            return None;
        }
        lengths[i..i + repeat].fill(value);
        i += repeat;
    }
    if lengths[256] == 0 {
        return None;
    }
    Some((Huffman::new(&lengths[..literals])?, Huffman::new(&lengths[literals..])?))
}

/// [image] resized to [width] by [height]: averaging the source pixels each output pixel
/// covers when shrinking, which keeps a 109-pixel emoji crisp at 30, and blending the nearest
/// four when growing.
pub fn resize(image: &Image, width: usize, height: usize) -> Image {
    let mut rgba = vec![0u8; width * height * 4];
    if width == 0 || height == 0 || image.width == 0 || image.height == 0 {
        return Image { width, height, rgba };
    }
    let sx = image.width as f32 / width as f32;
    let sy = image.height as f32 / height as f32;
    let src = |x: usize, y: usize, c: usize| image.rgba[(y * image.width + x) * 4 + c] as f32;
    for y in 0..height {
        for x in 0..width {
            let mut acc = [0f32; 4];
            if sx >= 1.0 && sy >= 1.0 {
                let (x0, x1) = (x as f32 * sx, (x + 1) as f32 * sx);
                let (y0, y1) = (y as f32 * sy, (y + 1) as f32 * sy);
                let mut total = 0f32;
                let mut py = y0.floor() as usize;
                while (py as f32) < y1 && py < image.height {
                    let wy = (y1.min(py as f32 + 1.0) - y0.max(py as f32)).max(0.0);
                    let mut px = x0.floor() as usize;
                    while (px as f32) < x1 && px < image.width {
                        let w = wy * (x1.min(px as f32 + 1.0) - x0.max(px as f32)).max(0.0);
                        for (c, a) in acc.iter_mut().enumerate() {
                            *a += src(px, py, c) * w;
                        }
                        total += w;
                        px += 1;
                    }
                    py += 1;
                }
                if total > 0.0 {
                    for a in acc.iter_mut() {
                        *a /= total;
                    }
                }
            } else {
                let fx = ((x as f32 + 0.5) * sx - 0.5).clamp(0.0, (image.width - 1) as f32);
                let fy = ((y as f32 + 0.5) * sy - 0.5).clamp(0.0, (image.height - 1) as f32);
                let (x0, y0) = (fx.floor() as usize, fy.floor() as usize);
                let (x1, y1) = ((x0 + 1).min(image.width - 1), (y0 + 1).min(image.height - 1));
                let (tx, ty) = (fx - x0 as f32, fy - y0 as f32);
                for (c, a) in acc.iter_mut().enumerate() {
                    let top = src(x0, y0, c) * (1.0 - tx) + src(x1, y0, c) * tx;
                    let bottom = src(x0, y1, c) * (1.0 - tx) + src(x1, y1, c) * tx;
                    *a = top * (1.0 - ty) + bottom * ty;
                }
            }
            let o = (y * width + x) * 4;
            for c in 0..4 {
                rgba[o + c] = acc[c].round().clamp(0.0, 255.0) as u8;
            }
        }
    }
    Image { width, height, rgba }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A stored (uncompressed) zlib stream around [raw], for building test images by hand.
    fn stored_zlib(raw: &[u8]) -> Vec<u8> {
        let mut z = vec![0x78, 0x01];
        z.push(1);
        let len = raw.len() as u16;
        z.extend(len.to_le_bytes());
        z.extend((!len).to_le_bytes());
        z.extend(raw);
        z.extend(adler32(raw).to_be_bytes());
        z
    }

    fn chunk(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
        let mut c = (body.len() as u32).to_be_bytes().to_vec();
        let mut covered = kind.to_vec();
        covered.extend(body);
        c.extend(&covered);
        c.extend(crc32(&covered).to_be_bytes());
        c
    }

    fn png(width: u32, height: u32, color: u8, rows: &[u8], extra: &[Vec<u8>]) -> Vec<u8> {
        let mut p = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
        let mut ihdr = width.to_be_bytes().to_vec();
        ihdr.extend(height.to_be_bytes());
        ihdr.extend([8, color, 0, 0, 0]);
        p.extend(chunk(b"IHDR", &ihdr));
        for e in extra {
            p.extend(e);
        }
        p.extend(chunk(b"IDAT", &stored_zlib(rows)));
        p.extend(chunk(b"IEND", &[]));
        p
    }

    #[test]
    fn an_rgba_image_comes_back_premultiplied() {
        // One row, filter 0: an opaque red pixel and a half-transparent white one.
        let rows = [0, 255, 0, 0, 255, 255, 255, 255, 128];
        let image = decode(&png(2, 1, 6, &rows, &[])).unwrap();
        assert_eq!((image.width, image.height), (2, 1));
        assert_eq!(&image.rgba[0..4], &[255, 0, 0, 255]);
        assert_eq!(&image.rgba[4..8], &[128, 128, 128, 128]);
    }

    #[test]
    fn a_palette_image_takes_its_colours_and_transparency_from_the_palette() {
        let palette = chunk(b"PLTE", &[0, 0, 255, 0, 255, 0]);
        let alpha = chunk(b"tRNS", &[0]);
        let rows = [0, 0, 1];
        let image = decode(&png(2, 1, 3, &rows, &[palette, alpha])).unwrap();
        assert_eq!(&image.rgba[0..4], &[0, 0, 0, 0]);
        assert_eq!(&image.rgba[4..8], &[0, 255, 0, 255]);
    }

    #[test]
    fn each_row_filter_is_undone() {
        // Two rows of one grey pixel each: Sub, then Up adding 10 to the row above.
        let rows = [1, 50, 2, 10];
        let image = decode(&png(1, 2, 0, &rows, &[])).unwrap();
        assert_eq!(image.rgba[0], 50);
        assert_eq!(image.rgba[4], 60);
    }

    #[test]
    fn a_changed_byte_makes_the_image_refused() {
        let mut bytes = png(1, 1, 0, &[0, 7], &[]);
        let at = bytes.len() - 20;
        bytes[at] ^= 1;
        assert!(decode(&bytes).is_none());
    }

    #[test]
    fn a_huge_claimed_size_is_refused_before_anything_is_allocated() {
        assert!(decode(&png(100_000, 100_000, 6, &[0], &[])).is_none());
    }

    #[test]
    fn fixed_huffman_blocks_and_back_references_inflate() {
        // Made by Python's zlib at level 9, with fixed codes; the longer one copies back a lot.
        // Dynamic codes are what the emoji font's images use, tested in shape.rs.
        let fixed = [0x78, 0xda, 0x4b, 0x4c, 0x4a, 0x4e, 0x04, 0x23, 0x00, 0x11, 0x3d, 0x03, 0x73];
        assert_eq!(zlib_decompress(&fixed, 100).unwrap(), b"abcabcabc");
        let dynamic = [0x78, 0xda, 0x2b, 0xc9, 0x48, 0x55, 0x28, 0x2c, 0xcd, 0x4c, 0xce, 0x56, 0x48, 0x2a, 0xca, 0x2f, 0xcf, 0x53, 0x48, 0xcb, 0xaf, 0x50, 0xc8, 0x2a, 0xcd, 0x2d, 0x28, 0x56, 0xc8, 0x2f, 0x4b, 0x2d, 0x52, 0x28, 0x01, 0x4a, 0xe7, 0x24, 0x56, 0x55, 0x2a, 0xa4, 0xe4, 0xa7, 0xeb, 0x80, 0x79, 0xa3, 0x8a, 0x47, 0x15, 0x8f, 0x2a, 0xa6, 0xaa, 0x62, 0x00, 0xa2, 0x05, 0x45, 0x74];
        assert_eq!(zlib_decompress(&dynamic, 2000).unwrap(), "the quick brown fox jumps over the lazy dog, ".repeat(20).as_bytes());
    }

    #[test]
    fn shrinking_averages_the_pixels_each_output_pixel_covers() {
        let image = Image { width: 2, height: 1, rgba: vec![0, 0, 0, 255, 255, 255, 255, 255] };
        let small = resize(&image, 1, 1);
        assert_eq!(&small.rgba[..], &[128, 128, 128, 255]);
    }
}
