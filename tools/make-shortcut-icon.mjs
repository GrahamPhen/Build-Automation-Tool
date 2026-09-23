// Generates the StartBuild shortcut icon: an isometric grass block, transparent background.
//
// No dependencies. PNG chunks are written by hand (CRC32 + zlib from node:zlib) and the results are
// wrapped in a multi-size .ico, which is what a Windows shortcut wants. 256x256 must be a PNG inside
// an .ico (BMP-in-ICO at that size is not allowed), and shipping the smaller sizes too keeps the icon
// crisp in the taskbar and at large-icon Explorer views.
//
//   node tools/make-shortcut-icon.mjs
//     -> tools/startbuild-icon.ico          (the icon the shortcut points at)
//     -> tools/startbuild-icon-preview.png  (256px copy, for eyeballing the result)

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const ICO = path.join(here, 'startbuild-icon.ico');
const PREVIEW = path.join(here, 'startbuild-icon-preview.png');

const SIZES = [16, 32, 48, 64, 128, 256];
const SS = 3; // subpixel samples per axis, for smooth edges

const COL = {
  top: [111, 196, 76],
  left: [82, 143, 55],
  right: [64, 114, 42],
  edge: [24, 33, 18],
};

// ---------------------------------------------------------------- PNG

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
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

/** RGBA buffer (size*size*4) -> PNG buffer. */
function png(size, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: RGBA
  ihdr[10] = 0; // deflate
  ihdr[11] = 0; // adaptive filtering
  ihdr[12] = 0; // no interlace

  const stride = size * 4;
  const raw = Buffer.alloc((stride + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (stride + 1)] = 0; // filter type 0 (None)
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, y * stride + stride);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- drawing

/** Is the point inside this convex quad? Order-independent winding test. */
function inside(quad, px, py) {
  let pos = false;
  let neg = false;
  for (let i = 0; i < 4; i++) {
    const [ax, ay] = quad[i];
    const [bx, by] = quad[(i + 1) % 4];
    const cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    if (cross > 0) pos = true;
    else if (cross < 0) neg = true;
    if (pos && neg) return false;
  }
  return true;
}

/**
 * Draw the block at `size` px. Geometry is expressed as fractions of `size`, so every size is a
 * genuine re-render rather than an upscale of one bitmap.
 */
function render(size) {
  const sw = size * 0.215; // half-width of the isometric top diamond (2:1 ratio)
  const hgt = size * 0.30; // vertical extent of the side faces
  const total = 2 * sw + hgt;
  const cy = (size - total) / 2 + sw;
  const cx = size / 2;

  const A = [cx, cy - sw]; // back
  const B = [cx + 2 * sw, cy]; // right
  const C = [cx, cy + sw]; // front
  const D = [cx - 2 * sw, cy]; // left
  const dn = (p) => [p[0], p[1] + hgt];

  // Painted back to front; the three faces do not overlap, so order only matters for the outline.
  const faces = [
    { quad: [C, B, dn(B), dn(C)], col: COL.right },
    { quad: [D, C, dn(C), dn(D)], col: COL.left },
    { quad: [A, B, C, D], col: COL.top },
  ];

  const coverage = faces.map((f) => {
    const cov = new Float32Array(size * size);
    for (let py = 0; py < size; py++) {
      for (let px = 0; px < size; px++) {
        let hits = 0;
        for (let sy = 0; sy < SS; sy++) {
          for (let sx = 0; sx < SS; sx++) {
            if (inside(f.quad, px + (sx + 0.5) / SS, py + (sy + 0.5) / SS)) hits++;
          }
        }
        cov[py * size + px] = hits / (SS * SS);
      }
    }
    return cov;
  });

  const silhouette = new Float32Array(size * size);
  for (let i = 0; i < silhouette.length; i++) {
    silhouette[i] = Math.max(coverage[0][i], coverage[1][i], coverage[2][i]);
  }

  // Outline: dilate the silhouette, then paint the faces back over it.
  const r = Math.max(1, Math.round(size * 0.014));
  const solid = new Uint8Array(size * size);
  for (let i = 0; i < solid.length; i++) solid[i] = silhouette[i] > 0.5 ? 1 : 0;

  const out = Buffer.alloc(size * size * 4, 0);
  const put = (i, [red, green, blue], alpha) => {
    out[i * 4] = red;
    out[i * 4 + 1] = green;
    out[i * 4 + 2] = blue;
    out[i * 4 + 3] = Math.round(Math.min(1, alpha) * 255);
  };

  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      let near = false;
      for (let dy = -r; dy <= r && !near; dy++) {
        for (let dx = -r; dx <= r; dx++) {
          const x = px + dx;
          const y = py + dy;
          if (x < 0 || y < 0 || x >= size || y >= size) continue;
          if (solid[y * size + x]) {
            near = true;
            break;
          }
        }
      }
      if (near) put(py * size + px, COL.edge, 1);
    }
  }

  // Faces on top, blended by coverage so the edges stay soft.
  faces.forEach((f, fi) => {
    const cov = coverage[fi];
    for (let i = 0; i < cov.length; i++) {
      const a = cov[i];
      if (a <= 0) continue;
      put(i, f.col, a);
    }
  });

  return out;
}

/** Box-filter downsample so the small sizes stay clean. */
function downscale(rgba, from, to) {
  const out = Buffer.alloc(to * to * 4, 0);
  const factor = from / to;
  for (let y = 0; y < to; y++) {
    for (let x = 0; x < to; x++) {
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      let n = 0;
      for (let sy = Math.floor(y * factor); sy < Math.floor((y + 1) * factor); sy++) {
        for (let sx = Math.floor(x * factor); sx < Math.floor((x + 1) * factor); sx++) {
          const i = (sy * from + sx) * 4;
          const al = rgba[i + 3] / 255;
          r += rgba[i] * al;
          g += rgba[i + 1] * al;
          b += rgba[i + 2] * al;
          a += al;
          n++;
        }
      }
      const o = (y * to + x) * 4;
      if (a > 0) {
        out[o] = Math.round(r / a);
        out[o + 1] = Math.round(g / a);
        out[o + 2] = Math.round(b / a);
      }
      out[o + 3] = Math.round((a / n) * 255);
    }
  }
  return out;
}

// ---------------------------------------------------------------- build

const master = render(256);
const images = SIZES.map((s) => ({ size: s, data: png(s, s === 256 ? master : downscale(master, 256, s)) }));

const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0); // reserved
header.writeUInt16LE(1, 2); // type: icon
header.writeUInt16LE(images.length, 4);

const dir = Buffer.alloc(16 * images.length);
let offset = header.length + dir.length;
images.forEach((img, i) => {
  const o = i * 16;
  dir[o] = img.size >= 256 ? 0 : img.size; // 0 means 256
  dir[o + 1] = img.size >= 256 ? 0 : img.size;
  dir[o + 2] = 0; // palette colours
  dir[o + 3] = 0; // reserved
  dir.writeUInt16LE(1, o + 4); // colour planes
  dir.writeUInt16LE(32, o + 6); // bits per pixel
  dir.writeUInt32LE(img.data.length, o + 8);
  dir.writeUInt32LE(offset, o + 12);
  offset += img.data.length;
});

fs.writeFileSync(ICO, Buffer.concat([header, dir, ...images.map((i) => i.data)]));
fs.writeFileSync(PREVIEW, png(256, master));

console.log(`wrote ${path.relative(process.cwd(), ICO)}  (${fs.statSync(ICO).size} bytes, sizes: ${SIZES.join(', ')})`);
console.log(`wrote ${path.relative(process.cwd(), PREVIEW)}  (${fs.statSync(PREVIEW).size} bytes)`);
