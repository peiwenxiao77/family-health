/* 家庭健康月报生成 + 备份 + Server酱微信推送
 * 每月 1 日由 GitHub Actions 调用。产出：
 *  1. 上月数据统计摘要（markdown，推送到微信）
 *  2. 完整月报（报告正文 + 当月原始数据合并为一个 JSON 文档）存入 Supabase backups 表
 *  3. 微信推送用 secret SERVERCHAN_KEY（未配置时只存不推）
 */
const BASE = 'https://dlnqhosjodkgyuojbyxo.supabase.co/rest/v1';
const KEY = 'sb_publishable_WJ4NSoqO2LTfq6WUo728DQ_0eMnvABo';

async function rest(path, opts = {}) {
  const resp = await fetch(BASE + path, {
    ...opts,
    headers: {
      'apikey': KEY, 'Authorization': 'Bearer ' + KEY,
      'Content-Type': 'application/json', ...(opts.headers || {}),
    },
  });
  if (!resp.ok) throw new Error(`REST ${path}: HTTP ${resp.status} ${await resp.text()}`);
  const txt = await resp.text();
  return txt ? JSON.parse(txt) : null;
}

const DAY = 86400000;
const dayKey = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

(async () => {
  const now = new Date();
  // 报告期 = 上个自然月
  const end = new Date(now.getFullYear(), now.getMonth(), 1);
  const start = new Date(end.getFullYear(), end.getMonth() - 1, 1);
  const period = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}`;
  const startISO = start.toISOString();
  const endISO = end.toISOString();
  // 预测需要更早的月经数据 → 多拉 120 天身体事件
  const longISO = new Date(start.getTime() - 120 * DAY).toISOString();

  const [hr, beAll] = await Promise.all([
    rest(`/health_records?select=*&ts=gte.${startISO}&ts=lt.${endISO}&limit=5000`),
    rest(`/body_events?select=*&ts=gte.${longISO}&ts=lt.${endISO}&order=ts.asc&limit=5000`),
  ]);
  const be = beAll.filter(e => e.ts >= start.getTime());
  const allMembers = [...new Set([...hr.map(r => r.member).filter(Boolean), ...be.map(e => e.member).filter(Boolean)])].sort();

  // —— 统计 ——
  const catLabel = c => ({ menstrual: '🌷 月经', gout: '🦴 痛风', anomaly: '😖 异常', intercourse: '❤️ 同房' }[c] || (c && c.startsWith && c.startsWith('cust_') ? '📝 ' + (be.find(e => e.category === c)?.payload?.custom_name || '自定义') : c));
  const memberLines = allMembers.map(m => {
    const mBe = be.filter(e => e.member === m);
    const counts = {};
    mBe.forEach(e => { const k = catLabel(e.category) || e.category; counts[k] = (counts[k] || 0) + 1; });
    const mHr = hr.filter(r => r.member === m);
    const hrCounts = {};
    mHr.forEach(r => { hrCounts[r.category?.name || '?'] = (hrCounts[r.category?.name || '?'] || 0) + 1; });
    const hrStr = Object.entries(hrCounts).map(([k, v]) => `${k} ${v} 次`).join('、') || '无';
    const beStr = Object.entries(counts).map(([k, v]) => `${k} ${v} 次`).join('、') || '无';
    return `**${m}**：身体事件 ${beStr}；健康测量 ${hrStr}`;
  });

  // 月经预测（复用网页版同款逻辑：近几个有效周期加权平均）
  const predictions = allMembers.map(m => {
    const starts = beAll.filter(e => e.category === 'menstrual' && e.member === m && (e.payload?.is_period_start !== false)).sort((a, b) => a.ts - b.ts);
    if (starts.length < 2) return null;
    const gaps = [];
    for (let i = 1; i < starts.length; i++) {
      const d = Math.round((starts[i].ts - starts[i - 1].ts) / DAY);
      if (d >= 15 && d <= 45) gaps.push(d);
    }
    if (!gaps.length) return null;
    const recent = gaps.slice(-6);
    let avg = recent.length >= 3
      ? recent.reduce((s, v, i) => s + v * (i + 1 + (6 - recent.length)), 0) / recent.reduce((s, _, i) => s + (i + 1 + (6 - recent.length)), 0)
      : recent.reduce((s, v) => s + v, 0) / recent.length;
    const next = new Date(starts[starts.length - 1].ts + avg * DAY);
    return `**${m}**：预测下次月经 ${dayKey(next)}（平均周期 ${Math.round(avg)} 天）`;
  }).filter(Boolean);

  // —— 摘要 markdown（微信推送正文） ——
  const summaryMd = [
    `# 🏠 家庭健康月报（${period}）`,
    '',
    `📊 本月健康测量 **${hr.length}** 条 · 身体事件 **${be.length}** 条`,
    '',
    '## 成员统计',
    ...(memberLines.length ? memberLines : ['（本月无记录）']),
    '',
    '## 🌷 下次月经预测',
    ...(predictions.length ? predictions : ['（月经记录不足，暂无法预测）']),
    '',
    `> 完整月报（含当月全部数据）已存入云端备份，可随时导出查看`,
    `> 生成时间：${now.toISOString().slice(0, 16).replace('T', ' ')} UTC`,
  ].join('\n');

  // —— 完整月报文档（报告 + 数据合并为一个 JSON 文件） ——
  const reportDoc = {
    doc: 'family-health-monthly-report',
    version: 1,
    period,
    generated_at: now.toISOString(),
    summary_md: summaryMd,
    stats: { health_records: hr.length, body_events: be.length, members: allMembers },
    predictions,
    data: { health_records: hr, body_events: be },  // 与报告合并存档
  };

  await rest('/backups', {
    method: 'POST',
    body: JSON.stringify({ payload: { kind: 'monthly_report', period, doc: reportDoc } }),
  });
  console.log(`✓ 月报已存入 backups（${period}）`);

  // —— 微信推送（Server酱；secret 未配置则跳过） ——
  const sendKey = process.env.SERVERCHAN_KEY;
  if (!sendKey) {
    console.log('⚠ SERVERCHAN_KEY 未配置，跳过微信推送（GitHub → Settings → Secrets and variables → Actions 添加后自动生效）');
    return;
  }
  const body = new URLSearchParams({ title: `家庭健康月报 ${period}`, desp: summaryMd });
  const sr = await fetch(`https://sctapi.ftqq.com/${sendKey}.send`, { method: 'POST', body });
  const rj = await sr.json().catch(() => ({}));
  if (rj && rj.code === 0) console.log('✓ 微信推送成功');
  else { console.error('推送失败:', JSON.stringify(rj)); process.exit(1); }
})().catch(e => { console.error('✗', e.message); process.exit(1); });
