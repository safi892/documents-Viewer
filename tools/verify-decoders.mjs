/*
 * Decoder verification.
 *
 *   node tools/verify-decoders.mjs
 *
 * Why this exists: the four files under assets/renderers/decoders/ are pure
 * parsing code with a long shelf life and no compiler watching them — a
 * regression there shows up as an empty viewer on a user's phone, not as a
 * build failure. This runs them under node with no dependencies at all.
 *
 * The OLE2 (.doc) and RTF checks are strongest on macOS, where `textutil` can
 * produce a real, independently-written Word 97-2003 and RTF file. Where it is
 * not available those two blocks report SKIP rather than pretending to pass.
 * To reproduce the binary check by hand:
 *
 *   printf 'Hello world.\n\nSecond paragraph.\n' > /tmp/in.txt
 *   textutil -convert doc -output /tmp/in.doc /tmp/in.txt
 */

import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const require = createRequire(import.meta.url);

const DECODERS = join(root, 'app/src/main/assets/renderers/decoders');
const ENGINES = join(root, 'app/src/main/assets/engines');

/* --------------------------------------------------------------- harness */

let passed = 0;
const failures = [];
const skipped = [];
const pending = [];

/**
 * Runs one check. A check may return a promise; those are collected and awaited
 * before the summary, so an async failure can never be counted as a pass.
 */
function check(name, fn) {
  function record(error) {
    failures.push(`${name}\n      ${error && error.message ? error.message : error}`);
  }
  try {
    const result = fn();
    if (result && typeof result.then === 'function') {
      pending.push(result.then(() => { passed++; }, record));
    } else {
      passed++;
    }
  } catch (error) {
    record(error);
  }
}

function skip(name, why) {
  skipped.push(`${name} (${why})`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message || 'assertion failed');
}

function assertEqual(actual, expected, message) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    throw new Error(`${message || 'not equal'}\n      actual:   ${a}\n      expected: ${e}`);
  }
}

function assertIncludes(haystack, needle, message) {
  if (String(haystack).indexOf(needle) < 0) {
    throw new Error(`${message || 'missing'}: ${JSON.stringify(needle)}\n      in: ${JSON.stringify(String(haystack).slice(0, 300))}`);
  }
}

function assertExcludes(haystack, needle, message) {
  if (String(haystack).indexOf(needle) >= 0) {
    throw new Error(`${message || 'unexpected'}: ${JSON.stringify(needle)}\n      in: ${JSON.stringify(String(haystack).slice(0, 300))}`);
  }
}

/* ---------------------------------------------------------------- loading */

await import(join(DECODERS, 'textdoc.js'));
await import(join(DECODERS, 'legacyoffice.js'));
await import(join(DECODERS, 'epub.js'));
await import(join(DECODERS, 'comic.js'));

const Text = globalThis.TextDocEngine;
const Legacy = globalThis.LegacyOfficeEngine;
const Epub = globalThis.EpubEngine;
const Comic = globalThis.ComicEngine;

const jszipModule = require(join(ENGINES, 'jszip.min.js'));
globalThis.JSZip = jszipModule.default || jszipModule;

/* --------------------------------------------------------------- textdoc */

check('textdoc: decodeBytes reads a UTF-16LE BOM', () => {
  const bytes = new Uint8Array([0xFF, 0xFE, 0x68, 0x00, 0x69, 0x00]);
  assertEqual(Text.decodeBytes(bytes), 'hi');
});

check('textdoc: decodeBytes reads UTF-8', () => {
  assertEqual(Text.decodeBytes(new TextEncoder().encode('h\u00e9llo')), 'h\u00e9llo');
});

check('textdoc: decodeBytes sniffs BOM-less UTF-16', () => {
  const bytes = new Uint8Array([
    0x68, 0x00, 0x65, 0x00, 0x6C, 0x00, 0x6C, 0x00, 0x6F, 0x00,
    0x21, 0x00, 0x21, 0x00, 0x21, 0x00, 0x21, 0x00
  ]);
  assertEqual(Text.decodeBytes(bytes), 'hello!!!!');
});

check('textdoc: cp1252 high range maps to the right glyphs', () => {
  assertEqual(Text.fromCp1252(0xE9), '\u00e9');
  assertEqual(Text.fromCp1252(0x93), '\u201C');
  assertEqual(Text.fromCp1252(0x97), '\u2014');
});

const RTF = [
  '{\\rtf1\\ansi\\ansicpg1252\\deff0',
  '{\\fonttbl{\\f0\\fnil Calibri;}}',
  '{\\*\\generator Riched20 10.0}',
  '{\\colortbl;\\red0\\green0\\blue0;}',
  '\\viewkind4\\uc1\\pard\\f0\\fs22 ',
  'Hello \\b bold\\b0  and \\i italic\\i0 .\\par ',
  "Caf\\'e9 na\\'efve \\u8216?quoted\\u8217?.\\par ",
  '{\\pict\\pngblip 89504e47}\\par ',
  '}'
].join('\n');

check('textdoc: RTF keeps bold and italic runs', () => {
  const html = Text.rtfToHtml(RTF);
  assertIncludes(html, '<strong>bold</strong>', 'bold run');
  assertIncludes(html, '<em>italic</em>', 'italic run');
});

check('textdoc: RTF splits paragraphs and drops empty ones', () => {
  // Two real paragraphs. The third \par follows a skipped picture group, so
  // it would only produce an empty paragraph — which must not be emitted.
  assertEqual((Text.rtfToHtml(RTF).match(/<p>/g) || []).length, 2);
  assertExcludes(Text.rtfToHtml(RTF), '<p></p>', 'empty paragraph');
});

check('textdoc: RTF decodes code page and unicode escapes', () => {
  const html = Text.rtfToHtml(RTF);
  assertIncludes(html, 'Caf\u00e9', 'cp1252 e-acute');
  assertIncludes(html, '\u2018quoted\u2019', 'unicode smart quotes');
});

check('textdoc: RTF drops font, colour and picture groups', () => {
  const html = Text.rtfToHtml(RTF);
  assertExcludes(html, 'Calibri', 'font table');
  assertExcludes(html, 'Riched20', 'generator');
  assertExcludes(html, '89504e47', 'embedded png');
  assertExcludes(html, 'red0', 'colour table');
});

check('textdoc: RTF escapes markup from the source text', () => {
  const html = Text.rtfToHtml('{\\rtf1 a < b & c > d}');
  assertIncludes(html, 'a &lt; b &amp; c &gt; d');
});

check('textdoc: RTF reads a backslash-newline as a line break', () => {
  const html = Text.rtfToHtml('{\\rtf1 one\\\ntwo}');
  assertIncludes(html, 'one<br>two');
});

check('textdoc: markdown renders headings, lists and quotes', () => {
  const html = Text.markdownToHtml('# Title\n\nText here.\n\n- one\n- two\n\n> quoted\n\n---\n');
  assertIncludes(html, '<h1>Title</h1>');
  assertIncludes(html, '<ul><li>one</li><li>two</li></ul>');
  assertIncludes(html, '<blockquote><p>quoted</p></blockquote>');
  assertIncludes(html, '<hr>');
});

check('textdoc: markdown renders emphasis and links', () => {
  const html = Text.markdownToHtml('**bold** and *soft* and [x](https://e.test)');
  assertIncludes(html, '<strong>bold</strong>');
  assertIncludes(html, '<em>soft</em>');
  assertIncludes(html, '<a href="https://e.test" rel="noreferrer noopener">x</a>');
});

check('textdoc: markdown will not autolink a javascript: URL', () => {
  const html = Text.markdownToHtml('[x](javascript:evil())');
  assertExcludes(html, '<a href', 'javascript URL');
  assertIncludes(html, '<span>x</span>');
});

check('textdoc: markdown leaves underscores inside code alone', () => {
  const html = Text.markdownToHtml('`a_b_c` and _real emphasis_');
  assertIncludes(html, '<code>a_b_c</code>');
  assertIncludes(html, '<em>real emphasis</em>');
});

check('textdoc: markdown escapes HTML in the source', () => {
  const html = Text.markdownToHtml('a <script>alert(1)</script> b');
  assertExcludes(html, '<script>');
  assertIncludes(html, '&lt;script&gt;');
});

check('textdoc: fenced code keeps its body verbatim', () => {
  const html = Text.markdownToHtml('```js\nconst a = 1 < 2;\n```');
  assertIncludes(html, '<pre class="md-code">');
  assertIncludes(html, 'const a = 1 &lt; 2;');
});

check('textdoc: prettyJson formats and rejects', () => {
  assertIncludes(Text.prettyJson('{"a":1}'), '\n  "a": 1\n');
  assertEqual(Text.prettyJson('{oops'), null);
});

check('textdoc: render routes by extension', () => {
  const enc = (s) => new TextEncoder().encode(s);
  assertEqual(Text.render(enc('# T'), 'a.md').kind, 'prose');
  assertEqual(Text.render(enc('{"a":1}'), 'a.json').kind, 'code');
  assertEqual(Text.render(enc('<p>hi</p>'), 'a.html').kind, 'frame');
  assertEqual(Text.render(enc('plain'), 'a.txt').kind, 'code');
  assertEqual(Text.render(enc('{oops'), 'a.json').kind, 'code');
});

check('textdoc: render flags a truncated preview', () => {
  const huge = new Uint8Array(3 * 1024 * 1024 + 10);
  const result = Text.render(huge, 'big.log');
  assert(!!result.note, 'expected a truncation note');
});

/* ------------------------------------------------------------------ epub */

const CONTAINER = `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>`;

const OPF = `<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>The &#8216;Big&#8217; Book &amp; More</dc:title>
    <dc:creator>A. Writer</dc:creator>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
    <item id="cover-img" href="images/cover.png" media-type="image/png"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>`;

check('epub: parseContainer finds the package document', () => {
  assertEqual(Epub.parseContainer(CONTAINER), 'OEBPS/content.opf');
});

check('epub: parseOpf reads metadata, spine and cover', () => {
  const pkg = Epub.parseOpf(OPF);
  assertEqual(pkg.title, 'The \u2018Big\u2019 Book & More', 'entity-decoded title');
  assertEqual(pkg.creator, 'A. Writer');
  assertEqual(pkg.spine, ['c1', 'c2']);
  assertEqual(pkg.coverId, 'cover-img');
  assertEqual(pkg.manifest.length, 5);
});

check('epub: resolvePath walks up and down', () => {
  assertEqual(Epub.resolvePath('OEBPS/text', '../Images/pic.png'), 'OEBPS/Images/pic.png');
  assertEqual(Epub.resolvePath('OEBPS/text', 'ch2.xhtml'), 'OEBPS/text/ch2.xhtml');
  assertEqual(Epub.resolvePath('', 'a/b.png'), 'a/b.png');
});

check('epub: resolvePath decodes percent escapes', () => {
  assertEqual(Epub.resolvePath('OEBPS', 'Images/my%20pic.png'), 'OEBPS/Images/my pic.png');
});

check('epub: resolvePath refuses to escape the archive root', () => {
  assertEqual(Epub.resolvePath('OEBPS', '../../../../etc/passwd'), 'etc/passwd');
});

check('epub: parseNav maps nav labels to paths', () => {
  const nav = '<nav epub:type="toc"><ol><li><a href="text/ch1.xhtml">One</a></li></ol></nav>';
  assertEqual(Epub.parseNav(nav, 'OEBPS')['OEBPS/text/ch1.xhtml'], 'One');
});

check('epub: parseNcx pairs each label with its content target', () => {
  const ncx = '<navMap><navPoint id="n1"><navLabel><text>First</text></navLabel>' +
    '<content src="text/ch1.xhtml"/></navPoint></navMap>';
  assertEqual(Epub.parseNcx(ncx, 'OEBPS')['OEBPS/text/ch1.xhtml'], 'First');
});

check('epub: extractBody strips head and keeps body', () => {
  const body = Epub.extractBody('<html><head><style>x{}</style></head><body><p>Hi</p></body></html>');
  assertIncludes(body, '<p>Hi</p>');
  assertExcludes(body, '<style>');
});

check('epub: stripScripts removes script and event handlers', () => {
  const cleaned = Epub.stripScripts('<p onclick="x()">a</p><script>evil()</script>' +
    '<a href="javascript:evil()">z</a>');
  assertExcludes(cleaned, 'script');
  assertExcludes(cleaned, 'onclick');
  assertExcludes(cleaned, 'javascript:');
});

check('epub: rewriteResources swaps internal references for blobs', () => {
  const out = Epub.rewriteResources(
    '<img src="../images/p.png"><a href="ch2.xhtml">n</a>',
    'OEBPS/text',
    { 'OEBPS/images/p.png': 'blob:pic' }
  );
  assertIncludes(out, 'src="blob:pic"');
  assertIncludes(out, '<a href="ch2.xhtml">');
});

check('epub: rewriteResources leaves external and data URLs alone', () => {
  const out = Epub.rewriteResources('<img src="https://e.test/p.png"><img src="data:image/png;base64,AA">', 'OEBPS', {});
  assertIncludes(out, 'https://e.test/p.png');
  assertIncludes(out, 'data:image/png;base64,AA');
});

check('epub: load reads a whole book', async () => {
  const png = new Uint8Array([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const zip = new globalThis.JSZip();
  zip.file('mimetype', 'application/epub+zip');
  zip.file('META-INF/container.xml', CONTAINER);
  zip.file('OEBPS/content.opf', OPF);
  zip.file('OEBPS/nav.xhtml', '<nav><ol><li><a href="text/ch1.xhtml">Chapter One</a></li></ol></nav>');
  zip.file('OEBPS/style.css', 'p { margin: 0; }');
  zip.file('OEBPS/images/cover.png', png);
  zip.file('OEBPS/images/p.png', png);
  zip.file('OEBPS/text/ch1.xhtml', '<html><head><title>c1</title></head><body>' +
    '<h1>Chapter One</h1><p>Hello.</p><img src="../images/p.png"><script>evil()</script></body></html>');
  zip.file('OEBPS/text/ch2.xhtml', '<html><body><h1>Chapter Two</h1></body></html>');

  return zip.generateAsync({ type: 'uint8array' }).then((bytes) => Epub.load(bytes, 'book.epub')
    .then((book) => {
      assertEqual(book.title, 'The \u2018Big\u2019 Book & More');
      assertEqual(book.creator, 'A. Writer');
      assertEqual(book.chapters.length, 2, 'chapter count');
      assertEqual(book.chapters[0].title, 'Chapter One', 'nav title attached');
      assertIncludes(book.chapters[0].html, 'src="blob:', 'manifest image rewritten');
      assertExcludes(book.chapters[0].html, '<script>', 'script stripped');
      assertIncludes(book.css, 'margin: 0', 'stylesheet inlined');
      assert(!!book.cover, 'cover blob resolved');
    }));
});

check('epub: load wraps images that are missing from the manifest', async () => {
  // Real books reference files the manifest forgot. Those must still render.
  const png = new Uint8Array([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const zip = new globalThis.JSZip();
  zip.file('META-INF/container.xml', CONTAINER);
  zip.file('OEBPS/content.opf', '<?xml version="1.0"?><package version="3.0">' +
    '<metadata><dc:title>Loose</dc:title></metadata><manifest>' +
    '<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>' +
    '</manifest><spine><itemref idref="c1"/></spine></package>');
  zip.file('OEBPS/text/ch1.xhtml', '<html><body><img src="../loose/extra.png"></body></html>');
  zip.file('OEBPS/loose/extra.png', png);

  return zip.generateAsync({ type: 'uint8array' }).then((bytes) => Epub.load(bytes, 'loose.epub')
    .then((book) => {
      assertIncludes(book.chapters[0].html, 'src="blob:', 'undeclared image rewritten');
    }));
});

check('epub: load rejects a zip that is not a book', async () => {
  const zip = new globalThis.JSZip();
  zip.file('hello.txt', 'not a book');
  return zip.generateAsync({ type: 'uint8array' })
    .then((bytes) => Epub.load(bytes, 'x.epub'))
    .then(
      () => { throw new Error('expected the load to fail'); },
      (error) => assertIncludes(error.message, 'package document')
    );
});

check('epub: load rejects a file that is not a zip', async () => {
  // Raw JSZip wording ("end of central directory") must not reach the reader.
  await Epub.load(new TextEncoder().encode('not a zip at all'), 'x.epub')
    .then(
      () => { throw new Error('expected the load to fail'); },
      (error) => assertEqual(error.message, 'This file is not a valid EPUB archive.')
    );
});

check('epub: isZipArchive accepts PK signatures only', () => {
  const magic = (c) => new Uint8Array([0x50, 0x4B, c, 0x04, 0x00, 0x00]);
  assert(Epub.isZipArchive(magic(0x03)), 'local header rejected');
  assert(Epub.isZipArchive(magic(0x05)), 'empty archive rejected');
  assert(Epub.isZipArchive(magic(0x07)), 'spanned archive rejected');
  assert(!Epub.isZipArchive(new TextEncoder().encode('plain text')), 'text accepted');
  assert(!Epub.isZipArchive(new Uint8Array(2)), 'short buffer accepted');
  assert(!Epub.isZipArchive(null), 'null accepted');
});

/* ----------------------------------------------------------------- comic */

check('comic: pagePaths keeps images and drops system noise', () => {
  const paths = ['page1.png', '__MACOSX/._page1.png', 'Thumbs.db', 'readme.txt', 'page2.jpg'];
  assertEqual(Comic.pagePaths(paths), ['page1.png', 'page2.jpg']);
});

check('comic: page order is natural, not byte order', () => {
  assertEqual(Comic.pagePaths(['p10.png', 'p2.png', 'p1.png']), ['p1.png', 'p2.png', 'p10.png']);
});

check('comic: mimeFor covers the archive types', () => {
  assertEqual(Comic.mimeFor('a.JPG'), 'image/jpeg');
  assertEqual(Comic.mimeFor('a.webp'), 'image/webp');
  assertEqual(Comic.mimeFor('a.unknown'), 'application/octet-stream');
});

check('comic: load turns a zip into ordered page blobs', async () => {
  const png = new Uint8Array([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const zip = new globalThis.JSZip();
  zip.file('p10.png', png);
  zip.file('p2.png', png);
  zip.file('notes.txt', 'ignore me');
  return zip.generateAsync({ type: 'uint8array' }).then((bytes) => Comic.load(bytes, 'c.cbz')
    .then((comic) => {
      assertEqual(comic.pages.length, 2);
      assertEqual(comic.pages[0].name, 'p2.png', 'natural order');
      assertIncludes(comic.pages[0].url, 'blob:');
    }));
});

check('comic: load rejects an archive with no pages', async () => {
  const zip = new globalThis.JSZip();
  zip.file('notes.txt', 'nothing here');
  return zip.generateAsync({ type: 'uint8array' })
    .then((bytes) => Comic.load(bytes, 'x.cbz'))
    .then(
      () => { throw new Error('expected the load to fail'); },
      (error) => assertIncludes(error.message, 'no page images')
    );
});

check('comic: load rejects a file that is not a zip', async () => {
  await Comic.load(new TextEncoder().encode('not a zip at all'), 'x.cbz')
    .then(
      () => { throw new Error('expected the load to fail'); },
      (error) => assertEqual(error.message, 'This file is not a valid CBZ archive.')
    );
});

/* ---------------------------------------------------------------- legacy */

check('legacy: isCfb recognises the OLE2 magic', () => {
  const header = new Uint8Array(512);
  [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1].forEach((b, i) => { header[i] = b; });
  assert(Legacy.isCfb(header), 'magic not recognised');
  header[0] = 0x50;
  assert(!Legacy.isCfb(header), 'false positive');
  assert(!Legacy.isCfb(new Uint8Array(8)), 'short buffer');
});

check('legacy: extract refuses a plain-text file', () => {
  assertEqual(Legacy.extract(new TextEncoder().encode('just words')), null);
});

check('legacy: looksLikeText rejects a control-character soup', () => {
  assert(Legacy.looksLikeText('Los Angeles is a city in California.'), 'prose rejected');
  assert(!Legacy.looksLikeText('\u0001\u0002\u0003'.repeat(200)), 'garbage accepted');
});

/* Real Word 97-2003 and RTF files, when macOS can mint them. */
let textutil = false;
try {
  execFileSync('textutil', ['-help'], { stdio: 'ignore' });
  textutil = true;
} catch (error) {
  textutil = false;
}

if (textutil) {
  const dir = mkdtempSync(join(tmpdir(), 'reader-decoders-'));
  const source = join(dir, 'sample.txt');
  writeFileSync(source,
    'Alpha beta gamma.\n\nSecond paragraph with accents: caf\u00e9, na\u00efve, ' +
    '\u201cquotes\u201d and an em dash \u2014 here.\n\nThird line ends.\n');

  const rtfPath = join(dir, 'sample.rtf');
  const docPath = join(dir, 'sample.doc');
  execFileSync('textutil', ['-convert', 'rtf', '-output', rtfPath, source]);
  execFileSync('textutil', ['-convert', 'doc', '-output', docPath, source]);

  check('legacy: a real .doc yields readable text', () => {
    const bytes = new Uint8Array(readFileSync(docPath));
    assert(Legacy.isCfb(bytes), 'textutil .doc is not OLE2?');
    const result = Legacy.extract(bytes);
    assert(!!result, 'extract returned null for a real .doc');
    assertIncludes(result.html, 'Alpha beta gamma.', 'first paragraph');
    assertIncludes(result.html, 'caf\u00e9', 'accented text');
    assertIncludes(result.html, '\u201cquotes\u201d', 'smart quotes survive cp1252');
    assertIncludes(result.html, '\u2014', 'em dash');
    assert((result.html.match(/<p>/g) || []).length >= 3, 'paragraph count');
  });

  check('textdoc: a real .rtf renders to paragraphs', () => {
    const result = Text.render(new Uint8Array(readFileSync(rtfPath)), 'sample.rtf');
    assertEqual(result.kind, 'prose');
    assertIncludes(result.html, 'Alpha beta gamma.');
    assertIncludes(result.html, 'caf\u00e9');
  });
} else {
  skip('legacy: a real .doc yields readable text', 'textutil not available');
  skip('textdoc: a real .rtf renders to paragraphs', 'textutil not available');
}

/* ---------------------------------------------------------------- report */

await Promise.all(pending);

console.log(`\n  ${passed} passed, ${failures.length} failed, ${skipped.length} skipped\n`);

if (skipped.length) {
  skipped.forEach((name) => console.log(`  SKIP  ${name}`));
  console.log('');
}

if (failures.length) {
  console.error('  FAILURES\n');
  failures.forEach((failure, index) => console.error(`  ${index + 1}. ${failure}\n`));
  process.exit(1);
}

console.log('  all decoder checks passed\n');
