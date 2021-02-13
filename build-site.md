# Build documentation site

Docs are based on:

- `docsify`, a _dynamic_, markdown-based generator.
- `mdoc`, type-checked scala/markdown compiler

The source for the docs is in `yourProject/docs`, the website in `fs2/target/website`. The currently deployed website is in the `gh-pages` branch.

You can build the site and preview it locally.

## With Nix

1. Run `nix-shell --run "sbt 'microsite/mdoc --watch'"`.
2. Run `nix-shell --run "node_modules/docsify-cli/bin/docsify serve target/website/docs/"` in a different terminal.

## Without Nix

Install `docsify`:

```
npm i docsify-cli -g
```

then, start `mdoc` in an `sbt` session:

```
sbt microsite/mdoc --watch
```

and docsify in a shell session:

```
docsify serve fs2/target/website/
```

and you'll get an updating preview.

Note that `mdoc` watches the markdown files, so if you change the code itself it will need a manual recompile.

`docsify` uses 3 special files: `index.html`, `_coverpage.md`, `_sidebar.md`, the sidebar needs to have a specific format:

- newlines in between headers
- and no extra modifiers inside links `[good]`, `[**bad**]` (or collapse will not work)
