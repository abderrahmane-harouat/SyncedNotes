//! OpenType layout: the GSUB and GPOS tables, which turn a row of glyphs from the cmap into
//! what a script needs. Arabic cannot be written without it: each letter takes one of up to
//! four shapes depending on its neighbours, lam and alef fuse into one, and the vowel marks sit
//! on the letters they belong to. Latin gets ligatures and kerning from the same tables.
//!
//! Only what the app's fonts use is implemented: substitution types 1, 2, 4, 5, 6 and 7, and
//! positioning types 1, 2, 4, 5, 6 and 9, each format of each. Every read is bounds-checked
//! (a font is untrusted input) and nested lookups are limited in depth, so a hostile font can
//! make shaping wrong, never unsafe or endless.

/// Feature masks: which glyphs a feature's lookups may touch. The joining forms are set per
/// letter by the Arabic analysis in `shape.rs`; everything else applies to every glyph.
pub const GLOBAL: u32 = 1;
pub const ISOL: u32 = 2;
pub const FINA: u32 = 4;
pub const MEDI: u32 = 8;
pub const INIT: u32 = 16;
/// Not a feature: marks a joiner or variation selector kept only so substitutions can see it
/// (an emoji sequence); whatever is left of them afterwards draws nothing.
pub const IGNORABLE: u32 = 1 << 31;

pub type Tag = [u8; 4];

/// One glyph while it is being shaped. Positions are in font units until `shape.rs` scales them.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Glyph {
    pub id: u16,
    /// UTF-16 index of the first code unit this glyph came from.
    pub cluster: u32,
    pub mask: u32,
    /// GDEF's glyph class: 1 base, 2 ligature, 3 mark, 4 component; 0 when unknown.
    pub class: u8,
    /// Which ligature a mark sits on, and which of its components (from 1); 0 for none.
    pub lig_id: u16,
    pub lig_comp: u8,
    pub advance: i32,
    pub dx: i32,
    pub dy: i32,
    /// The glyph a mark hangs on, by index; -1 for none.
    pub attach: i32,
}

// Nested lookups (a contextual lookup calling others) stop here, so a looping font ends.
const MAX_DEPTH: u32 = 6;
// A buffer never grows past this many glyphs per input character, whatever the font says.
const MAX_GROWTH: usize = 8;

fn u16_at(d: &[u8], o: usize) -> Option<u16> {
    let b = d.get(o..o.checked_add(2)?)?;
    Some(u16::from_be_bytes([b[0], b[1]]))
}

fn i16_at(d: &[u8], o: usize) -> Option<i16> {
    u16_at(d, o).map(|v| v as i16)
}

fn u32_at(d: &[u8], o: usize) -> Option<u32> {
    let b = d.get(o..o.checked_add(4)?)?;
    Some(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}

/// An offset from [base], read at [at]; None for a null offset or a bad read.
fn offset16(d: &[u8], base: usize, at: usize) -> Option<usize> {
    match u16_at(d, at)? {
        0 => None,
        o => base.checked_add(o as usize),
    }
}

/// The glyph's index in a Coverage table, if it is covered.
fn coverage(d: &[u8], table: usize, glyph: u16) -> Option<u16> {
    match u16_at(d, table)? {
        1 => {
            let count = u16_at(d, table + 2)? as usize;
            let (mut lo, mut hi) = (0usize, count);
            while lo < hi {
                let mid = (lo + hi) / 2;
                let g = u16_at(d, table + 4 + 2 * mid)?;
                if g == glyph {
                    return Some(mid as u16);
                }
                if g < glyph { lo = mid + 1 } else { hi = mid }
            }
            None
        }
        2 => {
            let count = u16_at(d, table + 2)? as usize;
            let (mut lo, mut hi) = (0usize, count);
            while lo < hi {
                let mid = (lo + hi) / 2;
                let r = table + 4 + 6 * mid;
                let (start, end) = (u16_at(d, r)?, u16_at(d, r + 2)?);
                if glyph < start {
                    hi = mid;
                } else if glyph > end {
                    lo = mid + 1;
                } else {
                    return Some(u16_at(d, r + 4)?.wrapping_add(glyph - start));
                }
            }
            None
        }
        _ => None,
    }
}

/// The glyph's class in a ClassDef table; 0 for every glyph it does not list.
fn class_of(d: &[u8], table: Option<usize>, glyph: u16) -> u16 {
    let Some(table) = table else { return 0 };
    let read = || -> Option<u16> {
        match u16_at(d, table)? {
            1 => {
                let start = u16_at(d, table + 2)?;
                let count = u16_at(d, table + 4)?;
                if glyph < start || glyph - start >= count {
                    return Some(0);
                }
                u16_at(d, table + 6 + 2 * (glyph - start) as usize)
            }
            2 => {
                let count = u16_at(d, table + 2)? as usize;
                let (mut lo, mut hi) = (0usize, count);
                while lo < hi {
                    let mid = (lo + hi) / 2;
                    let r = table + 4 + 6 * mid;
                    let (start, end) = (u16_at(d, r)?, u16_at(d, r + 2)?);
                    if glyph < start {
                        hi = mid;
                    } else if glyph > end {
                        lo = mid + 1;
                    } else {
                        return u16_at(d, r + 4);
                    }
                }
                Some(0)
            }
            _ => Some(0),
        }
    };
    read().unwrap_or(0)
}

/// GDEF: what kind each glyph is, which marks attach together, and the mark sets.
pub struct Gdef<'a> {
    d: &'a [u8],
}

impl<'a> Gdef<'a> {
    pub fn new(d: Option<&'a [u8]>) -> Option<Gdef<'a>> {
        d.map(|d| Gdef { d })
    }

    pub fn glyph_class(&self, glyph: u16) -> u8 {
        class_of(self.d, offset16(self.d, 0, 4), glyph) as u8
    }

    fn mark_attach_class(&self, glyph: u16) -> u16 {
        class_of(self.d, offset16(self.d, 0, 10), glyph)
    }

    fn in_mark_set(&self, set: u16, glyph: u16) -> bool {
        let read = || -> Option<bool> {
            // Mark glyph sets came with GDEF 1.2.
            if u16_at(self.d, 2)? < 2 {
                return Some(false);
            }
            let sets = offset16(self.d, 0, 12)?;
            if set >= u16_at(self.d, sets + 2)? {
                return Some(false);
            }
            let cov = sets.checked_add(u32_at(self.d, sets + 4 + 4 * set as usize)? as usize)?;
            Some(coverage(self.d, cov, glyph).is_some())
        };
        read().unwrap_or(false)
    }
}

/// A lookup as found: its type (extensions unwrapped per subtable), flag, subtables, and the
/// mark filtering set the flag may name.
struct Lookup {
    flag: u16,
    subtables: Vec<(u16, usize)>,
    filter: Option<u16>,
}

/// GSUB or GPOS, with the glyph classes they consult.
pub struct Layout<'a> {
    d: &'a [u8],
    gdef: Option<Gdef<'a>>,
    positioning: bool,
}

impl<'a> Layout<'a> {
    pub fn new(table: &'a [u8], gdef: Option<Gdef<'a>>, positioning: bool) -> Layout<'a> {
        Layout { d: table, gdef, positioning }
    }

    /// The lookups behind [features] for [script], in the order the font lists them, each with
    /// the mask of the feature that asked for it. Falls back to the default script, then Latin.
    pub fn lookups(&self, script: Tag, features: &[(Tag, u32)]) -> Vec<(u16, u32)> {
        self.find_lookups(script, features).unwrap_or_default()
    }

    fn find_lookups(&self, script: Tag, features: &[(Tag, u32)]) -> Option<Vec<(u16, u32)>> {
        let d = self.d;
        let scripts = offset16(d, 0, 4)?;
        let feature_list = offset16(d, 0, 6)?;
        let count = u16_at(d, scripts)? as usize;
        let mut chosen = None;
        for wanted in [script, *b"DFLT", *b"dflt", *b"latn"] {
            for i in 0..count {
                let r = scripts + 2 + 6 * i;
                if d.get(r..r + 4)? == wanted {
                    chosen = offset16(d, scripts, r + 4);
                    break;
                }
            }
            if chosen.is_some() {
                break;
            }
        }
        let script_table = chosen?;
        let lang = offset16(d, script_table, script_table)?;
        let required = u16_at(d, lang + 2)?;
        let feature_count = u16_at(d, feature_list)? as usize;
        let mut out: Vec<(u16, u32)> = Vec::new();
        let index_count = u16_at(d, lang + 4)? as usize;
        let mut indices: Vec<u16> = (0..index_count).filter_map(|i| u16_at(d, lang + 6 + 2 * i)).collect();
        if required != 0xFFFF {
            indices.push(required);
        }
        for index in indices {
            let index = index as usize;
            if index >= feature_count {
                continue;
            }
            let r = feature_list + 2 + 6 * index;
            let tag: Tag = d.get(r..r + 4)?.try_into().ok()?;
            let Some(&(_, mask)) = features.iter().find(|(t, _)| *t == tag) else { continue };
            let Some(feature) = offset16(d, feature_list, r + 4) else { continue };
            let lookup_count = u16_at(d, feature + 2)? as usize;
            for k in 0..lookup_count {
                let lookup = u16_at(d, feature + 4 + 2 * k)?;
                match out.iter_mut().find(|(l, _)| *l == lookup) {
                    Some(existing) => existing.1 |= mask,
                    None => out.push((lookup, mask)),
                }
            }
        }
        out.sort_by_key(|(l, _)| *l);
        Some(out)
    }

    fn lookup(&self, index: u16) -> Option<Lookup> {
        let d = self.d;
        let list = offset16(d, 0, 8)?;
        if index >= u16_at(d, list)? {
            return None;
        }
        let table = offset16(d, list, list + 2 + 2 * index as usize)?;
        let kind = u16_at(d, table)?;
        let flag = u16_at(d, table + 2)?;
        let count = u16_at(d, table + 4)? as usize;
        let extension = if self.positioning { 9 } else { 7 };
        let mut subtables = Vec::with_capacity(count);
        for i in 0..count {
            let Some(sub) = offset16(d, table, table + 6 + 2 * i) else { continue };
            if kind == extension {
                // Format 1: the real type, and a 32-bit offset to the real subtable.
                if u16_at(d, sub)? != 1 {
                    continue;
                }
                let real = u16_at(d, sub + 2)?;
                let at = sub.checked_add(u32_at(d, sub + 4)? as usize)?;
                subtables.push((real, at));
            } else {
                subtables.push((kind, sub));
            }
        }
        let filter = if flag & 0x10 != 0 { u16_at(d, table + 6 + 2 * count) } else { None };
        Some(Lookup { flag, subtables, filter })
    }

    /// Whether the lookup's flag tells it to look past this glyph.
    fn skips(&self, g: &Glyph, flag: u16, filter: Option<u16>) -> bool {
        match g.class {
            1 if flag & 0x2 != 0 => return true,
            2 if flag & 0x4 != 0 => return true,
            3 if flag & 0x8 != 0 => return true,
            _ => {}
        }
        if g.class == 3 {
            if let Some(gdef) = &self.gdef {
                if flag & 0x10 != 0 {
                    if let Some(set) = filter {
                        return !gdef.in_mark_set(set, g.id);
                    }
                } else if flag & 0xFF00 != 0 {
                    return gdef.mark_attach_class(g.id) != flag >> 8;
                }
            }
        }
        false
    }

    fn next(&self, buf: &[Glyph], from: usize, flag: u16, filter: Option<u16>) -> Option<usize> {
        (from + 1..buf.len()).find(|&j| !self.skips(&buf[j], flag, filter))
    }

    fn prev(&self, buf: &[Glyph], from: usize, flag: u16, filter: Option<u16>) -> Option<usize> {
        (0..from).rev().find(|&j| !self.skips(&buf[j], flag, filter))
    }

    fn class_for(&self, glyph: u16) -> u8 {
        self.gdef.as_ref().map(|g| g.glyph_class(glyph)).unwrap_or(0)
    }

    /// Runs one lookup over the whole buffer, on the glyphs [mask] allows.
    pub fn apply(&self, buf: &mut Vec<Glyph>, lookup: u16, mask: u32, limit: usize) {
        let Some(l) = self.lookup(lookup) else { return };
        let mut i = 0;
        while i < buf.len() {
            if buf[i].mask & mask != 0 && !self.skips(&buf[i], l.flag, l.filter) {
                if let Some(next) = self.apply_at(buf, i, &l, 0, limit) {
                    i = next.max(i + 1);
                    continue;
                }
            }
            i += 1;
        }
    }

    /// Applies [l] at [i] alone: the first subtable that matches wins. Returns where the scan
    /// goes on from, or None when nothing matched.
    fn apply_at(&self, buf: &mut Vec<Glyph>, i: usize, l: &Lookup, depth: u32, limit: usize) -> Option<usize> {
        if depth > MAX_DEPTH {
            return None;
        }
        for &(kind, sub) in &l.subtables {
            let done = if self.positioning {
                match kind {
                    1 => self.single_pos(buf, i, sub),
                    2 => self.pair_pos(buf, i, sub, l),
                    4 => self.mark_base(buf, i, sub, l),
                    5 => self.mark_ligature(buf, i, sub, l),
                    6 => self.mark_mark(buf, i, sub, l),
                    7 => self.context(buf, i, sub, l, depth, limit, false),
                    8 => self.context(buf, i, sub, l, depth, limit, true),
                    _ => None,
                }
            } else {
                match kind {
                    1 => self.single_subst(buf, i, sub),
                    2 => self.multiple_subst(buf, i, sub, limit),
                    4 => self.ligature_subst(buf, i, sub, l),
                    5 => self.context(buf, i, sub, l, depth, limit, false),
                    6 => self.context(buf, i, sub, l, depth, limit, true),
                    _ => None,
                }
            };
            if done.is_some() {
                return done;
            }
        }
        None
    }

    // --- Substitution -------------------------------------------------------------------------

    fn single_subst(&self, buf: &mut [Glyph], i: usize, sub: usize) -> Option<usize> {
        let d = self.d;
        let index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)?;
        let glyph = match u16_at(d, sub)? {
            1 => buf[i].id.wrapping_add(i16_at(d, sub + 4)? as u16),
            2 => {
                if index >= u16_at(d, sub + 4)? {
                    return None;
                }
                u16_at(d, sub + 6 + 2 * index as usize)?
            }
            _ => return None,
        };
        buf[i].id = glyph;
        buf[i].class = self.class_for(glyph);
        Some(i + 1)
    }

    fn multiple_subst(&self, buf: &mut Vec<Glyph>, i: usize, sub: usize, limit: usize) -> Option<usize> {
        let d = self.d;
        if u16_at(d, sub)? != 1 {
            return None;
        }
        let index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)?;
        if index >= u16_at(d, sub + 4)? {
            return None;
        }
        let seq = offset16(d, sub, sub + 6 + 2 * index as usize)?;
        let count = u16_at(d, seq)? as usize;
        // Deleting a glyph is allowed by the spec but would lose its character's place.
        if count == 0 || buf.len() + count - 1 > limit {
            return None;
        }
        let original = buf[i];
        let mut replacement = Vec::with_capacity(count);
        for k in 0..count {
            let id = u16_at(d, seq + 2 + 2 * k)?;
            replacement.push(Glyph { id, class: self.class_for(id), ..original });
        }
        buf.splice(i..i + 1, replacement);
        Some(i + count)
    }

    fn ligature_subst(&self, buf: &mut Vec<Glyph>, i: usize, sub: usize, l: &Lookup) -> Option<usize> {
        let d = self.d;
        if u16_at(d, sub)? != 1 {
            return None;
        }
        let index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)?;
        if index >= u16_at(d, sub + 4)? {
            return None;
        }
        let set = offset16(d, sub, sub + 6 + 2 * index as usize)?;
        let count = u16_at(d, set)? as usize;
        for k in 0..count {
            let Some(lig) = offset16(d, set, set + 2 + 2 * k) else { continue };
            let glyph = u16_at(d, lig)?;
            let components = u16_at(d, lig + 2)? as usize;
            if components == 0 {
                continue;
            }
            let mut positions = vec![i];
            let mut ok = true;
            for c in 1..components {
                match self.next(buf, *positions.last()?, l.flag, l.filter) {
                    Some(j) if Some(buf[j].id) == u16_at(d, lig + 4 + 2 * (c - 1)) => positions.push(j),
                    _ => {
                        ok = false;
                        break;
                    }
                }
            }
            if !ok {
                continue;
            }
            // Marks skipped between the components stay, remembering which component they
            // followed, so mark-to-ligature positioning can put each on its own letter.
            let lig_id = buf.iter().map(|g| g.lig_id).max().unwrap_or(0).wrapping_add(1).max(1);
            let last = *positions.last()?;
            let mut component = 1u8;
            for j in i + 1..=last {
                if positions.contains(&j) {
                    component = component.saturating_add(1);
                } else if buf[j].class == 3 {
                    buf[j].lig_id = lig_id;
                    buf[j].lig_comp = component;
                }
            }
            buf[i].id = glyph;
            buf[i].class = 2;
            buf[i].lig_id = lig_id;
            for &j in positions[1..].iter().rev() {
                buf.remove(j);
            }
            return Some(i + 1);
        }
        None
    }

    // --- Context, for both tables -------------------------------------------------------------

    /// Context and chaining context, formats 1 to 3: a rule matched around [i] applies other
    /// lookups at positions inside it.
    #[allow(clippy::too_many_arguments)]
    fn context(&self, buf: &mut Vec<Glyph>, i: usize, sub: usize, l: &Lookup, depth: u32, limit: usize, chain: bool) -> Option<usize> {
        let d = self.d;
        let format = u16_at(d, sub)?;
        // Each candidate rule, read into the same shape: backtrack, input (after the first),
        // lookahead, as tests on a glyph, and the lookups to apply.
        enum Test {
            Glyph(u16),
            Class(Option<usize>, u16),
            Coverage(usize),
        }
        let pass = |t: &Test, g: u16| match t {
            Test::Glyph(id) => *id == g,
            Test::Class(def, c) => class_of(d, *def, g) == *c,
            Test::Coverage(cov) => coverage(d, *cov, g).is_some(),
        };
        let mut rules: Vec<(Vec<Test>, Vec<Test>, Vec<Test>, usize, usize)> = Vec::new();
        let read_rule = |rule: usize, map: &dyn Fn(u16) -> Test, rules: &mut Vec<(Vec<Test>, Vec<Test>, Vec<Test>, usize, usize)>| -> Option<()> {
            let mut p = rule;
            let mut backtrack = Vec::new();
            let mut lookahead = Vec::new();
            if chain {
                let n = u16_at(d, p)? as usize;
                for k in 0..n {
                    backtrack.push(map(u16_at(d, p + 2 + 2 * k)?));
                }
                p += 2 + 2 * n;
            }
            let input_count = u16_at(d, p)? as usize;
            let records_count_at;
            let mut input = Vec::new();
            if chain {
                for k in 1..input_count {
                    input.push(map(u16_at(d, p + 2 + 2 * (k - 1))?));
                }
                p += 2 + 2 * input_count.saturating_sub(1);
                let n = u16_at(d, p)? as usize;
                for k in 0..n {
                    lookahead.push(map(u16_at(d, p + 2 + 2 * k)?));
                }
                p += 2 + 2 * n;
                records_count_at = p;
                rules.push((backtrack, input, lookahead, records_count_at + 2, u16_at(d, records_count_at)? as usize));
            } else {
                // Context rules put the record count before the input.
                let records = u16_at(d, p + 2)? as usize;
                for k in 1..input_count {
                    input.push(map(u16_at(d, p + 4 + 2 * (k - 1))?));
                }
                rules.push((backtrack, input, lookahead, p + 4 + 2 * input_count.saturating_sub(1), records));
            }
            Some(())
        };
        match format {
            1 => {
                let index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)?;
                if index >= u16_at(d, sub + 4)? {
                    return None;
                }
                let set = offset16(d, sub, sub + 6 + 2 * index as usize)?;
                for k in 0..u16_at(d, set)? as usize {
                    if let Some(rule) = offset16(d, set, set + 2 + 2 * k) {
                        read_rule(rule, &|g| Test::Glyph(g), &mut rules);
                    }
                }
            }
            2 => {
                coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)?;
                let (back_def, input_def, ahead_def, sets_at) = if chain {
                    (offset16(d, sub, sub + 4), offset16(d, sub, sub + 6), offset16(d, sub, sub + 8), sub + 10)
                } else {
                    let def = offset16(d, sub, sub + 4);
                    (def, def, def, sub + 6)
                };
                let class = class_of(d, input_def, buf[i].id);
                if class >= u16_at(d, sets_at)? {
                    return None;
                }
                let set = offset16(d, sub, sets_at + 2 + 2 * class as usize)?;
                for k in 0..u16_at(d, set)? as usize {
                    let Some(rule) = offset16(d, set, set + 2 + 2 * k) else { continue };
                    // Class rules read backtrack, input and lookahead against three ClassDefs.
                    let before = rules.len();
                    read_rule(rule, &|c| Test::Class(input_def, c), &mut rules);
                    if let Some(r) = rules.get_mut(before) {
                        for t in r.0.iter_mut() {
                            if let Test::Class(_, c) = *t { *t = Test::Class(back_def, c) }
                        }
                        for t in r.2.iter_mut() {
                            if let Test::Class(_, c) = *t { *t = Test::Class(ahead_def, c) }
                        }
                    }
                }
            }
            3 => {
                let mut p = sub + 2;
                let mut backtrack = Vec::new();
                let mut lookahead = Vec::new();
                if chain {
                    let n = u16_at(d, p)? as usize;
                    for k in 0..n {
                        backtrack.push(Test::Coverage(offset16(d, sub, p + 2 + 2 * k)?));
                    }
                    p += 2 + 2 * n;
                }
                let n = u16_at(d, p)? as usize;
                if n == 0 {
                    return None;
                }
                let records_count;
                let first = if chain { offset16(d, sub, p + 2)? } else { offset16(d, sub, p + 4)? };
                coverage(d, first, buf[i].id)?;
                let mut input = Vec::new();
                if chain {
                    for k in 1..n {
                        input.push(Test::Coverage(offset16(d, sub, p + 2 + 2 * k)?));
                    }
                    p += 2 + 2 * n;
                    let m = u16_at(d, p)? as usize;
                    for k in 0..m {
                        lookahead.push(Test::Coverage(offset16(d, sub, p + 2 + 2 * k)?));
                    }
                    p += 2 + 2 * m;
                    records_count = u16_at(d, p)? as usize;
                    rules.push((backtrack, input, lookahead, p + 2, records_count));
                } else {
                    records_count = u16_at(d, p + 2)? as usize;
                    for k in 1..n {
                        input.push(Test::Coverage(offset16(d, sub, p + 4 + 2 * k)?));
                    }
                    rules.push((backtrack, input, lookahead, p + 4 + 2 * n, records_count));
                }
            }
            _ => return None,
        }

        for (backtrack, input, lookahead, records_at, records) in rules {
            let mut positions = vec![i];
            let mut ok = true;
            for t in &input {
                match self.next(buf, *positions.last()?, l.flag, l.filter) {
                    Some(j) if pass(t, buf[j].id) => positions.push(j),
                    _ => {
                        ok = false;
                        break;
                    }
                }
            }
            if !ok {
                continue;
            }
            let mut at = i;
            for t in &backtrack {
                match self.prev(buf, at, l.flag, l.filter) {
                    Some(j) if pass(t, buf[j].id) => at = j,
                    _ => {
                        ok = false;
                        break;
                    }
                }
            }
            if !ok {
                continue;
            }
            let mut at = *positions.last()?;
            for t in &lookahead {
                match self.next(buf, at, l.flag, l.filter) {
                    Some(j) if pass(t, buf[j].id) => at = j,
                    _ => {
                        ok = false;
                        break;
                    }
                }
            }
            if !ok {
                continue;
            }
            // Matched: apply each record's lookup at its position, keeping later positions in
            // step when a substitution changes the number of glyphs.
            let mut end = *positions.last()? as isize;
            for r in 0..records {
                let sequence = u16_at(d, records_at + 4 * r)? as usize;
                let nested = u16_at(d, records_at + 4 * r + 2)?;
                let Some(&pos) = positions.get(sequence) else { continue };
                let Some(inner) = self.lookup(nested) else { continue };
                if pos >= buf.len() || self.skips(&buf[pos], inner.flag, inner.filter) {
                    continue;
                }
                let before = buf.len() as isize;
                self.apply_at(buf, pos, &inner, depth + 1, limit);
                let delta = buf.len() as isize - before;
                if delta != 0 {
                    for p in positions.iter_mut().skip(sequence + 1) {
                        *p = (*p as isize + delta).max(0) as usize;
                    }
                    end += delta;
                }
            }
            return Some((end.max(i as isize) as usize) + 1);
        }
        None
    }

    // --- Positioning --------------------------------------------------------------------------

    /// Adds a ValueRecord's placement and advance to [g]; returns the record's size.
    fn value(&self, at: usize, format: u16, g: &mut Glyph) -> usize {
        let d = self.d;
        let mut p = at;
        if format & 0x1 != 0 { g.dx += i16_at(d, p).unwrap_or(0) as i32; p += 2 }
        if format & 0x2 != 0 { g.dy += i16_at(d, p).unwrap_or(0) as i32; p += 2 }
        if format & 0x4 != 0 { g.advance += i16_at(d, p).unwrap_or(0) as i32; p += 2 }
        // Vertical advance, then device tables: nothing a horizontal line at one size uses.
        p - at + 2 * (format & 0xF8).count_ones() as usize
    }

    fn value_size(format: u16) -> usize {
        2 * (format & 0xFF).count_ones() as usize
    }

    fn single_pos(&self, buf: &mut [Glyph], i: usize, sub: usize) -> Option<usize> {
        let d = self.d;
        let index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)? as usize;
        let format = u16_at(d, sub + 4)?;
        match u16_at(d, sub)? {
            1 => { self.value(sub + 6, format, &mut buf[i]); }
            2 => {
                if index >= u16_at(d, sub + 6)? as usize {
                    return None;
                }
                self.value(sub + 8 + index * Self::value_size(format), format, &mut buf[i]);
            }
            _ => return None,
        }
        Some(i + 1)
    }

    fn pair_pos(&self, buf: &mut [Glyph], i: usize, sub: usize, l: &Lookup) -> Option<usize> {
        let d = self.d;
        let index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)? as usize;
        let j = self.next(buf, i, l.flag, l.filter)?;
        let (f1, f2) = (u16_at(d, sub + 4)?, u16_at(d, sub + 6)?);
        let (s1, s2) = (Self::value_size(f1), Self::value_size(f2));
        let record = match u16_at(d, sub)? {
            1 => {
                if index >= u16_at(d, sub + 8)? as usize {
                    return None;
                }
                let set = offset16(d, sub, sub + 10 + 2 * index)?;
                let count = u16_at(d, set)? as usize;
                let size = 2 + s1 + s2;
                let (mut lo, mut hi) = (0usize, count);
                let mut found = None;
                while lo < hi {
                    let mid = (lo + hi) / 2;
                    let r = set + 2 + size * mid;
                    let second = u16_at(d, r)?;
                    if second == buf[j].id {
                        found = Some(r + 2);
                        break;
                    }
                    if second < buf[j].id { lo = mid + 1 } else { hi = mid }
                }
                found?
            }
            2 => {
                let c1 = class_of(d, offset16(d, sub, sub + 8), buf[i].id) as usize;
                let c2 = class_of(d, offset16(d, sub, sub + 10), buf[j].id) as usize;
                let (n1, n2) = (u16_at(d, sub + 12)? as usize, u16_at(d, sub + 14)? as usize);
                if c1 >= n1 || c2 >= n2 {
                    return None;
                }
                sub + 16 + (c1 * n2 + c2) * (s1 + s2)
            }
            _ => return None,
        };
        self.value(record, f1, &mut buf[i]);
        let mut second = buf[j];
        self.value(record + s1, f2, &mut second);
        buf[j] = second;
        Some(if f2 != 0 { j + 1 } else { j })
    }

    fn anchor(&self, at: usize) -> Option<(i32, i32)> {
        // Formats 2 and 3 add a contour point or device tables to the same x and y.
        Some((i16_at(self.d, at + 2)? as i32, i16_at(self.d, at + 4)? as i32))
    }

    /// The mark's class and anchor from a MarkArray.
    fn mark_record(&self, array: usize, index: usize) -> Option<(usize, (i32, i32))> {
        let d = self.d;
        if index >= u16_at(d, array)? as usize {
            return None;
        }
        let class = u16_at(d, array + 2 + 4 * index)? as usize;
        let anchor = self.anchor(offset16(d, array, array + 4 + 4 * index)?)?;
        Some((class, anchor))
    }

    fn attach(&self, buf: &mut [Glyph], mark: usize, base: usize, base_anchor: (i32, i32), mark_anchor: (i32, i32)) {
        buf[mark].dx = base_anchor.0 - mark_anchor.0;
        buf[mark].dy = base_anchor.1 - mark_anchor.1;
        buf[mark].attach = base as i32;
    }

    fn mark_base(&self, buf: &mut [Glyph], i: usize, sub: usize, _l: &Lookup) -> Option<usize> {
        let d = self.d;
        if u16_at(d, sub)? != 1 {
            return None;
        }
        let mark_index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)? as usize;
        // The base is the nearest glyph before that is not a mark.
        let j = (0..i).rev().find(|&j| buf[j].class != 3)?;
        let base_index = coverage(d, offset16(d, sub, sub + 4)?, buf[j].id)? as usize;
        let classes = u16_at(d, sub + 6)? as usize;
        let (class, mark_anchor) = self.mark_record(offset16(d, sub, sub + 8)?, mark_index)?;
        let bases = offset16(d, sub, sub + 10)?;
        if base_index >= u16_at(d, bases)? as usize || class >= classes {
            return None;
        }
        let base_anchor = self.anchor(offset16(d, bases, bases + 2 + 2 * (base_index * classes + class))?)?;
        self.attach(buf, i, j, base_anchor, mark_anchor);
        Some(i + 1)
    }

    fn mark_ligature(&self, buf: &mut [Glyph], i: usize, sub: usize, _l: &Lookup) -> Option<usize> {
        let d = self.d;
        if u16_at(d, sub)? != 1 {
            return None;
        }
        let mark_index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)? as usize;
        let j = (0..i).rev().find(|&j| buf[j].class != 3)?;
        let lig_index = coverage(d, offset16(d, sub, sub + 4)?, buf[j].id)? as usize;
        let classes = u16_at(d, sub + 6)? as usize;
        let (class, mark_anchor) = self.mark_record(offset16(d, sub, sub + 8)?, mark_index)?;
        let ligs = offset16(d, sub, sub + 10)?;
        if lig_index >= u16_at(d, ligs)? as usize || class >= classes {
            return None;
        }
        let attach = offset16(d, ligs, ligs + 2 + 2 * lig_index)?;
        let components = u16_at(d, attach)? as usize;
        if components == 0 {
            return None;
        }
        // On the component the mark followed when the ligature formed; otherwise the last.
        let component = if buf[i].lig_id != 0 && buf[i].lig_id == buf[j].lig_id && buf[i].lig_comp > 0 {
            (buf[i].lig_comp as usize).min(components) - 1
        } else {
            components - 1
        };
        let base_anchor = self.anchor(offset16(d, attach, attach + 2 + 2 * (component * classes + class))?)?;
        self.attach(buf, i, j, base_anchor, mark_anchor);
        Some(i + 1)
    }

    fn mark_mark(&self, buf: &mut [Glyph], i: usize, sub: usize, l: &Lookup) -> Option<usize> {
        let d = self.d;
        if u16_at(d, sub)? != 1 {
            return None;
        }
        let mark_index = coverage(d, offset16(d, sub, sub + 2)?, buf[i].id)? as usize;
        let j = self.prev(buf, i, l.flag, l.filter)?;
        if buf[j].class != 3 {
            return None;
        }
        let mark2_index = coverage(d, offset16(d, sub, sub + 4)?, buf[j].id)? as usize;
        let classes = u16_at(d, sub + 6)? as usize;
        let (class, mark_anchor) = self.mark_record(offset16(d, sub, sub + 8)?, mark_index)?;
        let marks2 = offset16(d, sub, sub + 10)?;
        if mark2_index >= u16_at(d, marks2)? as usize || class >= classes {
            return None;
        }
        let base_anchor = self.anchor(offset16(d, marks2, marks2 + 2 + 2 * (mark2_index * classes + class))?)?;
        self.attach(buf, i, j, base_anchor, mark_anchor);
        Some(i + 1)
    }
}

/// How many glyphs a buffer for [chars] characters may grow to.
pub fn growth_limit(chars: usize) -> usize {
    chars.saturating_mul(MAX_GROWTH).max(16)
}
