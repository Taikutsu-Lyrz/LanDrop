import dariLocale from "../../locales/prs-AF.json";

export type AppLanguage = "en" | "prs-AF";

const STORAGE_KEY = "landrop.language";
const TRANSLATABLE_ATTRIBUTES = ["placeholder", "title", "aria-label", "alt"] as const;

type LocaleSection = Record<string, string>;

const phraseMap = new Map<string, string>([
  ...Object.entries(dariLocale.shared as LocaleSection),
  ...Object.entries(dariLocale.desktop as LocaleSection)
]);

type TemplateTranslation = {
  source: string;
  target: string;
  regex: RegExp;
};

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function templateRegex(source: string): RegExp {
  const placeholder = /\$\{[^}]+\}/g;
  let pattern = "^";
  let last = 0;
  for (const match of source.matchAll(placeholder)) {
    const index = match.index ?? 0;
    pattern += escapeRegex(source.slice(last, index));
    pattern += "(.*?)";
    last = index + match[0].length;
  }
  pattern += escapeRegex(source.slice(last));
  pattern += "$";
  return new RegExp(pattern, "s");
}

const templateTranslations: TemplateTranslation[] = [...phraseMap.entries()]
  .filter(([source]) => source.includes("${"))
  .map(([source, target]) => ({
    source,
    target,
    regex: templateRegex(source)
  }))
  .sort((a, b) => b.source.length - a.source.length);

let currentLanguage: AppLanguage = "en";
let observer: MutationObserver | null = null;
const originalText = new WeakMap<Text, string>();
const renderedText = new WeakMap<Text, string>();
const originalAttributes = new WeakMap<Element, Map<string, string>>();
const renderedAttributes = new WeakMap<Element, Map<string, string>>();

function substituteTemplate(target: string, captures: string[]): string {
  let index = 0;
  return target.replace(/\$\{[^}]+\}/g, () => captures[index++] ?? "");
}

function translateCore(source: string): string {
  const direct = phraseMap.get(source);
  if (direct !== undefined) {
    return direct;
  }

  for (const template of templateTranslations) {
    const match = template.regex.exec(source);
    if (!match) {
      continue;
    }
    return substituteTemplate(template.target, match.slice(1));
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

  return leading + translateCore(core) + trailing;
}

function renderTextNode(node: Text, force = false) {
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
  document.documentElement.lang = isDari ? "prs-AF" : "en";
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
  return window.localStorage.getItem(STORAGE_KEY) === "prs-AF" ? "prs-AF" : "en";
}

export function setAppLanguage(language: AppLanguage) {
  currentLanguage = language;
  if (typeof window !== "undefined") {
    window.localStorage.setItem(STORAGE_KEY, language);
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
