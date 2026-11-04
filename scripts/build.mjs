// Bundles the two frontend entry points into src/main/webapp/generated/.
// - bpe-enhancer.js: vanilla progressive enhancement for the Build with Parameters page.
//   Targets ES2017 so that HtmlUnit (used by JenkinsRule tests) can execute it.
// - bpe-designer.js: React app for the Build Form Designer.
import * as esbuild from "esbuild";

const watch = process.argv.includes("--watch");
const outdir = "src/main/webapp/generated";

const common = {
  bundle: true,
  format: "iife",
  minify: !watch,
  sourcemap: watch ? "inline" : false,
  legalComments: "none",
  logLevel: "info",
  define: { "process.env.NODE_ENV": watch ? '"development"' : '"production"' },
};

const builds = [
  { ...common, entryPoints: { "bpe-enhancer": "src/main/frontend/enhancer/index.ts" }, outdir, target: "es2017" },
  { ...common, entryPoints: { "bpe-designer": "src/main/frontend/designer/main.tsx" }, outdir, target: "es2019" },
];

if (watch) {
  for (const b of builds) {
    const ctx = await esbuild.context(b);
    await ctx.watch();
  }
} else {
  await Promise.all(builds.map((b) => esbuild.build(b)));
}
