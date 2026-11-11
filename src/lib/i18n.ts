import dariLocale from "../../locales/prs-AF.json";
import japaneseLocale from "../../locales/ja.json";
import spanishLocale from "../../locales/es.json";
import portugueseLocale from "../../locales/pt.json";
import turkishLocale from "../../locales/tr.json";

export type AppLanguage = "en" | "prs-AF" | "ja" | "es" | "tr" | "pt";
export const supportedLanguages: {code: AppLanguage; label: string}[] = [
  {code: "en", label: "English"}, {code: "prs-AF", label: "دری"},
  {code: "ja", label: "日本語"}, {code: "es", label: "Español"}, {code: "tr", label: "Türkçe"}, {code: "pt", label: "Português"}
];

const STORAGE_KEY = "landrop.language";
const TRANSLATABLE_ATTRIBUTES = ["placeholder", "title", "aria-label", "alt"] as const;

type LocaleSection = Record<string, string>;

type TemplateTranslation = { regex: RegExp; target: string; tokens: string[] };
const placeholder = /\$\{[^}]+\}|\$[A-Za-z_]\w*/g;
function escapeRegex(value: string): string { return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }
const localeMaps = new Map<AppLanguage, {phrases: Map<string, string>; templates: TemplateTranslation[]}>();
for (const [language, locale] of [["prs-AF", dariLocale], ["ja", japaneseLocale], ["es", spanishLocale], ["tr", turkishLocale], ["pt", portugueseLocale]] as const) {
  const phrases = new Map<string, string>([...Object.entries(locale.shared as LocaleSection), ...Object.entries(locale.desktop as LocaleSection)]);
  const templates: TemplateTranslation[] = [];
  for (const [source, target] of phrases) {
    const tokens: string[] = [];
    let pattern = "^", last = 0;
    for (const match of source.matchAll(placeholder)) {
      pattern += escapeRegex(source.slice(last, match.index)) + "(.*?)";
      tokens.push(match[0]);
      last = match.index! + match[0].length;
    }
    if (tokens.length) templates.push({regex: new RegExp(pattern + escapeRegex(source.slice(last)) + "$", "s"), target, tokens});
  }
  templates.sort((left, right) => right.regex.source.length - left.regex.source.length);
  localeMaps.set(language, {phrases, templates});
}

let currentLanguage: AppLanguage = "en";
let observer: MutationObserver | null = null;
const originalText = new WeakMap<Text, string>();
const renderedText = new WeakMap<Text, string>();
const originalAttributes = new WeakMap<Element, Map<string, string>>();
const renderedAttributes = new WeakMap<Element, Map<string, string>>();

const nestedMessageTokens = new Set(["${result.message}"]);

function translateCore(source: string, language: AppLanguage, depth = 0): string {
  const locale = localeMaps.get(language);
  if (!locale) return source;
  const direct = locale.phrases.get(source);
  if (direct !== undefined) return direct;
  if (depth >= 2) return source;
  for (const template of locale.templates) {
    const match = template.regex.exec(source);
    if (match) return template.target.replace(placeholder, (token) => {
      const index = template.tokens.indexOf(token);
      if (index < 0) return token;
      const value = match[index + 1];
      return nestedMessageTokens.has(token) && value !== source ? translateCore(value, language, depth + 1) : value;
    });
  }
  return source;
}

export function translateText(value: string, language: AppLanguage = currentLanguage): string {
  if (language === "en" || value.length === 0) {
    return value;
  }

  const leading = value.match(/^\s*/)?.[0] ?? "";
  const trailing = value.match(/\s*$/)?.[0] ?? "";
  const coreEnd = value.length - trailing.length;
  const core = value.slice(leading.length, coreEnd);

  if (!core) {
    return value;
  }

  return leading + translateCore(core, language) + trailing;
}

function renderTextNode(node: Text, force = false) {
  if (node.parentElement?.closest("[data-i18n-skip], script, style")) return;
  const lastRendered = renderedText.get(node);

  if (!force && lastRendered !== undefined && node.data === lastRendered) {
    return;
  }

  if (!originalText.has(node) || (!force && lastRendered !== undefined && node.data !== lastRendered)) {
    originalText.set(node, node.data);
  }

  const source = originalText.get(node) ?? node.data;
  const next = currentLanguage === "en" ? source : translateText(source, currentLanguage);
  renderedText.set(node, next);

  if (node.data !== next) {
    node.data = next;
  }
}

function attributeMaps(element: Element) {
  let originals = originalAttributes.get(element);
  if (!originals) {
    originals = new Map<string, string>();
    originalAttributes.set(element, originals);
  }

  let rendered = renderedAttributes.get(element);
  if (!rendered) {
    rendered = new Map<string, string>();
    renderedAttributes.set(element, rendered);
  }

  return { originals, rendered };
}

function renderAttributes(element: Element, force = false) {
  const { originals, rendered } = attributeMaps(element);

  for (const attribute of TRANSLATABLE_ATTRIBUTES) {
    if (!element.hasAttribute(attribute)) {
      continue;
    }

    const value = element.getAttribute(attribute) ?? "";
    const lastRendered = rendered.get(attribute);

    if (!force && lastRendered !== undefined && value === lastRendered) {
      continue;
    }

    if (!originals.has(attribute) || (!force && lastRendered !== undefined && value !== lastRendered)) {
      originals.set(attribute, value);
    }

    const source = originals.get(attribute) ?? value;
    const next = currentLanguage === "en" ? source : translateText(source, currentLanguage);
    rendered.set(attribute, next);

    if (value !== next) {
      element.setAttribute(attribute, next);
    }
  }
}

function renderTree(root: Node, force = false) {
  if (root.nodeType === Node.TEXT_NODE) {
    renderTextNode(root as Text, force);
    return;
  }

  if (!(root instanceof Element) && root !== document.body) {
    return;
  }

  if (root instanceof Element) {
    renderAttributes(root, force);
  }

  const walker = document.createTreeWalker(
    root,
    NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT
  );

  let node = walker.nextNode();
  while (node) {
    if (node.nodeType === Node.TEXT_NODE) {
      renderTextNode(node as Text, force);
    } else if (node instanceof Element) {
      renderAttributes(node, force);
    }
    node = walker.nextNode();
  }
}

function applyDocumentMetadata() {
  const isDari = currentLanguage === "prs-AF";
  document.documentElement.lang = currentLanguage;
  document.documentElement.dir = isDari ? "rtl" : "ltr";
  document.documentElement.dataset.language = currentLanguage;
}

function renderDocument(force = false) {
  applyDocumentMetadata();
  if (document.body) {
    renderTree(document.body, force);
  }
}

export function getInitialLanguage(): AppLanguage {
  if (typeof window === "undefined") {
    return "en";
  }
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    return supportedLanguages.find((language) => language.code === saved)?.code ?? "en";
  } catch { return "en"; }
}

export function setAppLanguage(language: AppLanguage) {
  currentLanguage = language;
  if (typeof window !== "undefined") {
    try { window.localStorage.setItem(STORAGE_KEY, language); } catch { /* Session language still works. */ }
  }
  if (typeof document !== "undefined") {
    renderDocument(true);
  }
}

export function initializeI18n(language: AppLanguage = getInitialLanguage()): () => void {
  currentLanguage = language;
  renderDocument(true);

  observer?.disconnect();
  observer = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      if (mutation.type === "characterData") {
        renderTextNode(mutation.target as Text);
        continue;
      }

      if (mutation.type === "attributes" && mutation.target instanceof Element) {
        renderAttributes(mutation.target);
        continue;
      }

      for (const added of mutation.addedNodes) {
        renderTree(added);
      }
    }
  });

  observer.observe(document.body, {
    subtree: true,
    childList: true,
    characterData: true,
    attributes: true,
    attributeFilter: [...TRANSLATABLE_ATTRIBUTES]
  });

  return () => {
    observer?.disconnect();
    observer = null;
  };
}
