# Architecture diagrams

PlantUML source and generated SVG output are committed together. Architecture documents
embed the SVG files so diagrams render on GitHub without browser or editor extensions, while
the `.puml` files remain the reviewable source of truth.

## Regenerate

Use PlantUML 1.2026.6 and a local Graphviz installation:

```bash
docs/diagrams/render.sh /path/to/plantuml-1.2026.6.jar
```

The script rejects other PlantUML versions to keep layout and SVG output reproducible. It runs
Java in headless mode and rewrites each SVG beside its corresponding `.puml` source.
