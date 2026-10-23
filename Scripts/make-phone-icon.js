// Makes the phone's launcher icon from Design/AppIcon-artwork.png, with macOS's own AppKit
// (JavaScript for Automation, so no toolchain is needed). Run from the repo:
//
//     for pair in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
//       osascript -l JavaScript Scripts/make-phone-icon.js "$PWD/Design/AppIcon-artwork.png" \
//         "$PWD/android/app/src/main/res/mipmap-${pair%%:*}/ic_launcher_foreground.png" ${pair##*:} 0.62
//     done
//
// The artwork is drawn at 62% of the adaptive icon's layer, so the notepad fills about 70% of
// what a launcher shows, as in the Mac's icon; at 77% it looked zoomed in. Around it is the
// artwork's own background colour, which its edges fade into, or a square seam would show.

ObjC.import('AppKit');
function run(argv) {
  const [src, out, size, scale] = [argv[0], argv[1], parseInt(argv[2]), parseFloat(argv[3])];
  const image = $.NSImage.alloc.initWithContentsOfFile(src);
  const rep = $.NSBitmapImageRep.alloc.initWithBitmapDataPlanesPixelsWidePixelsHighBitsPerSampleSamplesPerPixelHasAlphaIsPlanarColorSpaceNameBytesPerRowBitsPerPixel(null, size, size, 8, 4, true, false, $.NSDeviceRGBColorSpace, 0, 0);
  $.NSGraphicsContext.saveGraphicsState;
  const ctx = $.NSGraphicsContext.graphicsContextWithBitmapImageRep(rep);
  $.NSGraphicsContext.setCurrentContext(ctx);
  ctx.imageInterpolation = $.NSImageInterpolationHigh;
  const bg = $.NSColor.colorWithDeviceRedGreenBlueAlpha(0xF5/255, 0xF1/255, 0xEB/255, 1);
  const clear = $.NSColor.colorWithDeviceRedGreenBlueAlpha(0xF5/255, 0xF1/255, 0xEB/255, 0);
  bg.setFill; $.NSRectFill($.NSMakeRect(0, 0, size, size));
  const side = size * scale, o = (size - side) / 2;
  image.drawInRectFromRectOperationFraction($.NSMakeRect(o, o, side, side), $.NSZeroRect, $.NSCompositingOperationSourceOver, 1);
  // Each edge fades into the background, so no square seam shows around the artwork.
  const band = side * 0.14;
  const fade = $.NSGradient.alloc.initWithStartingColorEndingColor(bg, clear);
  fade.drawInRectAngle($.NSMakeRect(o, o, band, side), 0);
  fade.drawInRectAngle($.NSMakeRect(o + side - band, o, band, side), 180);
  fade.drawInRectAngle($.NSMakeRect(o, o, side, band), 90);
  fade.drawInRectAngle($.NSMakeRect(o, o + side - band, side, band), 270);
  $.NSGraphicsContext.restoreGraphicsState;
  rep.representationUsingTypeProperties($.NSBitmapImageFileTypePNG, $()).writeToFileAtomically(out, true);
}
