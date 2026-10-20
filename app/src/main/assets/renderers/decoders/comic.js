/*
 * CBZ decoder: a zip of page images.
 *
 * Small on purpose. The zip reader is already on the device (JSZip), pages are
 * sorted the way a person reads them rather than the way a file system lists
 * them, and each page becomes a blob URL that the harness mounts in an <img>
 * with lazy decoding so a 300-page archive does not decode 300 bitmaps at once.
 *
 * CBR (RAR) is not supported anywhere in this project: it would need a RAR
 * decompressor, and shipping none is better than advertising one.
 */

(function (global) {
  'use strict';

  var MAX_PAGES = 2000;
  var IMAGE_PATH = /\.(png|jpe?g|gif|webp|bmp|avif|heic|heif|svg)$/i;
  var NOISE = /(^|\/)(__MACOSX|\.DS_Store|Thumbs\.db|\._)/i;

  var MIME_BY_EXTENSION = {
    png: 'image/png',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    gif: 'image/gif',
    webp: 'image/webp',
    bmp: 'image/bmp',
    avif: 'image/avif',
    heic: 'image/heic',
    heif: 'image/heif',
    svg: 'image/svg+xml'
  };

  function extensionOf(path) {
    var dot = path.lastIndexOf('.');
    return dot < 0 ? '' : path.substring(dot + 1).toLowerCase();
  }

  function mimeFor(path) {
    return MIME_BY_EXTENSION[extensionOf(path)] || 'application/octet-stream';
  }

  /** PK\x03\x04, PK\x05\x06 (empty) or PK\x07\x08 (spanned). */
  function isZipArchive(bytes) {
    return !!bytes && bytes.length > 3 &&
      bytes[0] === 0x50 && bytes[1] === 0x4b &&
      (bytes[2] === 0x03 || bytes[2] === 0x05 || bytes[2] === 0x07);
  }

  /**
   * "page2.png" before "page10.png". A byte-order sort puts ten first, which
   * silently scrambles an entire book.
   */
  function naturalCompare(a, b) {
    return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
  }

  function pagePaths(paths) {
    return paths
      .filter(function (path) { return IMAGE_PATH.test(path) && !NOISE.test(path); })
      .sort(naturalCompare);
  }

  function load(bytes, name, stage) {
    if (typeof JSZip !== 'function') {
      return Promise.reject(new Error('Comic engine unavailable'));
    }
    if (!isZipArchive(bytes)) {
      // Without this, JSZip's "end of central directory" message reaches the reader.
      return Promise.reject(new Error('This file is not a valid CBZ archive.'));
    }
    var report = typeof stage === 'function' ? stage : function () {};

    return JSZip.loadAsync(bytes).then(function (zip) {
      var paths = [];
      zip.forEach(function (path, entry) {
        if (!entry.dir) paths.push(path);
      });

      var ordered = pagePaths(paths);
      if (!ordered.length) throw new Error('This archive holds no page images');

      var capped = ordered.length > MAX_PAGES;
      if (capped) ordered = ordered.slice(0, MAX_PAGES);

      report('laying-out');

      var pages = [];
      var index = 0;

      function step() {
        if (index >= ordered.length) return Promise.resolve();
        var path = ordered[index++];
        return zip.file(path).async('uint8array').then(function (binary) {
          if (binary && binary.byteLength) {
            var type = mimeFor(path);
            try {
              pages.push({
                name: path,
                url: URL.createObjectURL(new Blob([binary], { type: type }))
              });
            } catch (error) {
              /* a page we cannot wrap is skipped rather than fatal */
            }
          }
          return step();
        });
      }

      return step().then(function () {
        if (!pages.length) throw new Error('This archive holds no readable pages');
        return {
          kind: 'comic',
          pages: pages,
          capped: capped,
          total: paths.length
        };
      });
    });
  }

  global.ComicEngine = {
    load: load,
    pagePaths: pagePaths,
    naturalCompare: naturalCompare,
    mimeFor: mimeFor,
    extensionOf: extensionOf,
    isZipArchive: isZipArchive
  };
})(typeof window !== 'undefined' ? window : globalThis);
