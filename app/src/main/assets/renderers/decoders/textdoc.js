/*
 * Text family decoder. First-party, no dependencies.
 *
 * Covers everything that is already characters: plain text, Markdown, RTF and
 * XML/JSON. It never renders into the host document directly — it returns a
 * plain description and the harness decides how to paint it, which keeps the
 * parsing testable outside a browser.
 *
 *   kind 'prose' -> styled HTML to inject into the surface
 *   kind 'code'  -> pre-escaped text for the monospace block
 *   kind 'frame' -> source for a sandboxed iframe (HTML documents)
 *
 * Every path is character-accurate: we never re-encode the file, we never
 * touch the network, and we cap the work so a 200 MB log cannot wedge the
 * WebView.
 */

(function (global) {
  'use strict';

  var MAX_RENDER_BYTES = 3 * 1024 * 1024;

  /* ---------------------------------------------------------------- bytes */

  /**
   * Decodes a byte array to a string, honouring a UTF-16 byte order mark and
   * sniffing for BOM-less UTF-16 (very common on Windows exports).
   */
  function decodeBytes(bytes) {
    if (bytes.length >= 2) {
      if (bytes[0] === 0xFF && bytes[1] === 0xFE) {
        return new TextDecoder('utf-16le').decode(bytes);
      }
      if (bytes[0] === 0xFE && bytes[1] === 0xFF) {
        return new TextDecoder('utf-16be').decode(bytes);
      }
    }
    var head = bytes.subarray(0, Math.min(bytes.length, 4096));
    var zeroes = 0;
    for (var i = 1; i < head.length; i += 2) {
      if (head[i] === 0) zeroes++;
    }
    if (head.length >= 16 && zeroes > head.length / 4) {
      return new TextDecoder('utf-16le').decode(bytes);
    }
    return new TextDecoder('utf-8').decode(bytes);
  }

  /** Byte-preserving decode for RTF, whose escapes are code page bytes. */
  function decodeLatin1(bytes, limit) {
    var end = Math.min(bytes.length, limit);
    var chunks = [];
    var CHUNK = 8192;
    for (var i = 0; i < end; i += CHUNK) {
      var slice = bytes.subarray(i, Math.min(i + CHUNK, end));
      chunks.push(String.fromCharCode.apply(null, slice));
    }
    return chunks.join('');
  }

  function escapeHtml(text) {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /* --------------------------------------------------------------- cp1252 */

  var CP1252 = {
    0x80: '\u20AC', 0x82: '\u201A', 0x83: '\u0192', 0x84: '\u201E',
    0x85: '\u2026', 0x86: '\u2020', 0x87: '\u2021', 0x88: '\u02C6',
    0x89: '\u2030', 0x8A: '\u0160', 0x8B: '\u2039', 0x8C: '\u0152',
    0x8E: '\u017D', 0x91: '\u2018', 0x92: '\u2019', 0x93: '\u201C',
    0x94: '\u201D', 0x95: '\u2022', 0x96: '\u2013', 0x97: '\u2014',
    0x98: '\u02DC', 0x99: '\u2122', 0x9A: '\u0161', 0x9B: '\u203A',
    0x9C: '\u0153', 0x9E: '\u017E', 0x9F: '\u0178'
  };

  function fromCp1252(code) {
    if (code >= 0x80 && code <= 0x9F) return CP1252[code] || '';
    return String.fromCharCode(code);
  }

  /* ------------------------------------------------------------------ RTF */

  /**
   * Destinations whose entire group is metadata. RTF nests them anywhere, and
   * a picture group can be megabytes of hex — dropping them at the opening
   * brace is what keeps this linear.
   */
  var RTF_SKIP = {
    fonttbl: 1, colortbl: 1, stylesheet: 1, info: 1, pict: 1, object: 1,
    header: 1, headerl: 1, headerr: 1, headerf: 1,
    footer: 1, footerl: 1, footerr: 1, footerf: 1,
    footnote: 1, annotation: 1, atrfstart: 1, atrfend: 1,
    fldinst: 1, datafield: 1, listtable: 1, listoverridetable: 1,
    revtbl: 1, generator: 1, filetbl: 1, xmlnstbl: 1,
    themedata: 1, latentstyles: 1, datastore: 1, mmathpr: 1, colorschememapping: 1
  };

  /**
   * RTF to HTML, covering the subset Word actually emits: bold/italic/
   * underline/strike, paragraphs, tabs, code page and Unicode escapes.
 * Anything outside the subset is dropped deliberately rather than guessed at.
   */
  function rtfToHtml(source) {
    var out = [];
    var bold = false;
    var italic = false;
    var underline = false;
    var strike = false;
    var stack = [];
    var depth = 0;
    var skipDepth = -1;
    var blockOpen = false;
    var buffer = '';

    function activeStyle() {
      var open = '';
      var close = '';
      if (bold) { open += '<strong>'; close = '</strong>' + close; }
      if (italic) { open += '<em>'; close = '</em>' + close; }
      if (underline) { open += '<u>'; close = '</u>' + close; }
      if (strike) { open += '<s>'; close = '</s>' + close; }
      return { open: open, close: close };
    }

    function openBlock() {
      if (!blockOpen && skipDepth < 0) {
        out.push('<p>');
        blockOpen = true;
      }
    }

    function closeBlock() {
      if (blockOpen) {
        out.push('</p>');
        blockOpen = false;
      }
    }

    function flush() {
      if (!buffer) return;
      if (skipDepth >= 0) {
        buffer = '';
        return;
      }
      openBlock();
      var style = activeStyle();
      out.push(style.open + escapeHtml(buffer) + style.close);
      buffer = '';
    }

    function applyControlWord(word, param) {
      var on = param === null || param !== 0;
      switch (word) {
        case 'par':
        case 'pard':
        case 'sect':
        case 'row':
          flush();
          closeBlock();
          break;
        case 'page':
          flush();
          closeBlock();
          out.push('<hr class="rtf-page">');
          break;
        case 'line':
          flush();
          openBlock();
          out.push('<br>');
          break;
        case 'tab':
        case 'cell':
          flush();
          openBlock();
          out.push('<span class="rtf-tab"></span>');
          break;
        case 'b':
          flush();
          bold = on;
          break;
        case 'i':
          flush();
          italic = on;
          break;
        case 'ul':
          flush();
          underline = on;
          break;
        case 'ulnone':
          flush();
          underline = false;
          break;
        case 'strike':
          flush();
          strike = on;
          break;
        default:
          break;
      }
    }

    var i = 0;
    while (i < source.length) {
      var ch = source.charAt(i);

      if (ch === '{') {
        stack.push({ bold: bold, italic: italic, underline: underline, strike: strike });
        depth++;
        i++;
        continue;
      }

      if (ch === '}') {
        flush();
        var saved = stack.pop();
        if (saved) {
          bold = saved.bold;
          italic = saved.italic;
          underline = saved.underline;
          strike = saved.strike;
        }
        depth--;
        if (skipDepth >= 0 && depth < skipDepth) skipDepth = -1;
        i++;
        continue;
      }

      if (ch === '\\') {
        var next = source.charAt(i + 1);
        if (next === '') break;

        /*
         * A backslash before a newline is the "control symbol is a literal
         * character" rule; Cocoa's RTF writer emits it for every soft line
         * break, so a plain-text import arrives as one paragraph of \<LF>s.
         * Reading it as a line break is what makes those files reflow.
         */
        if (next === '\r' || next === '\n') {
          flush();
          openBlock();
          out.push('<br>');
          i += 2;
          if (next === '\r' && source.charAt(i) === '\n') i++;
          continue;
        }

        if (next === '\\' || next === '{' || next === '}') {
          buffer += next;
          i += 2;
          continue;
        }
        if (next === '*') {
          flush();
          skipDepth = depth;
          i += 2;
          continue;
        }
        if (next === "'") {
          var code = parseInt(source.substr(i + 2, 2), 16);
          if (!isNaN(code)) buffer += fromCp1252(code);
          i += 4;
          continue;
        }
        if (next === '~') { buffer += '\u00a0'; i += 2; continue; }
        if (next === '-' || next === '_') { i += 2; continue; }

        var match = /^\\([a-zA-Z]+)(-?\d+)?(?:\r\n|\n|\r| )?/.exec(source.substring(i));
        if (match) {
          var word = match[1].toLowerCase();
          var param = match[2] === undefined ? null : parseInt(match[2], 10);
          i += match[0].length;

          if (word === 'u' && param !== null) {
            flush();
            if (skipDepth < 0) {
              buffer += String.fromCharCode(param < 0 ? param + 65536 : param);
            }
            // The spec makes one fallback character mandatory after \uN.
            if (source.charAt(i) === '?') i++;
            continue;
          }
          if (RTF_SKIP[word]) {
            flush();
            skipDepth = depth;
            continue;
          }
          applyControlWord(word, param);
          continue;
        }

        i += 2;
        continue;
      }

      // RTF has no line semantics for raw newlines: they only pad the source
      // between control words. Text is reflowed from \par and \line.
      if (ch === '\r' || ch === '\n') {
        i++;
        continue;
      }

      buffer += ch;
      i++;
    }

    flush();
    closeBlock();
    return out.join('');
  }

  /* ------------------------------------------------------------- Markdown */

  function markdownToHtml(source) {
    var lines = source.replace(/\r\n?/g, '\n').split('\n');
    var out = [];
    var paragraph = [];
    var listType = null;
    var i = 0;

    function flushParagraph() {
      if (!paragraph.length) return;
      out.push('<p>' + inlineMarkdown(paragraph.join(' ')) + '</p>');
      paragraph = [];
    }

    function openList(type) {
      if (listType !== type) {
        closeList();
        out.push('<' + type + '>');
        listType = type;
      }
    }

    function closeList() {
      if (listType) {
        out.push('</' + listType + '>');
        listType = null;
      }
    }

    while (i < lines.length) {
      var line = lines[i];

      var fence = /^\s*(```|~~~)/.exec(line);
      if (fence) {
        flushParagraph();
        closeList();
        var marker = fence[1];
        var language = line.slice(fence[0].length).trim();
        var code = [];
        i++;
        while (i < lines.length && lines[i].indexOf(marker) !== 0 &&
               !/^\s*(```|~~~)/.test(lines[i])) {
          code.push(lines[i]);
          i++;
        }
        i++;
        out.push('<pre class="md-code"><code' +
          (language ? ' class="language-' + escapeHtml(language) + '"' : '') + '>' +
          escapeHtml(code.join('\n')) + '</code></pre>');
        continue;
      }

      if (/^\s*$/.test(line)) {
        flushParagraph();
        closeList();
        i++;
        continue;
      }

      var heading = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
      if (heading) {
        flushParagraph();
        closeList();
        var level = heading[1].length;
        out.push('<h' + level + '>' + inlineMarkdown(heading[2]) + '</h' + level + '>');
        i++;
        continue;
      }

      if (/^\s{0,3}([-*_])(\s*\1){2,}\s*$/.test(line)) {
        flushParagraph();
        closeList();
        out.push('<hr>');
        i++;
        continue;
      }

      if (/^\s*>/.test(line)) {
        flushParagraph();
        closeList();
        var quoted = [];
        while (i < lines.length && /^\s*>/.test(lines[i])) {
          quoted.push(lines[i].replace(/^\s*>\s?/, ''));
          i++;
        }
        out.push('<blockquote>' + markdownToHtml(quoted.join('\n')) + '</blockquote>');
        continue;
      }

      var bullet = /^\s*[-*+]\s+(.*)$/.exec(line);
      var numbered = /^\s*\d+[.)]\s+(.*)$/.exec(line);
      if (bullet) {
        flushParagraph();
        openList('ul');
        out.push('<li>' + inlineMarkdown(bullet[1]) + '</li>');
        i++;
        continue;
      }
      if (numbered) {
        flushParagraph();
        openList('ol');
        out.push('<li>' + inlineMarkdown(numbered[1]) + '</li>');
        i++;
        continue;
      }

      paragraph.push(line.trim());
      i++;
    }

    flushParagraph();
    closeList();
    return out.join('');
  }

  /**
   * Inline Markdown. Code spans are lifted out first so that asterisks inside
   * `like_this` are never read as emphasis.
   */
  function inlineMarkdown(text) {
    var escaped = escapeHtml(text);
    var codeSpans = [];

    escaped = escaped.replace(/`([^`]+)`/g, function (match, code) {
      codeSpans.push(code);
      return '\u0000' + (codeSpans.length - 1) + '\u0000';
    });

    escaped = escaped
      .replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, function (match, alt, src) {
        return '<img src="' + src + '" alt="' + alt + '" loading="lazy">';
      })
      .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (match, label, href) {
        if (/^(https?:|mailto:|#)/i.test(href)) {
          return '<a href="' + href + '" rel="noreferrer noopener">' + label + '</a>';
        }
        // A relative link cannot be resolved: the file's siblings are not
        // addressable from inside the WebView, so show the words, not a lie.
        return '<span>' + label + '</span>';
      })
      .replace(/(\*\*|__)(?=\S)([\s\S]*?\S)\1/g, '<strong>$2</strong>')
      .replace(/(\*|_)(?=\S)([\s\S]*?\S)\1/g, '<em>$2</em>')
      .replace(/~~(?=\S)([\s\S]*?\S)~~/g, '<del>$1</del>');

    return escaped.replace(/\u0000(\d+)\u0000/g, function (match, index) {
      return '<code>' + codeSpans[Number(index)] + '</code>';
    });
  }

  /* ----------------------------------------------------------- structured */

  function prettyJson(source) {
    try {
      return JSON.stringify(JSON.parse(source), null, 2);
    } catch (error) {
      return null;
    }
  }

  /**
   * Indents XML with the browser's own parser. Returns null when the document
   * does not parse, so the caller can fall back to showing the bytes.
   */
  function prettyXml(source) {
    if (typeof DOMParser !== 'function') return null;
    var doc;
    try {
      doc = new DOMParser().parseFromString(source, 'application/xml');
    } catch (error) {
      return null;
    }
    if (!doc || !doc.documentElement) return null;
    if (doc.getElementsByTagName('parsererror').length) return null;

    var lines = [];
    var INDENT = '  ';

    function walk(node, indent) {
      if (node.nodeType === 3) {
        var text = node.nodeValue.trim();
        if (text) lines.push(indent + text);
        return;
      }
      if (node.nodeType === 8) {
        lines.push(indent + '<!--' + node.nodeValue + '-->');
        return;
      }
      if (node.nodeType !== 1) return;

      var name = node.nodeName;
      var attributes = '';
      for (var a = 0; a < node.attributes.length; a++) {
        attributes += ' ' + node.attributes[a].name + '="' + node.attributes[a].value + '"';
      }

      var children = [];
      for (var c = 0; c < node.childNodes.length; c++) {
        var child = node.childNodes[c];
        if (child.nodeType === 1) children.push(child);
        else if (child.nodeType === 3 && child.nodeValue.trim()) children.push(child);
        else if (child.nodeType === 8) children.push(child);
      }

      if (!children.length) {
        lines.push(indent + '<' + name + attributes + '/>');
        return;
      }
      if (children.length === 1 && children[0].nodeType === 3) {
        lines.push(indent + '<' + name + attributes + '>' +
          children[0].nodeValue.trim() + '</' + name + '>');
        return;
      }
      lines.push(indent + '<' + name + attributes + '>');
      for (var k = 0; k < children.length; k++) walk(children[k], indent + INDENT);
      lines.push(indent + '</' + name + '>');
    }

    walk(doc.documentElement, '');
    return lines.join('\n');
  }

  /* -------------------------------------------------------------- routing */

  function render(bytes, name) {
    var truncated = bytes.byteLength > MAX_RENDER_BYTES;
    var view = truncated ? bytes.subarray(0, MAX_RENDER_BYTES) : bytes;
    var note = truncated
      ? 'Preview — this file is larger than 3 MB, so only the first 3 MB is shown.'
      : null;
    var lower = (name || '').toLowerCase();

    if (/\.rtf$/.test(lower)) {
      var raw = decodeLatin1(view, MAX_RENDER_BYTES);
      if (/^\s*\{\\rtf/.test(raw)) {
        return { kind: 'prose', html: rtfToHtml(raw), note: note };
      }
    }

    var text = decodeBytes(view);

    if (/\.(md|markdown)$/.test(lower)) {
      return { kind: 'prose', html: markdownToHtml(text), note: note };
    }
    if (/\.json$/.test(lower)) {
      var json = prettyJson(text);
      if (json) return { kind: 'code', html: escapeHtml(json), note: note };
    }
    if (/\.(xml|xsd|xsl|plist|rss|atom|opml|svg|tmx|gpx)$/.test(lower)) {
      var xml = prettyXml(text);
      if (xml) return { kind: 'code', html: escapeHtml(xml), note: note };
    }
    if (/\.(html?|xhtml)$/.test(lower)) {
      return { kind: 'frame', source: text, note: note };
    }
    return { kind: 'code', html: escapeHtml(text), note: note };
  }

  global.TextDocEngine = {
    render: render,
    escapeHtml: escapeHtml,
    decodeBytes: decodeBytes,
    decodeLatin1: decodeLatin1,
    rtfToHtml: rtfToHtml,
    markdownToHtml: markdownToHtml,
    inlineMarkdown: inlineMarkdown,
    prettyJson: prettyJson,
    prettyXml: prettyXml,
    fromCp1252: fromCp1252
  };
})(typeof window !== 'undefined' ? window : globalThis);
