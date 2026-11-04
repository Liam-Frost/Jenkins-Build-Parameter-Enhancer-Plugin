# Contributing

Bug reports, ideas and pull requests are welcome. Please open an issue first for larger changes, so the approach
can be agreed before you spend time on it.

## Prerequisites

- Java 21 or 25
- Maven 3.9 or newer

Node.js is downloaded by the Maven build (see `.mvn_exec_node`), so you do not need it installed. Install
Node 24 yourself only if you want to run the `npm` commands below directly.

## Build and test

```bash
mvn verify
```

This runs `npm ci`, type-checks and bundles the frontend, runs the frontend unit tests, then compiles, tests and
packages the plugin as `target/build-parameter-enhancer.hpi`. It also checks formatting with Spotless; fix any
formatting failure with:

```bash
mvn spotless:apply
```

## Run Jenkins with the plugin

```bash
mvn hpi:run
```

This starts Jenkins at <http://localhost:8080/jenkins/> with the plugin and the test dependencies
(Pipeline, Git Parameter) installed.

## Frontend

The frontend lives in `src/main/frontend` and is bundled by esbuild into `src/main/webapp/generated`, which is
not committed.

| Bundle | Source | Purpose |
| --- | --- | --- |
| `bpe-enhancer.js` | `enhancer/` | Rearranges the Build with Parameters page, presets, search |
| `bpe-designer.js` | `designer/` | The Build Form Designer (React) |

```bash
npm ci
```

```bash
npm run watch
```

`npm run watch` rebuilds on every change while `mvn hpi:run` is running; reload the page to see it.

```bash
npm run typecheck
```

```bash
npm test
```

The enhancer bundle is also run by HtmlUnit in the Java tests, and HtmlUnit's JavaScript engine supports only
part of modern JavaScript. The bundle targets ES2017, and spread syntax (`...x`) must not appear in the enhancer
sources: esbuild leaves it in place for ES2017 and HtmlUnit fails to compile it. If a page test fails with
`ScriptException` and a number as its message, look for syntax of that kind.

## Tests

- `src/test/java`: layout codec, auto layout and parameter discovery tests, plus HtmlUnit tests in
  `BuildParameterEnhancerPageTest` that load the real build page and check that a build started from the enhanced form
  receives exactly the values that were entered.
- `src/main/frontend/**/*.test.ts`: unit tests for the designer's layout and drag-and-drop logic (Vitest).

A change to how the page is rearranged should come with a test that submits the form and checks the build's
parameters.

## Pull requests

- Keep the change focused and describe how you tested it.
- UI text is English and uses Jenkins' own CSS classes, so the page follows the active Jenkins theme.
- Run `mvn verify` before pushing.

## README screenshots

`scripts/screenshots.mjs` takes the images in `docs/images` from a separate Jenkins on a demo job with neutral
parameter names, in English and Chinese. Create `work/init.groovy.d/demo-user.groovy` with a local `demo`/`demo`
account (see the comment at the top of the script), then:

```bash
mvn hpi:run -Dport=8099
```

```bash
node scripts/screenshots.mjs http://localhost:8099/jenkins
```
