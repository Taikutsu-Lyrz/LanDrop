# Localization coverage audit

The existing catalog uses English phrases as keys and separates shared, Android, and desktop strings. The source catalog initially contained 119 shared, 341 Android, and 282 desktop entries. Generated assets and build outputs were excluded from the extraction.

## Source extraction

The audit scanned Kotlin sources under `android/app/src/main/java`, Svelte views/components and TypeScript files under `src`. It compared string literals and visible markup text with the existing `locales/prs-AF.json` phrase keys. The reviewed missing phrases are in `docs/localization/missing-keys.json`, grouped by platform, with source references in `validation/localization-missing-locations.json`. The reviewed additions and source locations are preserved under docs/localization; locales/en.json contains the combined English inventory. The additions include the new selector labels Close and Language updated.

The extraction deliberately excludes protocol identifiers, CSS classes, animation labels, filesystem paths, URLs, pure numeric/network summaries, mock-only messages, brands, and diagnostic-only logging. Examples of newly captured UI coverage include receive-folder onboarding, unavailable/read-only folder errors, direct-Wi-Fi reset guidance, transfer protocol errors surfaced to users, About links, and Stop Wi-Fi Direct.

This static extraction is a reviewed candidate inventory, not proof that every possible runtime error is translated. Errors containing provider, OS, Rust, network or device-generated text can have arbitrary contents; external message payloads should remain understandable with a localized heading and a readable original detail.

## Runtime integration requirements

- Android language loading and `localizeAppText` were hardcoded to English/Dari. Load dictionaries keyed by the selected language and preserve the saved enum names for existing installations.
- Only Dari should switch layout direction to RTL. Japanese, Spanish and Turkish use LTR.
- QR scanner prompts must use the active dictionary for every non-English language, not only Dari.
- The two-option segmented language selector does not fit five choices. Use a Material selection dialog/list with native language names and immediate application.
- Android visible `Text` already routes through a localization wrapper, but accessibility content descriptions also need explicit localization.
- Desktop has one import, phrase map and template list hardcoded to Dari. Switch maps with the selected locale, persist validated language IDs, and update HTML lang/direction accordingly.
- Desktop template substitution initially replaced captures by target order. Match placeholder names so Japanese or Turkish translations can reorder interpolated values safely.
- Keep user paths, device names, invitations, file names and other user data unchanged.

## Checks for the integrated change

Verify catalog section/key parity and matching interpolation token sets, desktop type/build checks, Android compilation, and saved English/Dari compatibility. Exercise English → Dari → Japanese → Spanish → Turkish switching without restart, including an already open Settings page, selector cancellation, page navigation, empty states, transfer acceptance/failure states and accessibility labels. Japanese and Turkish expansion should not force a horizontal five-button selector.

## Implemented integration

`locales/en.json` is the reviewed English key inventory (shared, Android and desktop sections), mirrored in Android raw resources. Japanese, Spanish, Turkish and Portuguese catalogs cover this inventory, including the added live Settings summaries. Both platforms offer all six languages by their native names; existing English/Dari selections remain compatible. Only Dari is RTL. Android accessibility labels and QR prompts use the selected dictionary, and desktop interpolation matches named tokens rather than target order. The English-specific nearby-device plural suffix was replaced with the existing localized Nearby devices label plus the count.

Run `npm run check:locales` for catalog coverage, Android mirror parity and interpolation tokens. Run `npm run test:i18n` for actual desktop runtime switching, persistence, whitespace/fallback behavior and real template interpolation. Android `AppLocalizationTest` covers named-token reordering, complex expressions and saved language identities.

User-provided names, file paths, addresses, passwords and unknown external error details stay unchanged. Static catalog coverage does not imply that arbitrary provider/OS messages have translations. Live UI verification on a phone remains separate from compilation and unit checks.
