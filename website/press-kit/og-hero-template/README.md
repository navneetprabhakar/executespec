# OG hero card — source template

Renders `/images/og-hero.png` (1200×630) — the social-share preview card that Twitter, LinkedIn, Slack, Discord, FB, Product Hunt, iMessage, WhatsApp all pull when anyone shares an executespec.ai link.

## Output

- **Dimensions**: 1200×630 (universal OG/Twitter Card spec)
- **Format**: PNG, 256-color quantized (~92 KB)
- **Meta references**: 9 places in `website/index.html` (`og:image`, `twitter:image`, JSON-LD)

## Rebuild

```bash
# 1. Copy the latest product screenshot to use as the right-side visual
cp ../ss-05b-validation-polished.png screenshot.png

# 2. Render via Playwright (re-uses /tmp/es-capture/node_modules)
CHROMIUM_PATH="$HOME/Library/Caches/ms-playwright/chromium-1217/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" \
NODE_PATH=/tmp/es-capture/node_modules \
node render.js

# 3. Quantize to 256 colors to shrink 241 KB → 92 KB (lossless-looking for flat UI)
python3 -c "from PIL import Image; img = Image.open('og-hero.png').convert('RGB'); img.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG).save('og-hero.png', format='PNG', optimize=True)"

# 4. Ship
cp og-hero.png ../../images/og-hero.png
cp og-hero.png ~/Desktop/site/images/og-hero.png   # (if keeping Desktop mirror in sync)
cd ../.. && npx wrangler deploy --config wrangler.toml
```

## Design notes

- Left panel (540px): brand lockup → Beta chip → headline with italic "verified" in orange→yellow gradient → subcopy → pricing pill + URL stacked
- Right panel (660px): browser-chrome framed product screenshot cropped tight on the Verified-80 score ring + pipeline stages + Validation Report header. Subtle blue-cyan glow behind.
- Fonts: DM Sans (body), Instrument Serif italic (accent), JetBrains Mono (chrome/URL)
- Colors: match landing `index.html` CSS custom properties (`--bg #0B0D10`, `--blue #5B8DEF`, etc.)

## When to regenerate

- Major visual rebrand (colors, typography, logomark)
- Headline copy change in landing hero that should reflect on social shares
- New product screenshot worth featuring (e.g., post-P0-24 real tour panels)
