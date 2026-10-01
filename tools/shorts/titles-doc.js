// Shorts titles -> Word document (one table row per video, grouped by build).
const fs = require('fs');
const path = require('path');
const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, WidthType, ShadingType,
  HeadingLevel, PageOrientation, BorderStyle } = require('docx');

const dir = process.argv[2];
const csv = fs.readFileSync(path.join(dir, 'titles.csv'), 'utf8').replace(/^﻿/, '');
const rows = csv.trim().split(/\r?\n/).slice(1).map(l => {
  const m = l.match(/^"((?:[^"]|"")*)","((?:[^"]|"")*)","((?:[^"]|"")*)"$/);
  return m ? m.slice(1).map(s => s.replace(/""/g, '"')) : null;
}).filter(Boolean);

const W = [2300, 5000, 7100];          // landscape Letter text width 14400 (0.5" margins)
const border = { style: BorderStyle.SINGLE, size: 4, color: 'BFBFBF' };
const borders = { top: border, bottom: border, left: border, right: border };
function cell(text, w, opts = {}) {
  return new TableCell({
    width: { size: w, type: WidthType.DXA }, borders,
    shading: opts.fill ? { type: ShadingType.CLEAR, color: 'auto', fill: opts.fill } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({ children: [new TextRun({ text, bold: !!opts.bold, size: opts.size || 20, font: 'Calibri' })] })],
  });
}
const header = new TableRow({ tableHeader: true, children: [
  cell('Build', W[0], { bold: true, fill: 'F4B183' }), cell('Video / thumbnail file', W[1], { bold: true, fill: 'F4B183' }),
  cell('Title', W[2], { bold: true, fill: 'F4B183' })] });
let last = '';
const body = rows.map(([build, video, title], i) => {
  const first = build !== last; last = build;
  const fill = first ? 'F2F2F2' : undefined;
  return new TableRow({ children: [
    cell(first ? build : '', W[0], { bold: first, fill }),
    cell(video.replace(/\.mp4$/, '') + '  (.mp4 / .jpeg)', W[1], { size: 16, fill }),
    cell(title, W[2], { fill })] });
});

const doc = new Document({
  sections: [{
    properties: { page: { size: { width: 12240, height: 15840, orientation: PageOrientation.LANDSCAPE },
      margin: { top: 720, bottom: 720, left: 720, right: 720 } } },
    children: [
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun({ text: 'MineSurvive Shorts - Titles', font: 'Calibri' })] }),
      new Paragraph({ spacing: { after: 200 }, children: [new TextRun({ font: 'Calibri', size: 20,
        text: `${rows.length} videos from ${new Set(rows.map(r => r[0])).size} builds. Each video is in Desktop\\Shorts\\<build>\\ ` +
          '(with-outro\\ holds the copy with the MineSurvive end card); its thumbnail has the same name with .jpeg in ' +
          'Desktop\\Shorts Thumbnails and Titles. B = cinematic cut, C = orbit cut, A = tripod cut.' })] }),
      new Table({ width: { size: 14400, type: WidthType.DXA }, columnWidths: W, rows: [header, ...body] }),
    ],
  }],
});
Packer.toBuffer(doc).then(b => { fs.writeFileSync(path.join(dir, 'Shorts Titles.docx'), b); console.log('ok', rows.length); });
