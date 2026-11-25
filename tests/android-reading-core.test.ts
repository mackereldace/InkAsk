import { readFile, mkdtemp, rm } from "node:fs/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { runInNewContext } from "node:vm";
import { expect, it } from "vitest";
import { buildContext } from "../src/core/context";
import { createBranch } from "../src/core/branches";
import { planNoteExport } from "../src/core/note-export";
import { DEFAULT_TEMPLATES } from "../src/core/templates";
import type { Note, Project } from "../src/types";

it("runs the Android asset bundle on JSON metadata without browser APIs", async () => {
  const directory = await mkdtemp(join(tmpdir(), "inkask-core-"));
  try {
    await promisify(execFile)(process.execPath, ["--import", "tsx", "scripts/android-reading-config.mts", directory]);
    const script = await readFile(join(directory, "reading-core.js"), "utf8");
    const core = runInNewContext(`${script}\nInkAskCore`) as Record<string, (json: string) => string>;
    const template = DEFAULT_TEMPLATES[0];
    const note: Note = {
      id: "n", title: "📖 一致有界", anchor: { page: 1, quote: "一致有界", rects: [[10, 10, 90, 25]] },
      inheritedHistory: [], collapsed: false, task: template.task, templateId: template.id,
      draft: "这条条件在证明中如何使用？", draftAssetIds: [], messages: [], createdAt: 1,
    };
    const project: Project = {
      version: 1, document: { id: "doc", name: "一致性.pdf", fingerprint: "f", pdf: new Blob(),
        pageCount: 1, createdAt: 1, updatedAt: 1 },
      pages: [{ page: 1, text: "一致有界", width: 120, height: 200, source: "native", status: "ready",
        words: [{ text: "一致有界", rect: [10, 10, 90, 25] }] }],
      notes: [note], assets: [], templates: [template], view: { page: 1, scale: 1, rotation: 0 },
    };
    const context = { project, note, question: note.draft, template };
    expect(JSON.parse(core.context(JSON.stringify(context)))).toEqual(buildContext(context));
    note.inheritedHistory = [{ role: "assistant", content: "祖先回答" }];
    note.messages = [
      { id: "q1", role: "user", content: "原问题", assetIds: ["image"], status: "complete", createdAt: 2 },
      { id: "a1", role: "assistant", content: "目标回答", assetIds: [], status: "complete", createdAt: 3 },
      { id: "q2", role: "user", content: "后来问的", assetIds: [], status: "complete", createdAt: 4 },
      { id: "a2", role: "assistant", content: "不应继承", assetIds: [], status: "complete", createdAt: 5 },
    ];
    const anchor = { start: 0, end: 4, quote: "目标回答" };
    const args = { project, parentId: note.id, messageId: "a1", anchor, template, id: "native-branch", createdAt: 6 };
    const child = JSON.parse(core.branch(JSON.stringify(args))) as Note;
    expect(child).toEqual({ ...createBranch(project, note, "a1", anchor.quote, template.task, anchor), id: args.id, createdAt: 6 });
    expect(child.inheritedHistory).toEqual([
      { role: "assistant", content: "祖先回答" },
      { role: "user", content: "原问题", assetIds: ["image"] },
      { role: "assistant", content: "目标回答" },
    ]);
    expect(JSON.parse(core.branch(JSON.stringify({ ...args, id: "sibling" }))).id).toBe("sibling");
    note.messages = [];
    note.inheritedHistory = [];
    const raw = "实际引用 [S1] [S1]，代码 `$[S2]$`，公式 $[S2]$。\n\n$$\n\\frac{a}{b}\n$$";
    const sources = [{ id: "S1", page: 1, text: "一致有界" }, { id: "S2", page: 1, text: "代码" }];
    const citations = JSON.parse(core.citations(JSON.stringify({ raw, sources })));
    expect(citations.ids).toEqual(["S1"]);
    expect(citations.nativeContent).toContain("代码 `$[S2]$`，公式 $$[S2]$$。");
    expect(citations.nativeContent).toContain("$$\n\\frac{a}{b}\n$$");
    const preview = JSON.parse(core.preview(JSON.stringify({ source: sources[0], page: project.pages[0], anchor: note.anchor })));
    expect(preview.parts[0].anchor.rects).toEqual(note.anchor.rects);
    const exported = JSON.parse(core.notes(JSON.stringify(project)));
    const time = Date.parse(exported.files[0].text.match(/导出于 ([^；]+)/u)[1]);
    expect(exported.files).toEqual(planNoteExport(project, { now: time }).files);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
