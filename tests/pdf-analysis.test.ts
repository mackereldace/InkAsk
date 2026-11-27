import { describe, expect, it } from "vitest";
import { buildContext } from "../src/core/context";
import { pageEvidenceBlocks } from "../src/core/evidence-blocks";
import { selectEvidence } from "../src/core/evidence-retrieval";
import { applicablePdfAnalysis, parsePdfAnalysis, pdfAnalysisPrompt } from "../src/core/pdf-analysis";
import { DEFAULT_TEMPLATES } from "../src/core/templates";
import type { Note, PageText, Project } from "../src/types";

function page(page: number, text: string): PageText {
  return { page, text, source: "native", status: text ? "ready" : "empty", width: 100, height: 120,
    words: text ? text.split("\n").map((text, i) => ({ text, rect: [5, i * 10, 80, i * 10 + 8] })) : [] };
}
const statement = "定理2.4.15 两个凸棠可以分离。";
const proof = "证：用泛函构造超平面。";
const continued = "再由连续性得到 f(y)Bf(z)。";
const pages = [
  page(150, [statement, proof].join("\n")),
  page(151, [continued, "接下来讨论其他定理。"].join("\n")),
  page(152, "原来的选区。式 (2.4.15)"),
];

const artifact = {
  pages: [
    { page: 150, corrections: [{ line: 1, original: statement, text: "定理2.4.15 两个凸集可以分离。" },
      { line: 2, original: "其他页上的文字", text: "不可接受" }], transcript: [], structures: [
      { kind: "theorem", number: "2.4.15", title: "凸集分离", firstLine: 1, lastLine: 1 },
      { kind: "proof", number: "2.4.15", firstLine: 2, lastLine: 2, continuesOnNext: true },
      { kind: "theorem", number: "2.4.16", firstLine: 2, lastLine: 3 },
    ] },
    { page: 151, corrections: [{ line: 1, original: continued, text: "再由连续性得到 f(y)≤f(z)。" }], transcript: [],
      structures: [{ kind: "proof", number: "2.4.15", firstLine: 1, lastLine: 1, continuesFromPrevious: true }] },
  ],
};
const parsed = parsePdfAnalysis(JSON.stringify(artifact), pages, [150, 151]);
const annotated = pages.map(source => ({
  ...source, analysis: parsed.find(result => result.page === source.page)?.analysis,
}));

describe("AI PDF analysis", () => {
  it("prompts for page-scoped line numbers, text corrections, and cross-page boundaries", () => {
    const prompt = pdfAnalysisPrompt(pages, [151]);
    expect(prompt).toContain('"page":150,"target":false');
    expect(prompt).toContain('"page":151,"target":true');
    expect(prompt).toContain('continuesFromPrevious');
    expect(prompt).toContain('只在原图能确认');
  });

  it("checks original lines and bounds, leaving the searchable source reversible", () => {
    expect(parsed[0].analysis.corrections).toHaveLength(1);
    expect(parsed[0].analysis.structures.map(item => item.kind)).toEqual(["theorem", "proof"]);
    expect(pages[0].text).toContain("凸棠");
    const theorem = pageEvidenceBlocks(annotated[0]).find(b => b.structure?.kind === "theorem");
    expect(theorem?.text).toContain("凸集");
    expect(theorem?.rects).toEqual([[5, 0, 80, 8]]);
    expect(applicablePdfAnalysis({ ...annotated[0], text: "原页已重新提取" })).toBeUndefined();
  });

  it("pulls a named theorem and its proof continuation before general keyword ranking", () => {
    const note = { anchor: { page: 152, quote: "原来的选区。", rects: [] } } as unknown as Note;
    const project = { pages: annotated } as Project;
    const evidence = selectEvidence(project, note, "2.4.15 是怎么证明的？");
    expect(evidence.map(block => [block.page, block.role])).toEqual([[150, "theorem"], [150, "proof"], [151, "proof"]]);
    expect(evidence[2].text).toContain("f(y)≤f(z)");
    expect(evidence[2].text).not.toContain("接下来");
    expect(selectEvidence(project, note, "式 (2.4.15) 的右边是什么？").some(block => block.structure?.kind === "theorem")).toBe(false);
  });

  it("adds image-only transcription to retrieval/coverage and rejects a missing output page", () => {
    const source = page(1, "");
    const answer = { pages: [{ page: 1, corrections: [], structures: [], transcript: ["转写出的一整行"] }] };
    const [result] = parsePdfAnalysis(JSON.stringify(answer), [source], [1]);
    const corrected = { ...source, analysis: result.analysis };
    expect(pageEvidenceBlocks(corrected).map(block => block.text)).toContain("转写出的一整行");
    const template = DEFAULT_TEMPLATES[0];
    const note = { anchor: { page: 1, quote: "", rects: [] }, inheritedHistory: [], messages: [], draftAssetIds: [] } as unknown as Note;
    const project = { pages: [corrected], document: { name: "scan.pdf", pageCount: 1 }, assets: [] } as unknown as Project;
    expect(buildContext({ project, note, template, question: "转写出的一整行" }).coverage).toContain("1/1");
    expect(() => parsePdfAnalysis('{"pages":[]}', [source], [1])).toThrow("缺少目标页面");
  });
});
