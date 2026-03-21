/**
 * 分析 Excel(.xlsx/.xls) 工具
 * 用法：node analyze-xlsx.js <文件路径> [Sheet名或序号] [关键词]
 *
 * 功能：
 *   1. 列出所有 Sheet 名
 *   2. 输出指定 Sheet（默认第1个）全部单元格内容（行×列）
 *   3. 若提供关键词，过滤只显示含关键词的行
 *   4. 统计所有 {{...}} 占位符
 */
const XLSX = require('xlsx');
const fs   = require('fs');
const path = require('path');

const filePath  = process.argv[2];
const sheetArg  = process.argv[3] || '0';   // Sheet名 or 数字索引
const keyword   = process.argv[4] || '';

if (!filePath) {
    console.error('用法: node analyze-xlsx.js <文件路径> [Sheet名或序号] [关键词]');
    process.exit(1);
}

const absPath = path.resolve(filePath);
if (!fs.existsSync(absPath)) {
    console.error('文件不存在:', absPath);
    process.exit(1);
}

const workbook = XLSX.readFile(absPath, { cellText: true, cellDates: false });
const sheetNames = workbook.SheetNames;

console.log('═'.repeat(70));
console.log('文件:', absPath);
console.log('所有Sheet:', sheetNames.join(' | '));
console.log('═'.repeat(70));

// 选定 Sheet
let sheetName;
if (/^\d+$/.test(sheetArg)) {
    sheetName = sheetNames[parseInt(sheetArg, 10)] || sheetNames[0];
} else {
    sheetName = sheetArg;
}
if (!workbook.Sheets[sheetName]) {
    console.error('Sheet 不存在:', sheetName);
    process.exit(1);
}
const sheet = workbook.Sheets[sheetName];

// 转为二维数组
const rows = XLSX.utils.sheet_to_json(sheet, { header: 1, defval: '' });

// ── 占位符统计 ──────────────────────────────────────────
const phCount = {};
const placeholderRe = /\{\{([^}]+)\}\}/g;
rows.forEach(row => {
    row.forEach(cell => {
        const cellStr = String(cell);
        let m;
        placeholderRe.lastIndex = 0;
        while ((m = placeholderRe.exec(cellStr)) !== null) {
            const ph = `{{${m[1]}}}`;
            phCount[ph] = (phCount[ph] || 0) + 1;
        }
    });
});

console.log(`\n【Sheet: ${sheetName}】共 ${rows.length} 行\n`);

const phKeys = Object.keys(phCount);
if (phKeys.length > 0) {
    console.log(`【占位符汇总】共 ${phKeys.length} 种\n`);
    phKeys.sort().forEach(ph => {
        console.log(`  ${String(phCount[ph]).padStart(3)}次  ${ph}`);
    });
    console.log();
}

// ── 行内容输出 ────────────────────────────────────────
console.log('─'.repeat(70));
rows.forEach((row, i) => {
    const rowStr = row.map((c, j) => {
        const addr = XLSX.utils.encode_cell({ r: i, c: j });
        return c !== '' ? `${addr}:${c}` : null;
    }).filter(Boolean).join('  ');
    if (!rowStr) return;
    if (keyword && !rowStr.includes(keyword)) return;
    console.log(`[行${String(i + 1).padStart(4)}] ${rowStr}`);
});
