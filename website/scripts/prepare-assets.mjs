import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import sharp from 'sharp';
import { exportKeyboardReference } from './export-keyboard-reference.mjs';

await exportKeyboardReference();

// All input is local: this script never fetches an image or a font from the web.
const root = fileURLToPath(new URL('../', import.meta.url));
const original = path.join(root, 'assets/original/turboj-editor.png');
const images = path.join(root, 'public/images');
const fonts = path.join(root, 'public/fonts');
const licenses = path.join(root, 'public/licenses');
const originalHash = '27e0f5c593105574e60abd6e3055db525f119a20f80e3ddcf01ce3727cd9bcc8';
const sha256 = (buffer) => createHash('sha256').update(buffer).digest('hex');
const originalBuffer = await readFile(original);

if (sha256(originalBuffer) !== originalHash) {
  throw new Error('The original TurboJ capture has changed. Review its provenance before generating assets.');
}

await Promise.all([images, fonts, licenses].map((directory) => mkdir(directory, { recursive: true })));
const metadata = await sharp(originalBuffer).metadata();
if (metadata.width !== 1060 || metadata.height !== 740) {
  throw new Error('Unexpected original screenshot dimensions.');
}

await copyFile(original, path.join(images, 'turboj-editor.png'));
await sharp(originalBuffer).webp({ lossless: true, effort: 6 }).toFile(path.join(images, 'turboj-editor.webp'));
for (const width of [530, 795]) {
  await sharp(originalBuffer)
    .resize({ width, kernel: sharp.kernel.lanczos3 })
    .webp({ lossless: true, effort: 6 })
    .toFile(path.join(images, `turboj-editor-${width}.webp`));
}

// The full-size WebP must preserve every decoded pixel of the real capture.
const originalPixels = await sharp(originalBuffer).ensureAlpha().raw().toBuffer();
const webpPixels = await sharp(path.join(images, 'turboj-editor.webp')).ensureAlpha().raw().toBuffer();
if (!originalPixels.equals(webpPixels)) throw new Error('Full-size screenshot conversion altered pixels.');

const fontPackages = [
  { name: 'jetbrains-mono', file: 'jetbrains-mono-latin-wght-normal.woff2' },
  { name: 'ibm-plex-sans', file: 'ibm-plex-sans-latin-wght-normal.woff2' },
];
for (const font of fontPackages) {
  const packageRoot = path.join(root, 'node_modules/@fontsource-variable', font.name);
  await copyFile(path.join(packageRoot, 'files', font.file), path.join(fonts, `${font.name}-latin-variable.woff2`));
  await copyFile(path.join(packageRoot, 'LICENSE'), path.join(licenses, `${font.name}-OFL.txt`));
}

// This original social layout frames the real screenshot, without retouching,
// cropping, or re-creating any part of the application UI. Monospace text is
// rasterized by the build machine; it is not an additional distributed font.
const card = `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630" viewBox="0 0 1200 630">
  <rect width="1200" height="630" fill="#0000AA"/>
  <rect width="1200" height="46" fill="#AAAAAA"/>
  <g font-family="Menlo,DejaVu Sans Mono,monospace" font-weight="bold">
    <text x="32" y="31" font-size="21" fill="#0000AA">TURBOJ.EXE</text>
    <text x="1168" y="30" text-anchor="end" font-size="16" fill="#000000">MACOS / IN DEVELOPMENT</text>
    <text x="36" y="151" font-size="68" fill="#FFFFFF">TurboJ<tspan fill="#FFFF55">_</tspan></text>
    <text x="39" y="196" font-size="16" letter-spacing="1.2" fill="#56FFFF">THE ULTRA-FAST JAVA EDITOR</text>
    <text x="36" y="291" font-size="43" fill="#FFFFFF">Java,</text>
    <text x="36" y="345" font-size="43" fill="#FFFFFF">straight to</text>
    <text x="36" y="399" font-size="43" fill="#FFFFFF">the point.</text>
    <text x="39" y="483" font-size="17" fill="#FFFF55">Old-school focus.</text>
    <text x="39" y="513" font-size="17" fill="#FFFF55">Modern Java.</text>
  </g>
  <rect x="451" y="89" width="716" height="502" fill="none" stroke="#56FFFF" stroke-width="2"/>
  <rect x="0" y="602" width="1200" height="28" fill="#05AAAA"/>
  <text x="32" y="622" font-family="Menlo,DejaVu Sans Mono,monospace" font-size="15" font-weight="bold" fill="#000000">EDIT. COMPILE. RUN. REPEAT.</text>
</svg>`;
const screenshot = await sharp(originalBuffer).resize({ width: 712 }).png().toBuffer();
await sharp(Buffer.from(card))
  .composite([{ input: screenshot, left: 453, top: 91 }])
  .png({ compressionLevel: 9 })
  .toFile(path.join(images, 'turboj-social.png'));

const assetFiles = ['turboj-editor.png', 'turboj-editor.webp', 'turboj-editor-530.webp', 'turboj-editor-795.webp', 'turboj-social.png'];
const generated = await Promise.all(assetFiles.map(async (name) => {
  const file = path.join(images, name);
  const [buffer, info, details] = await Promise.all([readFile(file), sharp(file).metadata(), stat(file)]);
  return { file: `/images/${name}`, width: info.width, height: info.height, bytes: details.size, sha256: sha256(buffer) };
}));

await writeFile(path.join(root, 'assets/original/provenance.json'), JSON.stringify({
  original: 'turboj-editor.png',
  source: '../../../docs/images/turboj-classic.png',
  sha256: originalHash,
  width: metadata.width,
  height: metadata.height,
  capture: {
    program: 'Hello.java',
    output: 'Hello TurboJ!',
    cacheHitMilliseconds: 0.03,
    executionMilliseconds: 1.17,
    roundTripMilliseconds: 2.22,
    note: 'One captured prototype run. These are not benchmark averages or fresh compilation timings.',
  },
  transformations: 'Original PNG copied byte-for-byte; full-size WebP pixel-verified lossless; smaller WebP images resized only; social preview includes the complete scaled screenshot.',
  generated,
}, null, 2) + '\n');

for (const asset of generated) {
  console.log(`${asset.file}: ${asset.width}×${asset.height}, ${asset.bytes.toLocaleString('en-US')} bytes`);
}
console.log('Original hash and full-size WebP pixel equality verified. Local fonts and OFL licenses copied.');
