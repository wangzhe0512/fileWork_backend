/**
 * 分析 Word(.docx) 子模板工具
 * 用法：node analyze-docx.js <文件路径> [关键词]
 *
 * 功能：
 *   1. 提取文档全文，逐段输出
 *   2. 若提供关键词，过滤只显示包含关键词的段落（上下各 1 段上下文）
 *   3. 统计所有 {{...}} 占位符出现次数
 */
const mammoth = require('mammoth');
const fs = require('fs');
const path = require('path');

const filePath = process.argv[2];
const keyword  = process.argv[3] || '';

if (!filePath) {
    console.error('用法: node analyze-docx.js <文件路径> [关键词]');
    process.exit(1);
}

const absPath = path.resolve(filePath);
if (!fs.existsSync(absPath)) {
    console.error('文件不存在:', absPath);
    process.exit(1);
}

(async () => {
    const result = await mammoth.extractRawText({ path: absPath });
    const raw = result.value;

    // 按段落拆分（空行分段）
    const paragraphs = raw.split(/\n+/).filter(p => p.trim().length > 0);

    // ── 占位符统计 ──────────────────────────────────────────
    const placeholderRe = /\{\{([^}]+)\}\}/g;
    const phCount = {};
    let m;
    while ((m = placeholderRe.exec(raw)) !== null) {
        const ph = `{{${m[1]}}}`;
        phCount[ph] = (phCount[ph] || 0) + 1;
    }

    console.log('═'.repeat(70));
    console.log('文件:', absPath);
    console.log('总段落数:', paragraphs.length);
    console.log('═'.repeat(70));

    // ── 占位符汇总 ────────────────────────────────────────
    const phKeys = Object.keys(phCount);
    if (phKeys.length > 0) {
        console.log(`\n【占位符汇总】共 ${phKeys.length} 种\n`);
        phKeys.sort().forEach(ph => {
            console.log(`  ${String(phCount[ph]).padStart(3)}次  ${ph}`);
        });
    } else {
        console.log('\n【占位符汇总】未找到任何 {{...}} 占位符');
    }

    // ── 段落内容输出 ──────────────────────────────────────
    console.log('\n' + '─'.repeat(70));
    if (keyword) {
        console.log(`\n【含关键词 "${keyword}" 的段落】\n`);
        paragraphs.forEach((p, i) => {
            if (p.includes(keyword)) {
                const start = Math.max(0, i - 1);
                const end   = Math.min(paragraphs.length - 1, i + 1);
                for (let j = start; j <= end; j++) {
                    const tag = j === i ? '>>>' : '   ';
                    console.log(`[${String(j + 1).padStart(4)}] ${tag} ${paragraphs[j]}`);
                }
                console.log();
            }
        });
    } else {
        console.log('\n【完整段落列表】\n');
        paragraphs.forEach((p, i) => {
            console.log(`[${String(i + 1).padStart(4)}] ${p}`);
        });
    }
})();
