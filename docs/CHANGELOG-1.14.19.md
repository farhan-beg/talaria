# Talaria 1.14.19 (versionCode 36)

## Added
- The Config editor is now sectioned: config.yaml is split at its top-level keys, each block gets its own field, and there's a search across settings, per-section expand/collapse, an "edited" mark on what you changed, per-section undo, and collapse/expand all in one tap.

## Fixed
- Editing large config.yaml files no longer crashes the Config screen: one field holding the whole file could grow taller than Compose can measure. Blocks over 300 lines are now edited in slices, and every field is height-capped and scrolls inside.
- Reloading while you have unsaved edits now asks before discarding them.
