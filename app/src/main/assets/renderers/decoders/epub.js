/*
 * EPUB decoder (EPUB 2 and 3). Reads the book's own structure out of the zip:
 * container.xml -> OPF (manifest + spine) -> one XHTML document per spine item.
 *
 * The parsing core is pure string work so it can be tested under node; only
 * the blob-URL step in load() needs a browser. Nothing is fetched over the
 * network: internal images become blob: URLs, and the harness puts every
 * chapter inside a sandboxed, CSP-locked frame so book markup can never reach
 * the Android bridge.
 */

(function (global) {
  'use strict';

  var MAX_CHAPTERS = 600;
  var MAX_HTML_CHARS = 24 * 1024 * 1024;

  var IMAGE_TYPES = /^image\//i;
  var IMAGE_EXTENSIONS = /\.(png|jpe?g|gif|webp|bmp|svg)$/i;

  /** PK\x03\x04, PK\x05\x06 (empty) or PK\x07\x08 (spanned). */
  function isZipArchive(bytes) {
    return !!bytes && bytes.length > 3 &&
      bytes[0] === 0x50 && bytes[1] === 0x4b &&
      (bytes[2] === 0x03 || bytes[2] === 0x05 || bytes[2] === 0x07);
  }

  /* ------------------------------------------------------------ pure bits */

  function dirname(path) {
    var slash = path.lastIndexOf('/');
    return slash < 0 ? '' : path.substring(0, slash);
  }

  function normalizePath(path) {
    var parts = path.split('/');
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (part === '.' || part === '') {
        if (i === 0 && part === '') out.push('');
        continue;
      }
      if (part === '..') {
        if (out.length > 1 || (out.length === 1 && out[0] !== '')) out.pop();
        continue;
      }
      out.push(part);
    }
    return out.join('/');
  }

  /** Resolves an OPF href (which may be percent-encoded) against a base dir. */
  function resolvePath(base, relative) {
    if (!relative) return '';
    if (/^[a-z][a-z0-9+.-]*:/i.test(relative)) return relative;
    var decoded = relative;
    try {
      decoded = decodeURIComponent(relative);
    } catch (error) {
      /* leave it encoded: the zip may store the literal name */
    }
    if (decoded.charAt(0) === '/') return normalizePath(decoded.substring(1));
    return normalizePath((base ? base + '/' : '') + decoded);
  }

  function stripFragment(url) {
    var hash = url.indexOf('#');
    return hash < 0 ? url : url.substring(0, hash);
  }

  function attribute(tag, name) {
    var match = new RegExp(name + '\\s*=\\s*"([^"]*)"', 'i').exec(tag);
    if (match) return match[1];
    match = new RegExp(name + "\\s*=\\s*'([^']*)'", 'i').exec(tag);
    return match ? match[1] : null;
  }

  function tags(xml, name) {
    var out = [];
    var pattern = new RegExp('<' + name + '(\\s[^>]*)?/?>', 'gi');
    var match;
    while ((match = pattern.exec(xml)) !== null) {
      out.push({ tag: match[0], body: match[1] || '' });
    }
    return out;
  }

  function firstText(xml, tagName) {
    var pattern = new RegExp('<' + tagName + '(?:\\s[^>]*)?>([\\s\\S]*?)</' + tagName + '>', 'i');
    var match = pattern.exec(xml);
    if (!match) return null;
    return decodeEntities(match[1].replace(/<[^>]*>/g, '')).trim() || null;
  }

  function decodeEntities(text) {
    return text
      .replace(/&#x([0-9a-f]+);/gi, function (m, hex) {
        return String.fromCharCode(parseInt(hex, 16));
      })
      .replace(/&#(\d+);/g, function (m, dec) {
        return String.fromCharCode(parseInt(dec, 10));
      })
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&nbsp;/g, '\u00a0')
      .replace(/&amp;/g, '&');
  }

  /** META-INF/container.xml -> the OPF path. */
  function parseContainer(xml) {
    var match = /<rootfile\b[^>]*>/i.exec(xml);
    if (!match) return null;
    return attribute(match[0], 'full-path');
  }

  /** The OPF: package metadata, manifest, spine, and the cover pointer. */
  function parseOpf(xml) {
    var manifest = [];
    var items = tags(xml, 'item');
    for (var i = 0; i < items.length; i++) {
      var tag = items[i].tag;
      var id = attribute(tag, 'id');
      var href = attribute(tag, 'href');
      if (!id || !href) continue;
      manifest.push({
        id: id,
        href: href,
        type: attribute(tag, 'media-type') || '',
        properties: attribute(tag, 'properties') || ''
      });
    }

    var spine = [];
    var itemrefs = tags(xml, 'itemref');
    for (var s = 0; s < itemrefs.length; s++) {
      var idref = attribute(itemrefs[s].tag, 'idref');
      if (idref) spine.push(idref);
    }

    var coverId = null;
    for (var m = 0; m < manifest.length; m++) {
      if (/\bcover-image\b/.test(manifest[m].properties)) {
        coverId = manifest[m].id;
        break;
      }
    }
    if (!coverId) {
      var metas = tags(xml, 'meta');
      for (var k = 0; k < metas.length; k++) {
        if ((attribute(metas[k].tag, 'name') || '').toLowerCase() === 'cover') {
          coverId = attribute(metas[k].tag, 'content');
          break;
        }
      }
    }

    return {
      title: firstText(xml, 'dc:title') || firstText(xml, 'title'),
      creator: firstText(xml, 'dc:creator') || firstText(xml, 'creator'),
      language: firstText(xml, 'dc:language'),
      manifest: manifest,
      spine: spine,
      coverId: coverId
    };
  }

  /**
   * Chapter titles, keyed by resolved path. EPUB 3 uses the nav document,
   * EPUB 2 the NCX; both are parsed and merged with nav taking precedence.
   */
  function parseNav(xml, base) {
    var titles = Object.create(null);
    var pattern = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
    var match;
    while ((match = pattern.exec(xml)) !== null) {
      var href = attribute('<a' + match[1] + '>', 'href');
      if (!href) continue;
      var label = decodeEntities(match[2].replace(/<[^>]*>/g, '')).trim();
      if (!label) continue;
      var path = resolvePath(base, stripFragment(href));
      if (titles[path] === undefined) titles[path] = label;
    }
    return titles;
  }

  function parseNcx(xml, base) {
    var titles = Object.create(null);
    // Pair each <content src> with the <text> above it: that is how the NCX is
    // written, and regex cannot chase the actual nesting.
    var pattern = /<navPoint\b[\s\S]*?<text>([\s\S]*?)<\/text>[\s\S]*?<content\s+src="([^"]*)"/gi;
    var match;
    while ((match = pattern.exec(xml)) !== null) {
      var label = decodeEntities(match[1].replace(/<[^>]*>/g, '')).trim();
      if (label) titles[resolvePath(base, stripFragment(match[2]))] = label;
    }
    return titles;
  }

  /** The <body> of a chapter document, without its own <head>. */
  function extractBody(html) {
    if (typeof DOMParser === 'function') {
      try {
        var doc = new DOMParser().parseFromString(html, 'application/xhtml+xml');
        if (doc && doc.body && doc.body.childNodes.length) return doc.body.innerHTML;
        doc = new DOMParser().parseFromString(html, 'text/html');
        if (doc && doc.body) return doc.body.innerHTML;
      } catch (error) {
        /* fall through to the regex */
      }
    }
    var match = /<body\b[^>]*>([\s\S]*?)<\/body>/i.exec(html);
    return match ? match[1] : html;
  }

  /** Rewrites internal references to their blob: URLs. */
  function rewriteResources(html, chapterDir, resources) {
    return html.replace(/\b(src|href|poster)\s*=\s*("([^"]*)"|'([^']*)')/gi,
      function (match, attr, quoted, double, single) {
        var url = double !== undefined ? double : single;
        if (!url) return match;
        if (/^(data:|blob:|https?:|mailto:|#)/i.test(url)) return match;
        var resolved = resolvePath(chapterDir, stripFragment(url));
        var blob = resources[resolved] ||
          resources[resolved.toLowerCase()] ||
          resources[resolvePath(chapterDir, url)];
        if (!blob) return match;
        return attr + '="' + blob + '"';
      });
  }

  /* ------------------------------------------------------------ zip access */

  function buildIndex(zip) {
    var index = Object.create(null);
    zip.forEach(function (path, entry) {
      if (entry.dir) return;
      index[path] = entry;
      var lower = path.toLowerCase();
      if (!(lower in index)) index[lower] = entry;
    });
    return index;
  }

  function findEntry(index, path) {
    if (!path) return null;
    return index[path] || index[path.toLowerCase()] || null;
  }

  /* ---------------------------------------------------------------- load */

  function readText(index, path) {
    var entry = findEntry(index, path);
    if (!entry) return Promise.resolve(null);
    return entry.async('string');
  }

  /**
   * Turns bytes into a book description. Chapters carry their own directory so
   * the harness can resolve their relative resources, and their pre-rewritten
   * HTML so no further parsing is needed at paint time.
   */
  function load(bytes, name, stage) {
    if (typeof JSZip !== 'function') {
      return Promise.reject(new Error('EPUB engine unavailable'));
    }
    if (!isZipArchive(bytes)) {
      // Without this, JSZip's "end of central directory" message reaches the reader.
      return Promise.reject(new Error('This file is not a valid EPUB archive.'));
    }
    var report = typeof stage === 'function' ? stage : function () {};

    return JSZip.loadAsync(bytes).then(function (zip) {
      var index = buildIndex(zip);
      var entryPaths = [];
      zip.forEach(function (path, entry) {
        if (!entry.dir) entryPaths.push(path);
      });

      return readText(index, 'META-INF/container.xml').then(function (container) {
        var opfPath = container ? parseContainer(container) : null;
        if (!opfPath) {
          // Broken container: fall back to the first .opf in the archive.
          var found = null;
          zip.forEach(function (path, entry) {
            if (!found && !entry.dir && /\.opf$/i.test(path)) found = path;
          });
          opfPath = found;
        }
        if (!opfPath) throw new Error('This EPUB has no package document');

        return readText(index, opfPath).then(function (opf) {
          if (!opf) throw new Error('This EPUB has no package document');
          var pkg = parseOpf(opf);
          var base = dirname(opfPath);

          var byId = Object.create(null);
          pkg.manifest.forEach(function (item) { byId[item.id] = item; });

          var spineItems = pkg.spine
            .map(function (idref) { return byId[idref]; })
            .filter(function (item) {
              if (!item) return false;
              return /xhtml|html/i.test(item.type || '') || /\.(x?html?)$/i.test(item.href);
            });
          if (!spineItems.length) {
            // Some books omit media-type; fall back to document extensions.
            spineItems = pkg.manifest.filter(function (item) {
              return /\.(x?html?)$/i.test(item.href);
            });
          }
          if (!spineItems.length) throw new Error('This EPUB has no chapters');
          if (spineItems.length > MAX_CHAPTERS) {
            spineItems = spineItems.slice(0, MAX_CHAPTERS);
          }

          report('laying-out');

          // Blob URLs for every internal image, created once and shared.
          var resources = Object.create(null);
          var queued = Object.create(null);
          var resourcePromises = [];
          pkg.manifest.forEach(function (item) {
            var isImage = IMAGE_TYPES.test(item.type) || isImagePath(item.href);
            if (!isImage) return;
            var path = resolvePath(base, item.href);
            if (queued[path]) return;
            queued[path] = true;
            resourcePromises.push(
              readBinary(index, path).then(function (binary) {
                if (!binary) return;
                var type = item.type || 'application/octet-stream';
                try {
                  resources[path] = URL.createObjectURL(new Blob([binary], { type: type }));
                } catch (error) {
                  /* a resource we cannot wrap is simply left un-rewritten */
                }
              })
            );
          });

          /*
           * Sloppy books reference images that are in the zip but missing from
           * the manifest. Wrapping every image entry we find costs almost
           * nothing and makes those books render instead of showing broken
           * frames.
           */
          entryPaths.forEach(function (path) {
            if (!isImagePath(path) || queued[path]) return;
            queued[path] = true;
            resourcePromises.push(
              readBinary(index, path).then(function (binary) {
                if (!binary) return;
                try {
                  resources[path] = URL.createObjectURL(
                    new Blob([binary], { type: mimeForPath(path) })
                  );
                } catch (error) {
                  /* leave it un-rewritten */
                }
              })
            );
          });

          var cssPromises = pkg.manifest
            .filter(function (item) { return /css/i.test(item.type || ''); })
            .map(function (item) {
              return readText(index, resolvePath(base, item.href));
            });

          var navItem = pkg.manifest.filter(function (item) {
            return /\bnav\b/.test(item.properties);
          })[0];
          var ncxItem = pkg.manifest.filter(function (item) {
            return /ncx/i.test(item.type || '');
          })[0];

          var titlePromises = [
            navItem ? readText(index, resolvePath(base, navItem.href)) : null,
            ncxItem ? readText(index, resolvePath(base, ncxItem.href)) : null
          ];

          return Promise.all([
            Promise.all(resourcePromises),
            Promise.all(cssPromises),
            Promise.all(titlePromises.map(function (p) { return p || Promise.resolve(null); }))
          ]).then(function (groups) {
            var css = groups[1].filter(Boolean).join('\n');
            var titles = Object.create(null);
            var navTitles = groups[2][0] ? parseNav(groups[2][0], base) : null;
            var ncxTitles = groups[2][1] ? parseNcx(groups[2][1], base) : null;
            var key;
            if (navTitles) for (key in navTitles) titles[key] = navTitles[key];
            if (ncxTitles) for (key in ncxTitles) if (!titles[key]) titles[key] = ncxTitles[key];

            var limit = MAX_HTML_CHARS;
            var chapters = [];

            function step(position) {
              if (position >= spineItems.length || limit <= 0) {
                return Promise.resolve();
              }
              var item = spineItems[position];
              var path = resolvePath(base, item.href);
              return readText(index, path).then(function (html) {
                if (html) {
                  var body = stripScripts(extractBody(html));
                  var rewritten = rewriteResources(body, dirname(path), resources);
                  limit -= rewritten.length;
                  chapters.push({
                    title: titles[path] || null,
                    html: rewritten,
                    dir: dirname(path)
                  });
                }
                return step(position + 1);
              });
            }

            return step(0).then(function () {
              if (!chapters.length) throw new Error('This EPUB has no readable chapters');

              var coverPath = pkg.coverId && byId[pkg.coverId]
                ? resolvePath(base, byId[pkg.coverId].href)
                : null;
              var cover = coverPath ? resources[coverPath] || null : null;

              return {
                kind: 'epub',
                title: pkg.title,
                creator: pkg.creator,
                language: pkg.language,
                css: css,
                cover: cover,
                chapters: chapters
              };
            });
          });
        });
      });
    });
  }

  function isImagePath(href) {
    return IMAGE_EXTENSIONS.test(href || '');
  }

  /** Last-resort content type for an image entry with no manifest entry. */
  function mimeForPath(path) {
    var extension = String(path).toLowerCase().replace(/^.*\./, '');
    if (extension === 'jpg' || extension === 'jpeg') return 'image/jpeg';
    if (extension === 'svg') return 'image/svg+xml';
    return 'image/' + extension;
  }

  function readBinary(index, path) {
    var entry = findEntry(index, path);
    if (!entry) return Promise.resolve(null);
    return entry.async('uint8array');
  }

  /**
   * Defense in depth: the frames are sandboxed without allow-scripts and carry
   * a CSP, but stripping script elements from book markup we are about to
   * inject is cheap and makes the intent obvious to the next reader.
   */
  function stripScripts(html) {
    return String(html)
      .replace(/<script\b[\s\S]*?<\/script>/gi, '')
      .replace(/<script\b[^>]*\/?>/gi, '')
      .replace(/\son[a-z]+\s*=\s*"[^"]*"/gi, '')
      .replace(/\son[a-z]+\s*=\s*'[^']*'/gi, '')
      .replace(/\s(href|src)\s*=\s*("|')\s*javascript:[^"']*\2/gi, '');
  }

  global.EpubEngine = {
    load: load,
    parseContainer: parseContainer,
    parseOpf: parseOpf,
    parseNav: parseNav,
    parseNcx: parseNcx,
    isImagePath: isImagePath,
    mimeForPath: mimeForPath,
    isZipArchive: isZipArchive,
    extractBody: extractBody,
    rewriteResources: rewriteResources,
    stripScripts: stripScripts,
    resolvePath: resolvePath,
    normalizePath: normalizePath,
    dirname: dirname,
    decodeEntities: decodeEntities,
    attribute: attribute
  };
})(typeof window !== 'undefined' ? window : globalThis);
