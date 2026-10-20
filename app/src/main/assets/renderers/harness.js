/*
 * Office harness. Runs inside the sandboxed WebView (virtual origin
 * https://appassets.androidplatform.net) and is the only place JS engines run.
 *
 * Contract with Kotlin:
 *   harness -> AndroidBridge.ready()            once, when __loadDocument exists
 *   harness -> AndroidBridge.stage("reading"|"rendering"|"laying-out")
 *   harness -> AndroidBridge.done()             after first paint of content
 *   harness -> AndroidBridge.error(message)     on any failure
 *   Kotlin  -> window.__loadDocument(url, kind, name)
 *
 * Engines are fetched lazily so a DOCX open never pays for the SheetJS bundle.
 */

(function () {
  'use strict';

  var surface = document.getElementById('surface');
  var styleHost = document.getElementById('docx-styles');
  var loaded = Object.create(null);
  /* Fit callbacks for every sandboxed frame currently on screen. */
  var framesToFit = [];

  function bridge(name, arg) {
    var api = window.AndroidBridge;
    if (!api || typeof api[name] !== 'function') return;
    try {
      if (arg === undefined) api[name]();
      else api[name](String(arg));
    } catch (e) {
      /* bridge failures must never break rendering */
    }
  }

  function stage(name) {
    bridge('stage', name);
  }

  function fail(error) {
    var message = error && error.message ? error.message : String(error);
    bridge('error', message || 'Unknown rendering error');
  }

  function loadScript(src) {
    if (loaded[src]) return loaded[src];
    loaded[src] = new Promise(function (resolve, reject) {
      var el = document.createElement('script');
      el.src = src;
      el.async = false;
      el.onload = function () { resolve(); };
      el.onerror = function () { reject(new Error('Could not load ' + src)); };
      document.head.appendChild(el);
    });
    return loaded[src];
  }

  function fetchBytes(url) {
    return fetch(url, { cache: 'no-store' }).then(function (response) {
      if (!response.ok) throw new Error('Read failed (HTTP ' + response.status + ')');
      return response.arrayBuffer();
    });
  }

  /**
   * The vendored engines (docx-preview, SheetJS, pptx-to-html) are happiest with
   * an ArrayBuffer, so fetchBytes keeps returning one. The first-party decoders
   * index bytes directly, and `new Uint8Array(buffer)` is a view — not a copy —
   * so this costs nothing.
   */
  function asBytes(value) {
    return value instanceof Uint8Array ? value : new Uint8Array(value);
  }

  function clearSurface() {
    surface.textContent = '';
    styleHost.textContent = '';
    surface.dataset.kind = '';
    // The frames are gone; their fit callbacks must not outlive them.
    framesToFit.length = 0;
  }

  function afterPaint(fn) {
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        setTimeout(fn, 60);
      });
    });
  }

  /* ------------------------------------------------------- shared pieces */

  /**
   * OLE2 / Compound File magic. A .doc and a .xls are both OLE2, but only the
   * .doc needs the legacy reader — SheetJS still reads BIFF — so this is used
   * to route the Word path, not the spreadsheet one.
   */
  var CFB_SIGNATURE = [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];

  function isCompoundFile(bytes) {
    if (!bytes || bytes.byteLength < 512) return false;
    for (var i = 0; i < 8; i++) {
      if (bytes[i] !== CFB_SIGNATURE[i]) return false;
    }
    return true;
  }

  /*
   * Every framed surface (ODF, EPUB chapters, HTML documents) is a sandboxed
   * iframe with scripts off AND a content security policy that forbids all
   * network access. A book that references a remote image therefore cannot
   * leak a request, which is what "nothing leaves the device" has to mean.
   */
  var FRAME_CSP =
    "default-src 'none'; img-src blob: data:; style-src 'unsafe-inline'; " +
    "font-src data:; media-src blob: data:; base-uri 'none'; form-action 'none'";

  var FRAME_CSS =
    'html{-webkit-text-size-adjust:100%;}' +
    'body{margin:0;padding:0;background:#ffffff;color:#1b1b1d;' +
    'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,' +
    '"Helvetica Neue",sans-serif;font-size:17px;line-height:1.62;' +
    'overflow-wrap:break-word;}' +
    '@media (prefers-color-scheme:dark){body{background:#fbfbfb;}}' +
    'img,svg,video{max-width:100%;height:auto;}' +
    'p{margin:0 0 0.85em;}' +
    'h1,h2,h3,h4{line-height:1.25;margin:1.4em 0 0.5em;}' +
    'h1:first-child,h2:first-child,h3:first-child{margin-top:0;}' +
    'pre{white-space:pre-wrap;overflow-wrap:anywhere;}' +
    'table{max-width:100%;border-collapse:collapse;}' +
    'td,th{overflow-wrap:anywhere;}';

  function escapeText(text) {
    return String(text === undefined || text === null ? '' : text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  /**
   * A complete document for a sandboxed frame. Book CSS is inlined and its own
   * </style> is neutralised so a stylesheet cannot close the tag and start
   * markup.
   */
  function frameDocument(bodyHtml, css, title) {
    return '<!DOCTYPE html><html><head><meta charset="utf-8">' +
      '<meta http-equiv="Content-Security-Policy" content="' + FRAME_CSP + '">' +
      '<title>' + escapeText(title) + '</title>' +
      '<style>' + FRAME_CSS + '</style>' +
      (css ? '<style>' + String(css).replace(/<\/style/gi, '<\\/style') + '</style>' : '') +
      '</head><body>' + bodyHtml + '</body></html>';
  }

  /*
   * Frames are measured against their content and then given a pixel height,
   * because a nested scrolling document inside a scrolling reader is a trap. A
   * measured height is only correct for the width it was measured at, so every
   * registered frame is re-measured after a rotation — otherwise a chapter
   * measured in portrait clips its text once the phone turns sideways.
   */
  var refitQueued = false;

  window.addEventListener('resize', function () {
    if (refitQueued) return;
    refitQueued = true;
    // Give the frame documents a beat to reflow first.
    setTimeout(function () {
      refitQueued = false;
      for (var i = 0; i < framesToFit.length; i++) framesToFit[i]();
    }, 150);
  });

  /**
   * Grows a frame to its content, now and after every rotation. The small floor
   * is for documents that are genuinely nearly empty; the +24 absorbs the last
   * child's bottom margin, which scrollHeight does not count.
   */
  function sizeFrame(frame, minimum) {
    function fit() {
      var inner = frame.contentDocument;
      if (!inner || !inner.body) return;
      var height = inner.body.scrollHeight;
      if (!height) return;
      frame.style.height = Math.min(Math.max(height + 24, minimum || 48), 40000) + 'px';
    }
    frame.addEventListener('load', fit);
    framesToFit.push(fit);
    return frame;
  }

  function frameElement(bodyHtml, css, title, className, minimum) {
    var frame = document.createElement('iframe');
    frame.className = className;
    frame.setAttribute('sandbox', 'allow-same-origin');
    frame.setAttribute('title', title || 'Document');
    frame.srcdoc = frameDocument(bodyHtml, css, title);
    sizeFrame(frame, minimum);
    return frame;
  }

  function noteElement(text) {
    var note = document.createElement('p');
    note.className = 'engine-note';
    note.textContent = text;
    return note;
  }

  /** A note next to a framed document, so it sits on the reader's background. */
  function appendNote(text) {
    if (!text) return;
    var note = noteElement(text);
    note.classList.add('gutter');
    surface.appendChild(note);
  }

  /* ---------------------------------------------------------------- DOCX */

  function renderDocx(bytes, name) {
    // A .doc is an OLE2 compound file, not a zip: docx-preview cannot open it.
    if (isCompoundFile(asBytes(bytes))) return renderLegacyWord(bytes, name);
    stage('rendering');
    // JSZip must be global before docx-preview evaluates (UMD asks for it).
    return loadScript('/assets/engines/jszip.min.js')
      .then(function () { return loadScript('/assets/engines/docx-preview.min.js'); })
      .then(function () {
        if (!window.docx || typeof window.docx.renderAsync !== 'function') {
          throw new Error('DOCX engine unavailable');
        }
        var host = document.createElement('div');
        host.className = 'docx-host';
        surface.appendChild(host);
        return window.docx.renderAsync(bytes, host, styleHost, {
          className: 'docx',
          inWrapper: true,
          ignoreWidth: true,
          ignoreHeight: true,
          ignoreFonts: false,
          breakPages: true,
          renderHeaders: true,
          renderFooters: false,
          renderFootnotes: true,
          useBase64URL: true,
          experimental: true
        }).then(function (result) {
          // Newer builds return the parsed container; older ones return nothing.
          if (!result && !host.childNodes.length) {
            throw new Error('The document contains no readable content');
          }
          // Table cells and images must never push the page wider than the viewport.
          Array.prototype.forEach.call(host.querySelectorAll('table'), function (table) {
            table.style.maxWidth = '100%';
          });
          rescueListMarkers();
        });
      });
  }

  /**
   * Word draws list markers from Symbol / Wingdings, fonts no Android device
   * ships, so the marker (a private-use code point such as U+F0B7) paints as a
   * missing-glyph box. The engine has already written those declarations into
   * #docx-styles, so rewrite that stylesheet: a real bullet glyph, drawn with
   * the reader's own font stack.
   */
  function rescueListMarkers() {
    Array.prototype.forEach.call(styleHost.querySelectorAll('style'), function (style) {
      var css = style.textContent;
      if (!/font-family:\s*["']?(Symbol|Wingdings)/i.test(css)) return;
      style.textContent = css
        .replace(/font-family:\s*["']?(Symbol|Wingdings)[^;}]*/gi, 'font-family: var(--sans)')
        .replace(/[\uE000-\uF8FF]/g, '\u2022');
    });
  }

  /**
   * Word 97-2003. Extracted as text on purpose: the honest options are clean
   * reflowing text or nothing, and a half-rendered page image would be worse
   * than either. Images and layout are explicitly out of scope.
   */
  function renderLegacyWord(bytes, name) {
    stage('rendering');
    return loadScript('/assets/renderers/decoders/legacyoffice.js').then(function () {
      if (!window.LegacyOfficeEngine) throw new Error('Legacy Word engine unavailable');
      var result = window.LegacyOfficeEngine.extract(asBytes(bytes));
      if (!result) {
        throw new Error('This Word 97-2003 file could not be read. ' +
          'Saving it as .docx will open it.');
      }
      var article = document.createElement('article');
      article.className = 'legacy';
      article.appendChild(noteElement(
        'Text-only view of a Word 97-2003 document \u2014 formatting and images are not drawn.'
      ));
      var body = document.createElement('div');
      body.className = 'legacy-body';
      body.innerHTML = result.html;
      article.appendChild(body);
      surface.appendChild(article);
    });
  }

  /* --------------------------------------------------------------- TEXT */

  /**
   * Plain text, Markdown, RTF, JSON, XML and HTML. The decoder returns what it
   * found; painting is decided here so the decoder stays testable without a DOM.
   */
  function renderText(bytes, name) {
    stage('rendering');
    return loadScript('/assets/renderers/decoders/textdoc.js').then(function () {
      if (!window.TextDocEngine) throw new Error('Text engine unavailable');
      var result = window.TextDocEngine.render(asBytes(bytes), name);
      if (!result || (!result.html && !result.source)) {
        throw new Error('The document contains no readable content');
      }

      if (result.kind === 'frame') {
        // An HTML document is untrusted input: it gets its own sandboxed frame.
        surface.appendChild(
          frameElement(result.source, null, name, 'textdoc-frame')
        );
        appendNote(result.note);
        return;
      }

      var article = document.createElement('article');
      article.className = result.kind === 'prose' ? 'textdoc textdoc-prose' :
        'textdoc textdoc-code';
      if (result.note) article.appendChild(noteElement(result.note));

      var body = document.createElement(result.kind === 'prose' ? 'div' : 'pre');
      body.className = result.kind === 'prose' ? 'textdoc-body' : 'textdoc-pre';
      body.innerHTML = result.html;
      article.appendChild(body);
      surface.appendChild(article);
    });
  }

  /* --------------------------------------------------------------- EPUB */

  var CHAPTER_BATCH = 25;

  /**
   * A book is rendered one sandboxed frame per spine document, appended in
   * batches. Appending all 400 chapters at once would build a 400-frame DOM
   * before the first paint, so further batches arrive as the reader scrolls
   * toward them.
   */
  function renderEpub(bytes, name) {
    stage('rendering');
    return loadScript('/assets/engines/jszip.min.js')
      .then(function () { return loadScript('/assets/renderers/decoders/epub.js'); })
      .then(function () {
        if (!window.EpubEngine) throw new Error('EPUB engine unavailable');
        return window.EpubEngine.load(asBytes(bytes), name, stage);
      })
      .then(function (book) {
        if (book.title) document.title = book.title;
        appendFrontMatter(book);

        var chapters = book.chapters;
        var next = 0;
        var observer = null;

        function appendBatch() {
          var end = Math.min(next + CHAPTER_BATCH, chapters.length);
          for (; next < end; next++) {
            var chapter = chapters[next];
            var label = chapter.title || (book.title || 'Book') + ' \u2014 ' + (next + 1);
            surface.appendChild(
              frameElement(chapter.html, book.css, label, 'epub-chapter')
            );
          }
          if (next >= chapters.length) return;

          var sentinel = document.createElement('div');
          sentinel.className = 'engine-more';
          sentinel.textContent = 'Loading more\u2026';
          surface.appendChild(sentinel);

          if (typeof IntersectionObserver === 'function') {
            observer.observe(sentinel);
          } else {
            // No observer: finish the book now rather than stranding chapters.
            surface.removeChild(sentinel);
            appendBatch();
          }
        }

        if (typeof IntersectionObserver === 'function') {
          observer = new IntersectionObserver(function (entries) {
            for (var i = 0; i < entries.length; i++) {
              if (!entries[i].isIntersecting) continue;
              var target = entries[i].target;
              observer.unobserve(target);
              if (target.parentNode) target.parentNode.removeChild(target);
              requestAnimationFrame(appendBatch);
              return;
            }
          }, { rootMargin: '1600px 0px' });
        }

        appendBatch();
      });
  }

  /** Cover and title, when the package carries them. */
  function appendFrontMatter(book) {
    var hasCover = !!book.cover;
    if (!hasCover && !book.title) return;

    var front = document.createElement('header');
    front.className = 'book-front';

    if (hasCover) {
      var cover = document.createElement('img');
      cover.className = 'book-cover';
      cover.src = book.cover;
      cover.alt = book.title ? 'Cover of ' + book.title : 'Cover';
      cover.decoding = 'async';
      front.appendChild(cover);
    }
    if (book.title) {
      var title = document.createElement('h1');
      title.className = 'book-title';
      title.textContent = book.title;
      front.appendChild(title);
    }
    if (book.creator) {
      var creator = document.createElement('p');
      creator.className = 'book-author';
      creator.textContent = book.creator;
      front.appendChild(creator);
    }
    surface.appendChild(front);
  }

  /* -------------------------------------------------------------- COMIC */

  function renderComic(bytes, name) {
    stage('rendering');
    return loadScript('/assets/engines/jszip.min.js')
      .then(function () { return loadScript('/assets/renderers/decoders/comic.js'); })
      .then(function () {
        if (!window.ComicEngine) throw new Error('Comic engine unavailable');
        return window.ComicEngine.load(asBytes(bytes), name, stage);
      })
      .then(function (comic) {
        var figure = document.createElement('figure');
        figure.className = 'comic';
        comic.pages.forEach(function (page, index) {
          var image = document.createElement('img');
          image.className = 'comic-page';
          // lazy decoding: a 300-page archive must not decode 300 bitmaps to
          // paint page one.
          image.loading = 'lazy';
          image.decoding = 'async';
          image.src = page.url;
          image.alt = 'Page ' + (index + 1);
          figure.appendChild(image);
        });
        surface.appendChild(figure);
        if (comic.capped) {
          appendNote('Showing the first ' + comic.pages.length + ' pages of this archive.');
        }
      });
  }

  /* --------------------------------------------------------------- SHEETS */

  function renderSheet(bytes, name) {
    stage('rendering');
    return loadScript('/assets/engines/xlsx.full.min.js').then(function () {
      if (!window.XLSX) throw new Error('Spreadsheet engine unavailable');
      var isText = /\.(csv|tsv)$/i.test(name);
      var workbook = window.XLSX.read(
        isText ? new TextDecoder('utf-8').decode(new Uint8Array(bytes)) : new Uint8Array(bytes),
        { type: isText ? 'string' : 'array', cellDates: true, cellStyles: false }
      );
      if (!workbook.SheetNames.length) throw new Error('The workbook has no sheets');
      // Diagnostics: forwarded to logcat by the WebView chrome client, and the
      // only way to see which part of a workbook the engine actually decoded.
      console.log('sheet: read ' + workbook.SheetNames.length + ' tab(s): ' +
        workbook.SheetNames.map(function (n) {
          var range = workbook.Sheets[n] && workbook.Sheets[n]['!ref'];
          return n + (range ? '[' + range + ']' : '[empty]');
        }).join(', '));

      workbook.SheetNames.forEach(function (sheetName) {
        var section = document.createElement('section');
        section.className = 'sheet';

        var heading = document.createElement('h2');
        heading.className = 'sheet-name';
        heading.textContent = sheetName;
        section.appendChild(heading);

        var scroller = document.createElement('div');
        scroller.className = 'sheet-scroll';
        var table = document.createElement('div');
        table.className = 'sheet-table';
        table.innerHTML = window.XLSX.utils.sheet_to_html(
          workbook.Sheets[sheetName],
          { header: '', footer: '', id: '' }
        );
        scroller.appendChild(table);
        section.appendChild(scroller);
        surface.appendChild(section);
        console.log('sheet: "' + sheetName + '" rendered ' +
          table.querySelectorAll('tr').length + ' row(s), html=' + table.innerHTML.length +
          ', scroller=' + scroller.clientWidth + 'x' + scroller.clientHeight +
          ', table=' + table.clientWidth + 'x' + table.clientHeight +
          ', body=' + document.body.scrollHeight);
      });
    });
  }

  /* --------------------------------------------------------------- SLIDES */

  function renderSlides(bytes) {
    // Someone renamed a legacy deck: say so, instead of failing opaquely.
    if (isCompoundFile(asBytes(bytes))) {
      return Promise.reject(new Error(
        'This is a legacy PowerPoint file (.ppt). Saving it as .pptx will open it.'
      ));
    }
    stage('rendering');
    return loadScript('/assets/engines/pptx-to-html.min.js').then(function () {
      if (typeof window.pptxToHtml !== 'function') {
        throw new Error('Slides engine unavailable');
      }
      var width = Math.max(320, Math.min(window.innerWidth, 2400)) - 32;
      return window.pptxToHtml(bytes, {
        width: width,
        height: Math.round(width * 0.5625),
        scaleToFit: true,
        letterbox: true
      }).then(function (slides) {
        if (!slides || !slides.length) throw new Error('The presentation has no slides');
        slides.forEach(function (html, index) {
          var frame = document.createElement('figure');
          frame.className = 'slide';
          frame.dataset.index = String(index + 1);

          var pane = document.createElement('div');
          pane.className = 'slide-canvas';
          pane.innerHTML = html;
          frame.appendChild(pane);
          surface.appendChild(frame);
          fitSlide(pane);
        });
      });
    });
  }

  /**
   * The engine letterboxes the deck inside the box we asked for, using a
   * height we can only guess (we do not know the deck's aspect before parsing
   * it) — so a 4:3 deck inside a 16:9 box arrives with slabs of empty mat.
   * The rendered markup carries the deck's own size, so read it back and scale
   * the slide to fill the width at its true aspect instead.
   */
  function fitSlide(pane) {
    var wrap = pane.firstElementChild;
    if (!wrap) return;
    var inner = wrap.querySelector('.slide');
    if (!inner) return;
    var baseWidth = parseFloat(inner.style.width);
    var baseHeight = parseFloat(inner.style.height);
    var targetWidth = wrap.clientWidth;
    if (!baseWidth || !baseHeight || !targetWidth) return;

    var scale = targetWidth / baseWidth;
    wrap.style.height = Math.round(baseHeight * scale) + 'px';
    wrap.style.background = 'transparent';
    inner.style.left = '0px';
    inner.style.top = '0px';
    inner.style.transform = 'scale(' + scale + ')';
  }

  /* ------------------------------------------------------------------ ODF */

  function renderOdf(bytes, name) {
    stage('rendering');
    return import('/assets/engines/odf/index.js').then(function (mod) {
      var Odr = mod.default || mod.Odr;
      if (!Odr) throw new Error('ODF engine unavailable');
      return Odr.load({
        locateFile: function (file) { return '/assets/engines/odf/' + file; }
      }).then(function (odr) {
        var doc = odr.open(new Uint8Array(bytes), { name: name });
        var views = doc.listViews ? doc.listViews() : [];
        var count = views && views.length ? views.length : 1;
        // Sandboxed frames: rendered ODF html can never run script against the bridge.
        // (allow-same-origin only, so the load handler can measure the frame.)
        for (var index = 0; index < count; index++) {
          // render() answers { html, externalResources } — not a string.
          var rendered = doc.render(index);
          var html = rendered && rendered.html ? rendered.html : null;
          if (!html) throw new Error('The document contains no readable content');
          var pending = rendered.externalResources
            ? Object.keys(rendered.externalResources).length : 0;
          if (pending) {
            // Diagnostics: binary assets (images) are not inlined yet.
            console.log('odf: view ' + index + ' references ' + pending +
              ' external resource(s)');
          }
          console.log('odf: view ' + index + ' html=' + html.length);
          var view = views[index] || {};
          var frame = document.createElement('iframe');
          frame.className = 'odf-view';
          frame.setAttribute('sandbox', 'allow-same-origin');
          frame.setAttribute('title', name + (view.name ? ' \u2014 ' + view.name : ''));
          frame.srcdoc = html;
          frame.addEventListener('load', function (event) {
            var inner = event.target.contentDocument;
            if (!inner || !inner.body) return;
            var height = Math.min(inner.body.scrollHeight + 24, 12000);
            event.target.style.height = height + 'px';
          });
          surface.appendChild(frame);
        }
        if (doc.close) doc.close();
      });
    });
  }

  /* -------------------------------------------------------------- ROUTING */

  var renderers = {
    docx: renderDocx,
    sheet: renderSheet,
    slides: renderSlides,
    odf: renderOdf,
    text: renderText,
    epub: renderEpub,
    comic: renderComic
  };

  window.__loadDocument = function (url, kind, name) {
    clearSurface();
    surface.dataset.kind = kind || '';
    var render = renderers[kind];
    if (!render) {
      fail(new Error('No engine for "' + kind + '"'));
      return Promise.resolve();
    }
    stage('reading');
    return fetchBytes(url)
      .then(function (bytes) {
        if (!bytes || bytes.byteLength === 0) throw new Error('The file is empty');
        return render(bytes, name || 'document');
      })
      .then(function () {
        stage('laying-out');
        return new Promise(function (resolve) {
          afterPaint(function () {
            bridge('done');
            resolve();
          });
        });
      })
      .catch(function (error) {
        clearSurface();
        fail(error);
      });
  };

  window.addEventListener('error', function (event) {
    if (event && event.message) fail(event.message);
  });
  window.addEventListener('unhandledrejection', function (event) {
    fail(event.reason);
  });

  console.log('harness ready: engines load lazily on first open');
  bridge('ready');
})();
