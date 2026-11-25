import { exportMarkdown } from "../src/core/archive.js";
import { prepareCitationMarkdown } from "../src/core/citation-markdown.js";
import { buildContext } from "../src/core/context.js";
import { canMoveFolder, topLevelMoveItems, type LibraryItemRef } from "../src/core/library.js";
import { planNoteExport } from "../src/core/note-export.js";
import { sourceForAnchor } from "../src/core/source-location.js";
import { sourceEvidencePreview } from "../src/core/source-preview.js";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import type { Anchor, PageText, Project, Source } from "../src/types.js";
import type { WorkspaceFolder } from "../src/core/storage.js";

// Android supplies saved JSON metadata. Rendering and PDF/image I/O stay native.
export function context(input: string): string {
  return JSON.stringify(buildContext(JSON.parse(input)));
}

export function citations(input: string): string {
  const { raw, sources } = JSON.parse(input) as { raw: string; sources: Source[] };
  const prepared = prepareCitationMarkdown(raw, sources);
  return JSON.stringify({ content: prepared.content, nativeContent: nativeMath(prepared.content), ids: [...prepared.citedIds] });
}

interface MathNode {
  type: string;
  value?: string;
  children?: MathNode[];
  position?: { start: { offset?: number }; end: { offset?: number } };
}
const nativeParser = unified().use(remarkParse).use(remarkGfm).use(remarkMath);

function nativeMath(markdown: string): string {
  const edits: Array<{ start: number; end: number; text: string }> = [];
  function visit(node: MathNode) {
    const start = node.position?.start.offset, end = node.position?.end.offset;
    if ((node.type === "inlineMath" || node.type === "math") && start !== undefined && end !== undefined) {
      const latex = node.value ?? "";
      edits.unshift({ start, end, text: node.type === "inlineMath" ? "$$" + latex + "$$" : "$$\n" + latex + "\n$$" });
    } else node.children?.forEach(visit);
  }
  visit(nativeParser.parse(markdown) as MathNode);
  for (const edit of edits) markdown = markdown.slice(0, edit.start) + edit.text + markdown.slice(edit.end);
  return markdown;
}

export function preview(input: string): string {
  const { source, page, anchor, claim } = JSON.parse(input) as {
    source: Source; page?: PageText; anchor?: Anchor; claim?: string;
  };
  return JSON.stringify(sourceEvidencePreview(anchor ? sourceForAnchor(source, anchor) : source, page, claim));
}

export function notes(input: string): string {
  const plan = planNoteExport(JSON.parse(input) as Project);
  return JSON.stringify({
    root: plan.root,
    files: plan.files,
    assets: plan.assets.map(({ asset, path }) => ({ id: asset.id, path })),
  });
}

export function markdown(input: string): string {
  return exportMarkdown(JSON.parse(input) as Project);
}

export function moveLibrary(input: string): string {
  const { items, folders, membership, target } = JSON.parse(input) as {
    items: LibraryItemRef[]; folders: WorkspaceFolder[]; membership: Record<string, string>; target?: string;
  };
  const top = topLevelMoveItems(items, folders, membership);
  if ((target && !folders.some(folder => folder.id === target))
      || top.some(item => item.kind === "folder" && !canMoveFolder(folders, item.id, target))) {
    throw new Error("不能移入自身、子文件夹或不存在的目录。");
  }
  return JSON.stringify(top);
}
