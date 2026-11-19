#!/usr/bin/env node
/**
 * Generates the PWA icons in web/public without any image dependency:
 * a tiny anti-aliased rasterizer for rounded rectangles + a PNG encoder on node:zlib.
 *
 *   node scripts/gen-icons.mjs
 *
 * Output: public/favicon.svg, public/icons/{icon-192,icon-512,maskable-512,apple-touch-icon}.png
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const publicDir = join(here, '..', 'public');
const iconDir = join(publicDir, 'icons');

const BG = [14, 16, 20]; // #0e1014
const BAR = [74, 222, 128]; // #4ade80
const BAR_DIM = [52, 92, 70]; // the "unused" bar, so the glyph reads as a signal meter
const SUPERSAMPLE = 4;

/** Signal bars in a unit box: x, y (top), w, h, radius, color. */
function glyph() {
  const w = 0.16;
  const gap = 0.08;
  const bottom = 0.95;
  const heights = [0.32, 0.52, 0.72, 0.92];
  return heights.map((h, i) => ({
    x: 0.06 + i * (w + gap),
    y: bottom - h,
    w,
    h,
    r: 0.05,
    color: i === 3 ? BAR_DIM : BAR,
  }));
}

function insideRoundRect(px, py, x, y, w, h, r) {
  if (px < x || py < y || px > x + w || py > y + h) return false;
  const cx = Math.min(Math.max(px, x + r), x + w - r);
  const cy = Math.min(Math.max(py, y + r), y + h - r);
  const dx = px - cx;
  const dy = py - cy;
  return dx * dx + dy * dy <= r * r;
}

/**
 * @param size output size in px
 * @param opts.rounded background corner radius as a fraction of size (0 = square, full bleed)
 * @param opts.glyphScale fraction of the icon the glyph box occupies
 */
function render(size, { rounded, glyphScale }) {
  const shapes = [];
  const bgR = rounded * size;
  shapes.push({ x: 0, y: 0, w: size, h: size, r: bgR, color: BG });
  const box = glyphScale * size;
  const off = (size - box) / 2;
  for (const b of glyph()) {
    shapes.push({ x: off + b.x * box, y: off + b.y * box, w: b.w * box, h: b.h * box, r: b.r * box, color: b.color });
  }

  const px = Buffer.alloc(size * size * 4);
  const n = SUPERSAMPLE * SUPERSAMPLE;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < SUPERSAMPLE; sy++) {
        for (let sx = 0; sx < SUPERSAMPLE; sx++) {
          const fx = x + (sx + 0.5) / SUPERSAMPLE;
          const fy = y + (sy + 0.5) / SUPERSAMPLE;
          let color = null;
          for (const s of shapes) if (insideRoundRect(fx, fy, s.x, s.y, s.w, s.h, s.r)) color = s.color;
          if (color) {
            r += color[0];
            g += color[1];
            b += color[2];
            a += 255;
          }
        }
      }
      const i = (y * size + x) * 4;
      // Colours are accumulated only over covered samples; un-premultiply so edges keep their hue.
      const covered = a / 255;
      px[i] = covered ? Math.round(r / covered) : 0;
      px[i + 1] = covered ? Math.round(g / covered) : 0;
      px[i + 2] = covered ? Math.round(b / covered) : 0;
      px[i + 3] = Math.round(a / n);
    }
  }
  return encodePng(size, size, px);
}

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function encodePng(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function svg() {
  const hex = (c) => '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('');
  const box = 0.6 * 512;
  const off = (512 - box) / 2;
  const bars = glyph()
    .map(
      (b) =>
        `<rect x="${(off + b.x * box).toFixed(1)}" y="${(off + b.y * box).toFixed(1)}" width="${(b.w * box).toFixed(1)}" height="${(b.h * box).toFixed(1)}" rx="${(b.r * box).toFixed(1)}" fill="${hex(b.color)}"/>`,
    )
    .join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" rx="${(0.22 * 512).toFixed(1)}" fill="${hex(BG)}"/>${bars}</svg>\n`;
}

mkdirSync(iconDir, { recursive: true });
writeFileSync(join(iconDir, 'icon-192.png'), render(192, { rounded: 0.22, glyphScale: 0.6 }));
writeFileSync(join(iconDir, 'icon-512.png'), render(512, { rounded: 0.22, glyphScale: 0.6 }));
// Maskable: full-bleed background, glyph inside the 80 % safe circle.
writeFileSync(join(iconDir, 'maskable-512.png'), render(512, { rounded: 0, glyphScale: 0.5 }));
// iOS applies its own corner mask.
writeFileSync(join(iconDir, 'apple-touch-icon.png'), render(180, { rounded: 0, glyphScale: 0.6 }));
writeFileSync(join(publicDir, 'favicon.svg'), svg());
console.log('icons written to', iconDir);
