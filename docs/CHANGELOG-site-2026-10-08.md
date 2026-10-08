# Talaria site redesign (2026-10-08)

## New
- Hero: fanned three-phone stage (Chat, Control center, Sessions) with floating status chips, plus a "New" pill linking to the release notes.
- Scrolling feature ticker under the hero.
- Screens: an interactive tabbed gallery replaces the sideways scroll. It has captions, auto-advance (pauses when you tap), arrow keys, swipe, prev/next buttons, and a tap-to-enlarge lightbox.
- Features: a bento grid with an animated mock chat (tool calls, approval card, streaming reply) and a live subagents tile. Adds the 1.14.12 features: subagents, todos and goals, branch, undo and checkpoints, and multi-server.
- "Get started": three-step install guide.
- "What's new": pulls the latest releases live from the GitHub API, with the changelog rendered on the page. It falls back to a static copy if the API is unreachable.
- Themes: the six presets now switch the whole page live, are remembered in localStorage, and update the browser's theme-color.
- FAQ: six questions (requirements, official status, privacy, Play Store, multi-server, bugs).
- Download buttons link straight to the latest APK asset, and the version, date and size update themselves from the latest release. No more hand edits on each release.

## Improved
- Mobile: a hamburger menu and drawer, a chip-style gallery tab bar, and no horizontal overflow at 390 px.
- Accessibility: a skip link, visible focus rings, ARIA tabs, labelled buttons, and support for reduced-motion.
- SEO: a canonical URL, Twitter card tags, SoftwareApplication JSON-LD, and font preloads.
- Nav: highlights the active section and gains a border on scroll.
- Screenshot images keep their aspect ratio, with no layout shift.

## Unchanged
- Fonts (Inter and Fraunces), screenshots, the colour palette, and every link to the repo, releases, changelog and license.
