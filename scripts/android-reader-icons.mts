import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import {
  ArrowDownToLine, BookOpen, ChevronLeft, ChevronRight, CircleHelp, Ellipsis, FileText,
  FolderOpen, GitBranch, Grid2X2, Highlighter, List, Maximize2, Minus, Move, PanelLeft, Plus, RotateCw, X,
} from "lucide-react";

const icons = {
  arrow_down_to_line: ArrowDownToLine, book_open: BookOpen, chevron_left: ChevronLeft,
  chevron_right: ChevronRight, circle_help: CircleHelp, ellipsis: Ellipsis, file_text: FileText,
  folder_open: FolderOpen, git_branch: GitBranch,
  grid: Grid2X2, highlighter: Highlighter, list: List, maximize: Maximize2, minus: Minus, move: Move,
  panel_left: PanelLeft, plus: Plus, rotate_cw: RotateCw, close: X,
};

const directory = resolve(process.argv[2], "drawable");
await mkdir(directory, { recursive: true });
await Promise.all(Object.entries(icons).map(async ([name, Component]) => {
  const svg = renderToStaticMarkup(createElement(Component));
  const paths = [...svg.matchAll(/<(path|line|polyline|polygon|circle|rect)\b([^>]+)>/g)].map(([, tag, input]) => {
    const attrs = Object.fromEntries([...input.matchAll(/([\w-]+)="([^"]+)"/g)].map(([, key, value]) => [key, value]));
    const number = (key: string) => Number(attrs[key] ?? 0);
    if (tag === "path") return attrs.d;
    if (tag === "line") return `M${attrs.x1} ${attrs.y1} L${attrs.x2} ${attrs.y2}`;
    if (tag === "polyline" || tag === "polygon") return `M${attrs.points}${tag === "polygon" ? "z" : ""}`;
    if (tag === "circle") {
      const [x, y, r] = ["cx", "cy", "r"].map(number);
      return `M${x - r} ${y} a${r} ${r} 0 1 1 ${2 * r} 0 a${r} ${r} 0 1 1 ${-2 * r} 0 z`;
    }
    const [x, y, w, h, r] = ["x", "y", "width", "height", "rx"].map(number);
    if (!r) return `M${x} ${y} h${w} v${h} h${-w} z`;
    return `M${x + r} ${y} h${w - 2 * r} a${r} ${r} 0 0 1 ${r} ${r} v${h - 2 * r} a${r} ${r} 0 0 1 ${-r} ${r} h${2 * r - w} a${r} ${r} 0 0 1 ${-r} ${-r} v${2 * r - h} a${r} ${r} 0 0 1 ${r} ${-r} z`;
  });
  if (!paths.length || paths.some((path) => !path || !/^[\d\s.,+\-eAMLHVCSQTRZamlhvcsqtrz]+$/.test(path))) {
    throw new Error(`Invalid Lucide path for ${name}.`);
  }
  await writeFile(resolve(directory, `inkask_ic_${name}.xml`), `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
${paths.map((path) => `    <path android:pathData="${path}" android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />`).join("\n")}
</vector>\n`);
}));
