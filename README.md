# Shopify Functions Query Cost

An IntelliJ‑platform plugin that shows Shopify Function **input GraphQL query cost** in the status bar, using Shopify’s
input query limits. The widget activates for GraphQL files referenced by `input_query` in `shopify.extension.toml`, and
color‑codes the cost:

- Green: OK
- Amber: warning (cost 25–30 or list literal > 100)
- Red: over limit (cost > 30) or parse error

## Features

- Status bar widget showing `Shopify Query Cost: X/30`
- Warnings in tooltip for high cost or oversized list literals
- Automatic discovery of `input_query` files via `shopify.extension.toml`
- Debounced live updates as you edit

## Development

Run the plugin in a sandboxed IDE:

```
./gradlew runIde
```

Run tests:

```
./gradlew test
```

## Compatibility

Built against IntelliJ Platform 2024.2 (build `242`). Should work across JetBrains IDEs based on that platform line
(IntelliJ IDEA, WebStorm, PyCharm, PhpStorm, etc.).
