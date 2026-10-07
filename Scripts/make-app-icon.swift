// Makes the app icon from Design/AppIcon-artwork.png: the artwork inside Apple's macOS icon
// shape (824 of 1024, continuous corners, a soft shadow), then every size the asset catalog
// needs. Run from the repo:
//
//     swift Scripts/make-app-icon.swift
//
// The artwork is a full square photo; macOS expects the rounded shape with a margin, so it is
// cut to that rather than used as it is.

import AppKit
import SwiftUI
let source = NSImage(contentsOfFile: "Design/AppIcon-artwork.png")!
let icons = "SyncedNotes/Assets.xcassets/AppIcon.appiconset"
let canvas = 1024
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: canvas, pixelsHigh: canvas, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
let context = NSGraphicsContext(bitmapImageRep: rep)!
NSGraphicsContext.current = context
let cg = context.cgContext
// Apple's macOS icon grid: the shape is 824 of 1024, centred, with continuous corners.
let shapeRect = CGRect(x: 100, y: 100, width: 824, height: 824)
let shape = RoundedRectangle(cornerRadius: 185.4, style: .continuous).path(in: shapeRect).cgPath
// A soft shadow under the shape, as the template has.
cg.saveGState()
cg.setShadow(offset: CGSize(width: 0, height: -10), blur: 24, color: NSColor.black.withAlphaComponent(0.28).cgColor)
cg.addPath(shape); cg.setFillColor(NSColor(srgbRed: 0.95, green: 0.93, blue: 0.89, alpha: 1).cgColor); cg.fillPath()
cg.restoreGState()
// The picture fills the shape, a little larger than the shape so the notepad sits well inside.
cg.saveGState()
cg.addPath(shape); cg.clip()
let zoom: CGFloat = 1.06
let side = shapeRect.width * zoom
source.draw(in: CGRect(x: shapeRect.midX - side / 2, y: shapeRect.midY - side / 2, width: side, height: side), from: .zero, operation: .sourceOver, fraction: 1)
cg.restoreGState()
NSGraphicsContext.restoreGraphicsState()
let master = rep.representation(using: .png, properties: [:])!
// Every size, scaled from the master.
for pixels in [16, 32, 64, 128, 256, 512, 1024] {
    let size = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    let small = NSGraphicsContext(bitmapImageRep: size)!
    NSGraphicsContext.current = small
    small.imageInterpolation = .high
    NSImage(data: master)!.draw(in: CGRect(x: 0, y: 0, width: pixels, height: pixels))
    NSGraphicsContext.restoreGraphicsState()
    try! size.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(icons)/icon-\(pixels).png"))
}
print("Wrote the icon to \(icons)")
