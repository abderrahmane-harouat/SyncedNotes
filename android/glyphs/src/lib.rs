//! The native half of the text engine: everything that chews through bytes or pixels.
//!
//! Kotlin owns the document, layout, cursor and input. This crate owns font files (untrusted
//! binary input, so it lives in a memory-safe language), turning text into positioned glyphs,
//! rasterizing outlines, and compositing a frame's draw list into pixels.

pub mod font;
pub mod otl;
pub mod paint;
pub mod png;
pub mod raster;
pub mod shape;

mod engine;
mod jni;
