// rollup.config.js
//
// Standalone build for the Commerce Webtop application.
//
// The Commerce app source (src/webtop/apps/commerce) is deliberately
// self-contained: its only build-time dependency is the published
// @mintjamsinc/ichigojs runtime. It carries no webtop-internal imports
// (`type AnyInstance = any` instead of importing ApplicationInstance), so it
// builds here independently of the cms0 webtop project. Everything the shell
// provides (instance.api.*, window.appLaunch, themes) is injected at runtime.
//
// Build output mirrors the cms0 layout — dist/webtop/apps/commerce/ — so the
// app.js / index.html / assets / app.yml can be dropped straight into a
// deployed webtop's apps directory. The shared webtop CSS and Bootstrap Icons
// that index.html references via ../../assets/... belong to the webtop core at
// the deploy target and are not part of this build.
import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from 'rollup-plugin-typescript2';
import terser from '@rollup/plugin-terser';
import copy from 'rollup-plugin-copy';
import { transformSync } from 'esbuild';

// Build mode is selected via the BUILD env var.
//   BUILD=development (default) -> unminified output, inline sourcemaps
//   BUILD=production            -> minified JS + minified CSS, external sourcemaps
// Unrecognized values fall back to development with a warning so a typo
// never silently ships a development bundle as production.
const rawMode = process.env.BUILD;
if (rawMode && rawMode !== 'development' && rawMode !== 'production') {
  console.warn(`[rollup] Unknown BUILD=${rawMode}; falling back to "development".`);
}
const isProduction = rawMode === 'production';

// Minify CSS via esbuild. Used by production builds to overwrite the
// unminified CSS that the asset copy plugin places in dist/.
function minifyCss(contents) {
  return transformSync(contents.toString(), { loader: 'css', minify: true }).code;
}

// Cache-busting version stamp. A single token is computed once per rollup
// invocation and substituted into both the copied index.html (via the copy
// plugin's transform hook) and the emitted JS chunk (via renderChunk below).
// Filenames stay constant so rebuilds never leave orphan files behind;
// index.html appends "?v=<BUILD_VERSION>" to asset URLs so browsers refetch
// after each build.
const BUILD_VERSION = Date.now().toString(36);
console.log(`[rollup] BUILD_VERSION=${BUILD_VERSION}`);

// Replace __BUILD_VERSION__ tokens in copied text assets (HTML).
function stampVersion(contents) {
  return contents.toString().replaceAll('__BUILD_VERSION__', BUILD_VERSION);
}

// Rollup plugin: replace __BUILD_VERSION__ in the emitted JS chunk. Runs in
// renderChunk so it executes after terser; terser preserves string literals so
// substituting here keeps the minified output valid.
function versionStampPlugin() {
  return {
    name: 'build-version-stamp',
    renderChunk(code) {
      if (!code.includes('__BUILD_VERSION__')) return null;
      return { code: code.replaceAll('__BUILD_VERSION__', BUILD_VERSION), map: null };
    },
  };
}

// Build one Commerce Webtop app: src/webtop/apps/<name> -> dist/webtop/apps/<name>,
// with index.html (version stamped), assets/ (css + icons) and app.yml copied,
// and CSS under assets/css/ minified in production.
function makeApp(name) {
  const plugins = [
    resolve({ moduleDirectories: ['node_modules'] }),
    commonjs(),
    typescript({ tsconfig: './tsconfig.json', useTsconfigDeclarationDir: false, clean: true }),
  ];

  if (isProduction) {
    plugins.push(terser());
  }

  // Must run after terser so the literal __BUILD_VERSION__ in the emitted bundle
  // is replaced with the build version stamp.
  plugins.push(versionStampPlugin());

  // Copy static assets so the build is self-contained: index.html (version
  // stamped), the assets/ directory (css + icons), and app.yml.
  plugins.push(copy({
    targets: [
      { src: `src/webtop/apps/${name}/index.html`, dest: `dist/webtop/apps/${name}`, transform: stampVersion },
      { src: `src/webtop/apps/${name}/assets`, dest: `dist/webtop/apps/${name}` },
      { src: `src/webtop/apps/${name}/app.yml`, dest: `dist/webtop/apps/${name}` },
    ],
    hook: 'writeBundle',
  }));

  // In production, overwrite the just-copied CSS with minified content at the
  // same paths. Must run on closeBundle (not writeBundle): rollup executes
  // writeBundle hooks in parallel, so a recursive asset directory copy can
  // otherwise finish after — and silently clobber — the minified CSS.
  // closeBundle is guaranteed to run after all writeBundle hooks complete.
  if (isProduction) {
    plugins.push(copy({
      targets: [
        {
          src: `src/webtop/apps/${name}/assets/css/*.css`,
          dest: `dist/webtop/apps/${name}/assets/css`,
          transform: minifyCss,
        },
      ],
      hook: 'closeBundle',
    }));
  }

  return {
    input: `src/webtop/apps/${name}/app.ts`,
    output: {
      file: `dist/webtop/apps/${name}/app.js`,
      format: 'esm',
      sourcemap: isProduction ? true : 'inline',
      ...(isProduction ? { sourcemapExcludeSources: false } : {}),
    },
    plugins,
  };
}

export default [
  makeApp('commerce'),
  makeApp('commerce-dashboard'),
  makeApp('commerce-pim'),
  makeApp('commerce-ops'),
  makeApp('commerce-publish'),
];
