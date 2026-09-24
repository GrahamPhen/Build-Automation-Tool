#!/usr/bin/env node
/**
 * make-fixtures.mjs - build small synthetic schematic files to test schematic-catalog.mjs.
 *
 * Creates, in ./fixtures:
 *   tree_house_test.litematic   v7, 4x3x2, palette {air, stone}, 18 of 24 blocks solid
 *   tree_house_test.schem       Sponge v2, same name and size  -> same-build twin
 *   old_build.litematic         v6 -> must be flagged as an older format
 *   flat.schematic              MCEdit, 5x1x5, 20 of 25 solid
 *   copy_of_tree_house.litematic  byte-identical copy -> duplicate group
 *   broken.litematic            not NBT -> must be reported as unreadable
 *
 * The .zip case is created by the test runner with Compress-Archive, so the zip reader is
 * validated against an archive produced by something other than this code.
 */

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(here, 'fixtures');

/* ---------- tiny NBT writer ---------- */
const TAG = { byte: 1, int: 3, long: 4, string: 8, list: 9, compound: 10, longArray: 12 };

const nbtString = (s) => {
  const b = Buffer.from(s, 'utf8');
  const len = Buffer.alloc(2);
  len.writeUInt16BE(b.length);
  return Buffer.concat([len, b]);
};
const named = (type, name, payload) => Buffer.concat([Buffer.from([type]), nbtString(name), payload]);
const intPayload = (v) => { const b = Buffer.alloc(4); b.writeInt32BE(v); return b; };
const tagInt = (name, v) => named(TAG.int, name, intPayload(v));
const tagString = (name, v) => named(TAG.string, name, nbtString(v));
const compoundBody = (children) => Buffer.concat([...children, Buffer.from([0])]);
const tagCompound = (name, children) => named(TAG.compound, name, compoundBody(children));

/** List of compounds: payload is itemType + length + each item's body (no names, no type). */
const tagListOfCompound = (name, items) => {
  const len = Buffer.alloc(4);
  len.writeInt32BE(items.length);
  return named(TAG.list, name, Buffer.concat([Buffer.from([TAG.compound]), len, ...items.map(compoundBody)]));
};

const tagLongArray = (name, values) => {
  const len = Buffer.alloc(4);
  len.writeInt32BE(values.length);
  const buf = Buffer.alloc(8 * values.length);
  values.forEach((v, i) => buf.writeBigInt64BE(BigInt(v), i * 8));
  return named(TAG.longArray, name, Buffer.concat([len, buf]));
};

const rootCompound = (children) => Buffer.concat([Buffer.from([TAG.compound]), nbtString(''), ...children, Buffer.from([0])]);

/* ---------- packing ---------- */
/** Pack palette indices little-endian at `bits` per entry into an array of 64-bit longs. */
function packIndices(indices, bits) {
  let big = 0n;
  indices.forEach((v, i) => { big |= BigInt(v) << BigInt(i * bits); });
  const totalBits = indices.length * bits;
  const longCount = Math.ceil(totalBits / 64);
  const longs = [];
  for (let i = 0; i < longCount; i++) longs.push((big >> BigInt(i * 64)) & 0xFFFFFFFFFFFFFFFFn);
  return longs;
}

/* ---------- fixtures ---------- */
function litematic({ name, version, sizeX, sizeY, sizeZ, solidCount, author = 'Fixture Author', description = '' }) {
  const volume = sizeX * sizeY * sizeZ;
  // palette: index 0 = air, index 1 = stone -> bits = 2
  const indices = Array.from({ length: volume }, (_, i) => (i < solidCount ? 1 : 0));
  const longs = packIndices(indices, 2);

  // Items of a TAG_List are compound BODIES (children + End), so each palette entry is a
  // list of children, not a pre-built named tag.
  const palette = [
    [tagString('Name', 'minecraft:air')],
    [tagString('Name', 'minecraft:stone')],
  ];

  const region = tagCompound('Main', [
    tagCompound('Position', [tagInt('x', 0), tagInt('y', 0), tagInt('z', 0)]),
    tagCompound('Size', [tagInt('x', sizeX), tagInt('y', sizeY), tagInt('z', sizeZ)]),
    tagListOfCompound('BlockStatePalette', palette),
    tagLongArray('BlockStates', longs),
    tagListOfCompound('Entities', []),
    tagListOfCompound('TileEntities', []),
    tagListOfCompound('PendingBlockTicks', []),
  ]);

  return rootCompound([
    tagInt('MinecraftDataVersion', 4000),
    tagInt('Version', version),
    tagCompound('Metadata', [
      tagString('Name', name),
      tagString('Author', author),
      tagString('Description', description),
      tagCompound('EnclosingSize', [tagInt('x', sizeX), tagInt('y', sizeY), tagInt('z', sizeZ)]),
      tagInt('RegionCount', 1),
      tagInt('TotalBlocks', solidCount),
      tagInt('TotalVolume', volume),
    ]),
    tagCompound('Regions', [region]),
  ]);
}

function schemSponge({ name, version, sizeX, sizeY, sizeZ, solidCount }) {
  const volume = sizeX * sizeY * sizeZ;
  const data = Buffer.alloc(volume);
  for (let i = 0; i < volume; i++) data[i] = i < solidCount ? 1 : 0; // varint, all < 128
  const palette = tagCompound('Palette', [tagInt('minecraft:air', 0), tagInt('minecraft:stone', 1)]);
  const blockData = named(7, 'BlockData', Buffer.concat([intPayload(data.length), data]));
  return rootCompound([
    tagInt('Version', version),
    tagInt('DataVersion', 4000),
    named(2, 'Width', (() => { const b = Buffer.alloc(2); b.writeInt16BE(sizeX); return b; })()),
    named(2, 'Height', (() => { const b = Buffer.alloc(2); b.writeInt16BE(sizeY); return b; })()),
    named(2, 'Length', (() => { const b = Buffer.alloc(2); b.writeInt16BE(sizeZ); return b; })()),
    palette,
    blockData,
    tagCompound('Metadata', [tagString('Name', name), tagString('Author', 'Fixture Author')]),
  ]);
}

function schematicMcedit({ name, sizeX, sizeY, sizeZ, solidCount }) {
  const volume = sizeX * sizeY * sizeZ;
  const blocks = Buffer.alloc(volume);
  for (let i = 0; i < volume; i++) blocks[i] = i < solidCount ? 1 : 0;
  return rootCompound([
    named(2, 'Width', (() => { const b = Buffer.alloc(2); b.writeInt16BE(sizeX); return b; })()),
    named(2, 'Height', (() => { const b = Buffer.alloc(2); b.writeInt16BE(sizeY); return b; })()),
    named(2, 'Length', (() => { const b = Buffer.alloc(2); b.writeInt16BE(sizeZ); return b; })()),
    tagString('Materials', 'Alpha'),
    named(7, 'Blocks', Buffer.concat([intPayload(blocks.length), blocks])),
    named(7, 'Data', Buffer.concat([intPayload(blocks.length), Buffer.alloc(volume)])),
  ]);
}

/* ---------- write them ---------- */
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

const gz = (buf) => zlib.gzipSync(buf, { level: 9 });
const write = (name, buf) => {
  fs.writeFileSync(path.join(out, name), buf);
  console.log(`  ${name}  (${buf.length} bytes)`);
};

const treeHouse = litematic({ name: 'tree_house_test', version: 7, sizeX: 4, sizeY: 3, sizeZ: 2, solidCount: 18 });

console.log('Writing fixtures to ' + out);
write('tree_house_test.litematic', gz(treeHouse));
write('copy_of_tree_house.litematic', gz(treeHouse));                       // byte-identical -> duplicate
write('old_build.litematic', gz(litematic({ name: 'old_build', version: 6, sizeX: 3, sizeY: 3, sizeZ: 3, solidCount: 27 })));
write('tree_house_test.schem', gz(schemSponge({ name: 'tree_house_test', version: 2, sizeX: 4, sizeY: 3, sizeZ: 2, solidCount: 18 })));
write('flat.schematic', gz(schematicMcedit({ name: 'flat_platform', sizeX: 5, sizeY: 1, sizeZ: 5, solidCount: 20 })));
fs.writeFileSync(path.join(out, 'broken.litematic'), Buffer.from('this is not NBT at all, not even gzipped'));
console.log('  broken.litematic  (intentionally invalid)');

console.log('\nExpected results:');
console.log('  tree_house_test.litematic      v7  -> buildable, 18 blocks, 4x3x2');
console.log('  copy_of_tree_house.litematic   byte-identical duplicate of tree_house_test.litematic');
console.log('  tree_house_test.schem          Sponge v2 -> buildable, 18 blocks, same-build twin');
console.log('  old_build.litematic            v6  -> NOT buildable (too old), 27 blocks');
console.log('  flat.schematic                 MCEdit -> buildable, 20 blocks, 5x1x5');
console.log('  broken.litematic               unreadable');
