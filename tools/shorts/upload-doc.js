// Upload sheet: one block per finished Short (video, thumbnail, title, description with hashtags).
//   node upload-doc.js <rows.json> <out.docx>     (rows from package-all.ps1)
const fs = require('fs');
const { Document, Packer, Paragraph, TextRun, HeadingLevel } = require('docx');

let rows = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^﻿/, ''));
if (!Array.isArray(rows)) rows = [rows];
const older = r => /\(older edit\)$/.test(r.build) ? 1 : 0;           // the current-format finals first
rows.sort((a, b) => older(a) - older(b) || a.build.localeCompare(b.build));
const run = (text, o = {}) => new TextRun({ text, font: 'Calibri', size: o.size || 21, bold: !!o.bold, color: o.color });
// (the title row used to say just "Title"; current Shorts now carry three texts: viral title, SEO title, reel hook)
const label = (k, v) => new Paragraph({ spacing: { after: 40 }, children: [run(k + ': ', { bold: true }), run(v)] });

const children = [
  new Paragraph({ heading: HeadingLevel.HEADING_1, children: [run('MineSurvive Shorts - Upload Sheet', { size: 32, bold: true })] }),
  new Paragraph({ spacing: { after: 240 }, children: [run(`${rows.length} Shorts. Each video is in Desktop\\Shorts\\<build>\\ ` +
    '(a "(phone size)" copy sits next to it); thumbnails are in Desktop\\Shorts Thumbnails and Titles. ' +
    'The description block below each one is also saved as "<build> - description.txt" beside the video.', { color: '555555' })] }),
];
for (const r of rows) {
  children.push(new Paragraph({ heading: HeadingLevel.HEADING_2, spacing: { before: 280 }, children: [run(r.build, { size: 26, bold: true })] }));
  children.push(label('Video', r.video));
  children.push(label('Thumbnail', r.thumb || '(none)'));
  children.push(label(r.seo ? 'Viral title' : 'Title', r.title));
  if (r.seo) children.push(label('SEO title', r.seo));
  if (r.reel) children.push(label('Reel / post hook', r.reel));
  children.push(new Paragraph({ spacing: { before: 80, after: 40 }, children: [run('Description:', { bold: true })] }));
  for (const line of r.description.split(/\r?\n/)) children.push(new Paragraph({ children: [run(line, { size: 20 })] }));
}
Packer.toBuffer(new Document({ sections: [{ properties: { page: { margin: { top: 900, bottom: 900, left: 1000, right: 1000 } } }, children }] }))
  .then(b => { fs.writeFileSync(process.argv[3], b); console.log('doc ok', rows.length); });
