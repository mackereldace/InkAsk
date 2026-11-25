import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { PROVIDERS } from "../server/provider-catalog.js";
import { DEFAULT_TEMPLATES, SYSTEM_PROMPT, TASK_LABELS } from "../src/core/templates.js";

const destination = process.argv[2];
if (!destination) throw new Error("Provide the Android assets directory.");
await mkdir(destination, { recursive: true });
await writeFile(resolve(destination, "reading-config.json"), JSON.stringify({
  providers: PROVIDERS,
  templates: DEFAULT_TEMPLATES,
  systemPrompt: SYSTEM_PROMPT,
  taskLabels: TASK_LABELS,
}));
await build({
  entryPoints: [fileURLToPath(new URL("./android-reading-core.mts", import.meta.url))],
  outfile: resolve(destination, "reading-core.js"),
  bundle: true,
  format: "iife",
  globalName: "InkAskCore",
  platform: "neutral",
  mainFields: ["module", "main"],
  target: "es2022",
});
