/*
 * Legacy Office decoder: OLE2 / Compound File Binary, and the Word 97-2003
 * piece table inside it.
 *
 * This exists because .doc is still one of the most common attachments people
 * are sent, and no bundled engine reads it. There is no DOM work here and no
 * dependencies: it is pure binary parsing, so it is unit-testable under node.
 *
 * Scope, stated plainly: this reads the *text* of a .doc, with paragraph
 * breaks preserved. It does not reproduce fonts, tables as tables, or images —
 * a .doc that opens as clean reflowable text is honest; a .doc that opens as a
 * broken page image is not. .ppt is deliberately not handled at all.
 */

(function (global) {
  'use strict';

  var SIGNATURE = [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];
  var END_OF_CHAIN = 0xFFFFFFFE;
  var FREE_SECTOR = 0xFFFFFFFF;
  var MAX_TEXT_CHARS = 2 * 1024 * 1024;

  var CP1252 = {
    0x80: '\u20AC', 0x82: '\u201A', 0x83: '\u0192', 0x84: '\u201E',
    0x85: '\u2026', 0x86: '\u2020', 0x87: '\u2021', 0x88: '\u02C6',
    0x89: '\u2030', 0x8A: '\u0160', 0x8B: '\u2039', 0x8C: '\u0152',
    0x8E: '\u017D', 0x91: '\u2018', 0x92: '\u2019', 0x93: '\u201C',
    0x94: '\u201D', 0x95: '\u2022', 0x96: '\u2013', 0x97: '\u2014',
    0x98: '\u02DC', 0x99: '\u2122', 0x9A: '\u0161', 0x9B: '\u203A',
    0x9C: '\u0153', 0x9E: '\u017E', 0x9F: '\u0178'
  };

  function isCfb(bytes) {
    if (!bytes || bytes.byteLength < 512) return false;
    for (var i = 0; i < 8; i++) {
      if (bytes[i] !== SIGNATURE[i]) return false;
    }
    return true;
  }

  /* ------------------------------------------------------------ container */

  function Cfb(bytes) {
    this.bytes = bytes;
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    this.sectorSize = 1 << this.view.getUint16(30, true);
    this.miniSectorSize = 1 << this.view.getUint16(32, true);
    if (this.sectorSize < 128 || this.sectorSize > 1 << 20) {
      throw new Error('Unsupported sector size');
    }

    this.firstDirSector = this.view.getUint32(48, true);
    this.miniCutoff = this.view.getUint32(56, true) || 4096;
    this.firstMiniFatSector = this.view.getUint32(60, true);

    this.miniStream = new Uint8Array(0);
    this.miniFat = [];

    this._buildFat(this.view.getUint32(44, true), this.view.getUint32(68, true));
    this._readDirectory();
    this._readMiniStream();
  }

  Cfb.prototype._sectorOffset = function (sector) {
    return (sector + 1) * this.sectorSize;
  };

  Cfb.prototype._sectorCount = function () {
    return Math.floor(this.bytes.byteLength / this.sectorSize) - 1;
  };

  Cfb.prototype._buildFat = function (fatSectorCount, firstDifatSector) {
    var view = this.view;
    var perSector = this.sectorSize / 4;
    var sectors = [];
    var i;

    // The header itself holds the first 109 FAT sector locations.
    for (i = 0; i < 109; i++) {
      var sector = view.getUint32(76 + i * 4, true);
      if (sector === FREE_SECTOR || sector === END_OF_CHAIN) continue;
      if (sector >= this._sectorCount()) break;
      sectors.push(sector);
    }

    var difat = firstDifatSector;
    var guard = 0;
    while (difat !== END_OF_CHAIN && difat !== FREE_SECTOR && difat < 0xFFFFFFFA &&
           guard++ < 4096) {
      var base = this._sectorOffset(difat);
      if (base < 0 || base + this.sectorSize > this.bytes.byteLength) break;
      for (i = 0; i < perSector - 1; i++) {
        var next = view.getUint32(base + i * 4, true);
        if (next === FREE_SECTOR || next === END_OF_CHAIN) continue;
        if (next >= this._sectorCount()) continue;
        sectors.push(next);
      }
      difat = view.getUint32(base + (perSector - 1) * 4, true);
    }

    if (fatSectorCount && sectors.length > fatSectorCount) sectors.length = fatSectorCount;

    this.fat = [];
    for (i = 0; i < sectors.length; i++) {
      var offset = this._sectorOffset(sectors[i]);
      for (var j = 0; j < perSector; j++) {
        this.fat.push(view.getUint32(offset + j * 4, true));
      }
    }
  };

  Cfb.prototype._chain = function (start, fat) {
    var out = [];
    var sector = start;
    var guard = 0;
    var limit = fat.length + 2;
    while (sector !== END_OF_CHAIN && sector !== FREE_SECTOR &&
           sector < 0xFFFFFFFA && guard++ < limit) {
      if (sector >= fat.length) break;
      out.push(sector);
      sector = fat[sector];
    }
    return out;
  };

  Cfb.prototype._readDirectory = function () {
    var view = this.view;
    var chain = this._chain(this.firstDirSector, this.fat);
    var entries = [];

    for (var c = 0; c < chain.length; c++) {
      var base = this._sectorOffset(chain[c]);
      for (var offset = 0; offset + 128 <= this.sectorSize; offset += 128) {
        var start = base + offset;
        if (start + 128 > this.bytes.byteLength) break;
        var type = view.getUint8(start + 66);
        if (type !== 1 && type !== 2 && type !== 5) continue;

        var nameLength = view.getUint16(start + 64, true);
        var name = '';
        for (var k = 0; k + 3 <= nameLength; k += 2) {
          var code = view.getUint16(start + k, true);
          if (code === 0) break;
          name += String.fromCharCode(code);
        }

        var low = view.getUint32(start + 120, true);
        var high = view.getUint32(start + 124, true);

        entries.push({
          name: name,
          type: type,
          startSector: view.getUint32(start + 116, true),
          size: high === 0 ? low : low + high * 4294967296
        });
      }
    }
    this.directory = entries;
  };

  Cfb.prototype._readMiniStream = function () {
    var root = null;
    for (var i = 0; i < this.directory.length; i++) {
      if (this.directory[i].type === 5) { root = this.directory[i]; break; }
    }
    if (!root) return;

    var miniFat = this._readRegular(this.firstMiniFatSector, 0);
    for (var o = 0; o + 4 <= miniFat.byteLength; o += 4) {
      this.miniFat.push(this.view.getUint32(miniFat.byteOffset + o, true));
    }
    this.miniStream = this._readSectors(
      this._chain(root.startSector, this.fat), this.sectorSize, 0, root.size
    );
  };

  /** Concatenates a FAT sector chain at `sectorSize` strides. */
  Cfb.prototype._readSectors = function (chain, stride, base, maxBytes) {
    var total = chain.length * stride;
    var out = new Uint8Array(total);
    var source = this.bytes;
    var produced = 0;

    for (var i = 0; i < chain.length; i++) {
      var start = base + chain[i] * stride;
      if (start >= source.byteLength) break;
      if (maxBytes && produced >= maxBytes) break;
      var end = Math.min(start + stride, source.byteLength);
      var chunk = source.subarray(start, end);
      if (maxBytes && produced + chunk.byteLength > maxBytes) {
        chunk = chunk.subarray(0, maxBytes - produced);
      }
      out.set(chunk, produced);
      produced += chunk.byteLength;
    }
    return out.subarray(0, produced);
  };

  Cfb.prototype._readRegular = function (startSector, maxBytes) {
    var chain = this._chain(startSector, this.fat);
    var out = new Uint8Array(chain.length * this.sectorSize);
    var produced = 0;
    for (var i = 0; i < chain.length; i++) {
      if (maxBytes && produced >= maxBytes) break;
      var start = this._sectorOffset(chain[i]);
      if (start >= this.bytes.byteLength) break;
      var end = Math.min(start + this.sectorSize, this.bytes.byteLength);
      var chunk = this.bytes.subarray(start, end);
      if (maxBytes && produced + chunk.byteLength > maxBytes) {
        chunk = chunk.subarray(0, maxBytes - produced);
      }
      out.set(chunk, produced);
      produced += chunk.byteLength;
    }
    return out.subarray(0, produced);
  };

  Cfb.prototype._readMini = function (entry) {
    var chain = this._chain(entry.startSector, this.miniFat);
    return this._readSectors(chain, this.miniSectorSize, 0, entry.size);
  };

  Cfb.prototype.readStream = function (entry) {
    if (!entry || !entry.size) return new Uint8Array(0);
    if (entry.type === 2 && entry.size < this.miniCutoff) return this._readMini(entry);
    return this._readRegular(entry.startSector, entry.size);
  };

  Cfb.prototype.findStream = function (names) {
    for (var n = 0; n < names.length; n++) {
      if (!names[n]) continue;
      for (var i = 0; i < this.directory.length; i++) {
        var entry = this.directory[i];
        if (entry.type === 2 && entry.name === names[n]) return entry;
      }
    }
    return null;
  };

  /* ------------------------------------------------------------ Word text */

  function decodeCp1252(bytes, offset, count) {
    var out = [];
    var limit = Math.min(count, Math.max(0, bytes.byteLength - offset));
    for (var i = 0; i < limit; i++) {
      var code = bytes[offset + i] & 0xFF;
      if (code === 0x0D || code === 0x0B || code === 0x0C) { out.push('\n'); continue; }
      if (code === 0x07) { out.push('\t'); continue; }
      if (code === 0x1E) { out.push('-'); continue; }
      if (code === 0x1F) continue;
      if (code === 0x0A) continue;
      if (code < 0x20) continue;
      out.push(code >= 0x80 && code <= 0x9F ? (CP1252[code] || '') : String.fromCharCode(code));
    }
    return out.join('');
  }

  function decodeUtf16(bytes, offset, count) {
    var out = [];
    var limit = Math.min(count, Math.max(0, Math.floor((bytes.byteLength - offset) / 2)));
    for (var i = 0; i < limit; i++) {
      var code = bytes[offset + i * 2] | (bytes[offset + i * 2 + 1] << 8);
      if (code === 0x0D || code === 0x0B || code === 0x0C) { out.push('\n'); continue; }
      if (code === 0x07) { out.push('\t'); continue; }
      if (code === 0x1E) { out.push('-'); continue; }
      if (code === 0x1F || code === 0x0A) continue;
      if (code < 0x20) continue;
      out.push(String.fromCharCode(code));
    }
    return out.join('');
  }

  /**
   * Word 97+ stores its text as a list of "pieces", each pointing at either
   * an 8-bit code page run or a UTF-16 run inside the WordDocument stream.
   * The map is the CLX in the table stream: a sequence of Prc blocks followed
   * by one Pcdt, whose PlcPcd holds N+1 character positions then N PCDs.
   */
  function pieceTableText(word, table) {
    if (!table || table.byteLength < 16) return null;
    if (word.byteLength < 0x200) return null;

    var wv = new DataView(word.buffer, word.byteOffset, word.byteLength);
    var tv = new DataView(table.buffer, table.byteOffset, table.byteLength);

    var fcClx = wv.getUint32(0x01A2, true);
    var lcbClx = wv.getUint32(0x01A6, true);
    if (!lcbClx || fcClx + lcbClx > table.byteLength) return null;

    var p = fcClx;
    var end = fcClx + lcbClx;
    var plcStart = -1;
    var plcLength = 0;

    while (p + 5 <= end) {
      var clxt = table[p];
      if (clxt === 0x01) {
        p += 3 + tv.getUint16(p + 1, true);
        continue;
      }
      if (clxt === 0x02) {
        plcLength = tv.getUint32(p + 1, true);
        plcStart = p + 5;
        break;
      }
      break;
    }
    if (plcStart < 0 || plcLength < 4) return null;
    if (plcStart + plcLength > table.byteLength) return null;

    var pieces = Math.floor((plcLength - 4) / 12);
    if (pieces <= 0 || pieces > 200000) return null;

    var cpsBase = plcStart;
    var pcdBase = plcStart + (pieces + 1) * 4;
    var out = [];
    var produced = 0;

    for (var i = 0; i < pieces; i++) {
      if (produced >= MAX_TEXT_CHARS) break;
      var cpStart = tv.getUint32(cpsBase + i * 4, true);
      var cpEnd = tv.getUint32(cpsBase + (i + 1) * 4, true);
      var count = cpEnd - cpStart;
      if (count <= 0 || count > MAX_TEXT_CHARS) continue;

      var fc = tv.getUint32(pcdBase + i * 8 + 2, true);
      var compressed = (fc & 0x40000000) !== 0;
      var offset = fc & 0x3FFFFFFF;
      if (compressed) offset = Math.floor(offset / 2);

      var text = compressed
        ? decodeCp1252(word, offset, count)
        : decodeUtf16(word, offset, count);
      out.push(text);
      produced += count;
    }

    return out.length ? out.join('') : null;
  }

  /** Word 6/95 and table-less documents: one raw character run. */
  function simpleText(word) {
    if (word.byteLength < 0x100) return null;
    var wv = new DataView(word.buffer, word.byteOffset, word.byteLength);
    var fcMin = wv.getUint32(0x18, true);
    var ccpText = wv.getUint32(0x4C, true);
    if (!ccpText || ccpText > MAX_TEXT_CHARS || fcMin >= word.byteLength) return null;
    return decodeCp1252(word, fcMin, Math.min(ccpText, word.byteLength - fcMin));
  }

  /**
   * Guard against shipping garbage: real prose is mostly printable, so a
   * decode that is more than a fifth control characters did not work, and the
   * caller should say so instead of showing mojibake.
   */
  function looksLikeText(text) {
    if (!text) return false;
    var sample = text.slice(0, 4000);
    var printable = 0;
    for (var i = 0; i < sample.length; i++) {
      var code = sample.charCodeAt(i);
      if (code === 9 || code === 10 || (code >= 32 && code !== 0xFFFD)) printable++;
    }
    return printable >= sample.length * 0.8;
  }

  function paragraphHtml(text) {
    var lines = String(text).replace(/\r\n?/g, '\n').split('\n');
    var out = [];
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].replace(/[ \t]+$/, '');
      if (!line.length) continue;
      out.push('<p>' + line
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/\t/g, '<span class="rtf-tab"></span>') + '</p>');
    }
    return out.join('');
  }

  /**
   * Decodes an OLE2 file. Returns null when this is not a document we can read
   * (which the harness reports as a plain, honest failure).
   */
  function extract(bytes) {
    if (!isCfb(bytes)) return null;

    var cfb;
    try {
      cfb = new Cfb(bytes);
    } catch (error) {
      return null;
    }

    var wordEntry = cfb.findStream(['WordDocument']);
    if (wordEntry) {
      var word = cfb.readStream(wordEntry);
      if (word.byteLength >= 0x200) {
        // Bit 9 of the FIB flags says which stream holds the piece table.
        var flags = word[0x0A] | (word[0x0B] << 8);
        var preferred = (flags & 0x0200) ? '1Table' : '0Table';
        var alternate = preferred === '1Table' ? '0Table' : '1Table';
        var tableEntry = cfb.findStream([preferred, alternate]);
        var table = tableEntry ? cfb.readStream(tableEntry) : null;

        var text = pieceTableText(word, table) || simpleText(word);
        if (looksLikeText(text)) {
          return {
            kind: 'doc',
            html: paragraphHtml(text),
            characters: text.length
          };
        }
      }
    }

    return null;
  }

  global.LegacyOfficeEngine = {
    isCfb: isCfb,
    extract: extract,
    Cfb: Cfb,
    pieceTableText: pieceTableText,
    decodeCp1252: decodeCp1252,
    decodeUtf16: decodeUtf16,
    looksLikeText: looksLikeText,
    paragraphHtml: paragraphHtml
  };
})(typeof window !== 'undefined' ? window : globalThis);
