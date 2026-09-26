// @vitest-environment happy-dom
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import AppIcon from "./components/AppIcon.vue";
import { icons, type IconName } from "./icons";
import { uiComponents } from "./uiComponents";

const root = join(__dirname);
const sources = (function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return walk(path);
    return /\.(vue|ts)$/.test(entry.name) && !entry.name.endsWith(".test.ts")
      ? [path]
      : [];
  });
})(root);

// Arrows, dingbats, geometric shapes, technical symbols, full-width plus and emoji used as pictograms.
const pictogram =
  /[\u2190-\u21FF\u2300-\u23FF\u25A0-\u27BF\u2900-\u297F\u2B00-\u2BFF\uFF0B\u{1F000}-\u{1FAFF}]/u;
// Text notation, not icons: a keyboard shortcut and a key → value mapping label.
const allowedNotation = ["⌘ / Ctrl + Enter", "维度 ID → 精确值"];

it("uses icons only through the central registry", () => {
  for (const file of sources) {
    const name = relative(root, file);
    let text = readFileSync(file, "utf-8");
    for (const notation of allowedNotation)
      text = text.replaceAll(notation, "");
    expect(text, `${name} contains an emoji or symbol icon`).not.toMatch(
      pictogram,
    );
    expect(text, `${name} contains hand-written SVG`).not.toMatch(/<svg[\s>]/);
    if (name !== "icons.ts")
      expect(text, `${name} imports icons directly`).not.toContain(
        "@element-plus/icons-vue",
      );
  }
});

it("renders every registered icon as an Element Plus icon", () => {
  for (const name of Object.keys(icons) as IconName[]) {
    const wrapper = mount(AppIcon, {
      props: { name },
      global: { plugins: [uiComponents] },
    });
    expect(wrapper.find("i.el-icon svg").exists(), name).toBe(true);
    wrapper.unmount();
  }
});

it("spins only when asked", () => {
  const still = mount(AppIcon, {
    props: { name: "loading" },
    global: { plugins: [uiComponents] },
  });
  const spinning = mount(AppIcon, {
    props: { name: "loading", spin: true },
    global: { plugins: [uiComponents] },
  });
  expect(still.classes()).not.toContain("is-loading");
  expect(spinning.classes()).toContain("is-loading");
});
