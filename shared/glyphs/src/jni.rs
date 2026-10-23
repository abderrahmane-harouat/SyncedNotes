//! The JNI surface, declared by hand instead of through a binding crate.
//!
//! Only two JNI functions are used, both to reach the memory of a direct `ByteBuffer`. Their
//! positions in the `JNINativeInterface` table come from the NDK's `jni.h` and are fixed by the
//! JNI specification, so they cannot shift between Android or JVM versions.
//!
//! Every export catches panics: unwinding into the JVM is undefined behaviour, and a bug here
//! must turn into an error code, not a corrupted process.

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::engine::with_engine;
use crate::paint::{Clip, Target};

type JniEnv = *mut *const *const c_void;
type JObject = *mut c_void;

const GET_DIRECT_BUFFER_ADDRESS: usize = 230;
const GET_DIRECT_BUFFER_CAPACITY: usize = 231;

const ERROR_BAD_BUFFER: i32 = -1000;
const ERROR_PANIC: i32 = -1001;

/// The bytes of a direct `ByteBuffer`, bounded by its capacity. Valid only for the duration of
/// the JNI call that received `buffer`.
unsafe fn direct_buffer<'a>(env: JniEnv, buffer: JObject) -> Option<&'a mut [u8]> {
    if env.is_null() || buffer.is_null() {
        return None;
    }
    // SAFETY: `env` is the JNIEnv the VM passed to this native method; its first word points
    // at the function table, whose slots 230 and 231 are these two functions per jni.h.
    unsafe {
        let table = *env;
        let address: extern "system" fn(JniEnv, JObject) -> *mut c_void =
            std::mem::transmute(*table.add(GET_DIRECT_BUFFER_ADDRESS));
        let capacity: extern "system" fn(JniEnv, JObject) -> i64 =
            std::mem::transmute(*table.add(GET_DIRECT_BUFFER_CAPACITY));
        let ptr = address(env, buffer);
        let len = capacity(env, buffer);
        if ptr.is_null() || len < 0 {
            return None;
        }
        Some(std::slice::from_raw_parts_mut(ptr as *mut u8, len as usize))
    }
}

fn overlaps(a: &[u8], b: &[u8]) -> bool {
    let (a0, a1) = (a.as_ptr() as usize, a.as_ptr() as usize + a.len());
    let (b0, b1) = (b.as_ptr() as usize, b.as_ptr() as usize + b.len());
    a0 < b1 && b0 < a1
}

fn guarded(f: impl FnOnce() -> i32) -> i32 {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(ERROR_PANIC)
}

fn prefix(bytes: &[u8], length: i32) -> Option<&[u8]> {
    bytes.get(..usize::try_from(length).ok()?)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_syncednotes_engine_render_Native_loadFont(
    env: JniEnv,
    _class: JObject,
    data: JObject,
    length: i32,
) -> i32 {
    guarded(|| {
        let Some(bytes) = (unsafe { direct_buffer(env, data) }) else { return ERROR_BAD_BUFFER };
        let Some(bytes) = prefix(bytes, length) else { return ERROR_BAD_BUFFER };
        with_engine(|e| e.load_font(bytes))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_syncednotes_engine_render_Native_loadImage(
    env: JniEnv,
    _class: JObject,
    data: JObject,
    width: i32,
    height: i32,
) -> i32 {
    guarded(|| {
        let Some(bytes) = (unsafe { direct_buffer(env, data) }) else { return ERROR_BAD_BUFFER };
        let Some(length) = (width as i64).checked_mul(height as i64).and_then(|n| n.checked_mul(4)) else { return ERROR_BAD_BUFFER };
        let Some(bytes) = i32::try_from(length).ok().and_then(|n| prefix(bytes, n)) else { return ERROR_BAD_BUFFER };
        with_engine(|e| e.load_image(bytes, width, height))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_syncednotes_engine_render_Native_freeImage(_env: JniEnv, _class: JObject, id: i32) -> i32 {
    guarded(|| {
        with_engine(|e| e.free_image(id));
        0
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_syncednotes_engine_render_Native_fontMetrics(
    env: JniEnv,
    _class: JObject,
    font: i32,
    out: JObject,
) -> i32 {
    guarded(|| {
        let Some(out) = (unsafe { direct_buffer(env, out) }) else { return ERROR_BAD_BUFFER };
        if out.len() < 32 {
            return ERROR_BAD_BUFFER;
        }
        match with_engine(|e| e.metrics(font)) {
            Some(m) => {
                for (i, v) in m.iter().enumerate() {
                    out[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
                }
                0
            }
            None => -1,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_syncednotes_engine_render_Native_shape(
    env: JniEnv,
    _class: JObject,
    input: JObject,
    input_length: i32,
    output: JObject,
) -> i32 {
    guarded(|| {
        let Some(input) = (unsafe { direct_buffer(env, input) }) else { return ERROR_BAD_BUFFER };
        let Some(output) = (unsafe { direct_buffer(env, output) }) else { return ERROR_BAD_BUFFER };
        // Two &mut views of the same memory would be undefined behaviour in Rust.
        if overlaps(input, output) {
            return ERROR_BAD_BUFFER;
        }
        let Some(input) = prefix(input, input_length) else { return ERROR_BAD_BUFFER };
        with_engine(|e| e.shape(input, output))
    })
}

#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_syncednotes_engine_render_Native_renderToBuffer(
    env: JniEnv,
    _class: JObject,
    pixels: JObject,
    width: i32,
    height: i32,
    stride: i32,
    clip_left: i32,
    clip_top: i32,
    clip_right: i32,
    clip_bottom: i32,
    commands: JObject,
    commands_length: i32,
) -> i32 {
    guarded(|| {
        let Some(pixels) = (unsafe { direct_buffer(env, pixels) }) else { return ERROR_BAD_BUFFER };
        let Some(commands) = (unsafe { direct_buffer(env, commands) }) else { return ERROR_BAD_BUFFER };
        if overlaps(pixels, commands) {
            return ERROR_BAD_BUFFER;
        }
        let Some(commands) = prefix(commands, commands_length) else { return ERROR_BAD_BUFFER };
        let (Ok(w), Ok(h), Ok(s)) = (usize::try_from(width), usize::try_from(height), usize::try_from(stride)) else {
            return ERROR_BAD_BUFFER;
        };
        let Some(mut target) = Target::new(pixels, w, h, s) else { return ERROR_BAD_BUFFER };
        let clip = Clip { left: clip_left, top: clip_top, right: clip_right, bottom: clip_bottom };
        with_engine(|e| e.render(&mut target, clip, commands))
    })
}

#[cfg(target_os = "android")]
mod android {
    use super::*;

    #[repr(C)]
    struct AndroidBitmapInfo {
        width: u32,
        height: u32,
        stride: u32,
        format: i32,
        flags: u32,
    }

    const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

    #[link(name = "jnigraphics")]
    unsafe extern "C" {
        fn AndroidBitmap_getInfo(env: JniEnv, bitmap: JObject, info: *mut AndroidBitmapInfo) -> i32;
        fn AndroidBitmap_lockPixels(env: JniEnv, bitmap: JObject, pixels: *mut *mut c_void) -> i32;
        fn AndroidBitmap_unlockPixels(env: JniEnv, bitmap: JObject) -> i32;
    }

    /// Draws straight into an Android `Bitmap`'s pixels, so a frame costs no extra copy.
    #[allow(clippy::too_many_arguments)]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_syncednotes_android_BitmapRenderer_render(
        env: JniEnv,
        _class: JObject,
        bitmap: JObject,
        clip_left: i32,
        clip_top: i32,
        clip_right: i32,
        clip_bottom: i32,
        commands: JObject,
        commands_length: i32,
    ) -> i32 {
        guarded(|| {
            let Some(commands) = (unsafe { direct_buffer(env, commands) }) else { return ERROR_BAD_BUFFER };
            let Some(commands) = prefix(commands, commands_length) else { return ERROR_BAD_BUFFER };
            let mut info = AndroidBitmapInfo { width: 0, height: 0, stride: 0, format: 0, flags: 0 };
            if bitmap.is_null() || unsafe { AndroidBitmap_getInfo(env, bitmap, &mut info) } != 0 {
                return ERROR_BAD_BUFFER;
            }
            if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 {
                return ERROR_BAD_BUFFER;
            }
            let mut ptr: *mut c_void = std::ptr::null_mut();
            if unsafe { AndroidBitmap_lockPixels(env, bitmap, &mut ptr) } != 0 || ptr.is_null() {
                return ERROR_BAD_BUFFER;
            }
            let (w, h, s) = (info.width as usize, info.height as usize, info.stride as usize);
            // SAFETY: lockPixels guarantees stride × height bytes at `ptr` until unlockPixels.
            let pixels = unsafe { std::slice::from_raw_parts_mut(ptr as *mut u8, s * h) };
            let result = if overlaps(pixels, commands) {
                ERROR_BAD_BUFFER
            } else {
                match Target::new(pixels, w, h, s) {
                    Some(mut target) => {
                        let clip = Clip { left: clip_left, top: clip_top, right: clip_right, bottom: clip_bottom };
                        with_engine(|e| e.render(&mut target, clip, commands))
                    }
                    None => ERROR_BAD_BUFFER,
                }
            };
            unsafe { AndroidBitmap_unlockPixels(env, bitmap) };
            result
        })
    }
}
