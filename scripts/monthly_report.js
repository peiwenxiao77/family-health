/* 家庭健康月报生成 + 备份 + Server酱微信推送
 * 每月 1 日由 GitHub Actions 调用。产出：
 *  1. 上月数据统计摘要 + 总结报告（markdown，推送到微信）
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
  const prevStart = new Date(end.getFullYear(), end.getMonth() - 2, 1);  // 再上个月（对比用）
  const period = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}`;
  const startISO = start.toISOString();
  const endISO = end.toISOString();
  // 预测需要更早的月经数据 → 多拉 120 天身体事件；健康测量多拉一个月（环比用）
  const hrLongISO = prevStart.toISOString();

  const [hrAll, beAll] = await Promise.all([
    // 注意：ts 列是毫秒时间戳(bigint)，过滤必须传数字，传 ISO 字符串会 400
    rest(`/health_records?select=*&ts=gte.${prevStart.getTime()}&ts=lt.${end.getTime()}&limit=5000`),
    rest(`/body_events?select=*&ts=gte.${new Date(start.getTime() - 120 * DAY).getTime()}&ts=lt.${end.getTime()}&order=ts.asc&limit=5000`),
  ]);
  if (!Array.isArray(hrAll) || !Array.isArray(beAll)) throw new Error('数据拉取异常: ' + JSON.stringify({ hrAll, beAll }).slice(0, 300));
  // 兜底：过滤掉空行，防止个别 null 元素炸统计
  const clean = arr => arr.filter(Boolean);
  const hrRaw = clean(hrAll);
  const beRaw = clean(beAll);
  const hr = hrRaw.filter(r => r.ts >= start.getTime());
  const hrPrev = hrRaw.filter(r => r.ts >= prevStart.getTime() && r.ts < start.getTime());
  const be = beRaw.filter(e => e.ts >= start.getTime());
  const bePrev = beRaw.filter(e => e.ts >= prevStart.getTime() && e.ts < start.getTime());
  const allMembers = [...new Set([...hr.map(r => r.member).filter(Boolean), ...be.map(e => e.member).filter(Boolean)])].sort();

  // —— 基础统计 ——
  const catLabel = c => ({ menstrual: '🌷 月经', gout: '🦴 痛风', anomaly: '😖 异常', intercourse: '❤️' }[c] || (c && c.startsWith && c.startsWith('cust_') ? '📝 ' + (be.find(e => e.category === c)?.payload?.custom_name || '自定义') : c));
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

  // 月经预测（v111：先合并连续记录，再按预存 5 天窗规则，与网页版逻辑一致）
  const predictions = allMembers.map(m => {
    const evs = beRaw.filter(e => e.category === 'menstrual' && e.member === m && (e.payload?.is_period_start !== false)).sort((a, b) => a.ts - b.ts);
    if (evs.length < 1) return null;
    // v111：合并连续/重叠记录（如 10.1、10.2、10.3-10.5 三条 → 一次 5 天经期）
    const spans = [];
    for (const e of evs) {
      const s = new Date(e.ts); s.setHours(0, 0, 0, 0);
      const en = e.end_ts ? (() => { const d = new Date(e.end_ts); d.setHours(0, 0, 0, 0); return d; })() : new Date(s);
      const last = spans[spans.length - 1];
      if (last && s.getTime() <= last.end.getTime() + 1.5 * DAY) {
        last.end = new Date(Math.max(last.end.getTime(), en.getTime()));
      } else spans.push({ start: new Date(s), end: new Date(Math.max(s.getTime(), en.getTime())) });
    }
    spans.forEach(sp => { sp.days = Math.round((sp.end - sp.start) / DAY) + 1; });
    if (spans.length < 2) return null;
    // v111：进行中/待定（最近经期开始后 5 天内或记录未结束）不预测，与网页版一致
    const lastP = spans[spans.length - 1];
    const today0 = new Date(); today0.setHours(0, 0, 0, 0);
    const pendEnd = Math.max(lastP.end.getTime(), lastP.start.getTime() + 4 * DAY);
    if (today0.getTime() >= lastP.start.getTime() && today0.getTime() <= pendEnd) return null;
    const gaps = [];
    for (let i = 1; i < spans.length; i++) {
      const d = Math.round((spans[i].start - spans[i - 1].start) / DAY);
      if (d >= 15 && d <= 45) gaps.push(d);
    }
    if (!gaps.length) return null;
    const recent = gaps.slice(-6);
    let avg = recent.length >= 3
      ? recent.reduce((s, v, i) => s + v * (i + 1 + (6 - recent.length)), 0) / recent.reduce((s, _, i) => s + (i + 1 + (6 - recent.length)), 0)
      : recent.reduce((s, v) => s + v, 0) / recent.length;
    const mean = gaps.reduce((s, v) => s + v, 0) / gaps.length;
    const sd = Math.sqrt(gaps.reduce((s, v) => s + (v - mean) ** 2, 0) / gaps.length);
    const regular = sd < 3 ? '周期规律' : sd <= 5 ? '周期基本规律' : '周期波动较大';
    const next = new Date(spans[spans.length - 1].start.getTime() + avg * DAY);
    // v111：上次经期不足 5 天 → 只预测下次月经窗，无卵泡期等各期预测
    const lastDays = lastP.days;
    const note = lastDays >= 5 ? '' : `（上次经期仅 ${lastDays} 天，暂无卵泡期等各期预测）`;
    return { member: m, avg: Math.round(avg), sd, regular, next: dayKey(next), note };
  }).filter(Boolean);

  // —— 总结报告（规则解读，不只是数字） ——
  const totalCur = hr.length + be.length;
  const totalPrev = hrPrev.length + bePrev.length;
  const diff = totalCur - totalPrev;
  const trend = diff > 0 ? `比上月多 ${diff} 条 📈` : diff < 0 ? `比上月少 ${-diff} 条 📉` : '与上月持平';
  const habit = totalCur >= 30 ? '记录坚持得很好，数据连贯性对看趋势非常有价值 👍'
    : totalCur >= 10 ? '记录习惯不错，再密一点趋势会更准'
    : '本月记录偏少，关键健康信息容易漏，建议至少把月经/痛风等重要事件记全';

  const summaryLines = [];
  summaryLines.push(`📅 本月共记录 **${totalCur}** 条（${trend}）。${habit}`);

  // 月经总结
  predictions.forEach(p => {
    summaryLines.push(`🌷 **${p.member}**：平均周期 ${p.avg} 天，${p.regular}（波动 ${p.sd.toFixed(1)} 天）；预测下次 **${p.next}**${p.note || ''}`);
  });

  // 痛风总结
  const goutCur = be.filter(e => e.category === 'gout');
  const goutPrev = bePrev.filter(e => e.category === 'gout');
  if (goutCur.length === 0) {
    summaryLines.push(`🦴 **痛风**：本月 ${goutPrev.length ? `无发作（上月 ${goutPrev.length} 次，有好转 👍）` : '无发作，继续保持'}`);
  } else {
    const lastGout = beRaw.filter(e => e.category === 'gout').sort((a, b) => b.ts - a.ts)[0];
    const sinceDays = Math.round((now.getTime() - lastGout.ts) / DAY);
    const locs = {};
    goutCur.forEach(e => { const l = e.payload?.location || '未记录部位'; locs[l] = (locs[l] || 0) + 1; });
    const topLoc = Object.entries(locs).sort((a, b) => b[1] - a[1])[0];
    summaryLines.push(`🦴 **痛风**：本月发作 **${goutCur.length} 次**（上月 ${goutPrev.length} 次），主要部位「${topLoc[0]}」；距上次发作 ${sinceDays} 天${goutCur.length >= 2 ? '，发作偏频繁，建议关注饮食（海鲜/啤酒/内脏）并考虑复查尿酸' : ''}`);
  }

  // 异常情况总结
  const anomalyCur = be.filter(e => e.category === 'anomaly');
  if (anomalyCur.length) {
    const types = {};
    anomalyCur.forEach(e => { const t = e.payload?.anomaly_type || '未分类'; types[t] = (types[t] || 0) + 1; });
    const top = Object.entries(types).sort((a, b) => b[1] - a[1]).map(([t, n]) => `${t}${n > 1 ? `×${n}` : ''}`).join('、');
    summaryLines.push(`😖 **异常情况**：本月 ${anomalyCur.length} 次（${top}），若同一症状反复出现建议留意诱因`);
  }

  // 自定义项目亮点
  const custCounts = {};
  be.forEach(e => { if (e.category && String(e.category).startsWith('cust_')) { const n = e.payload?.custom_name || '自定义'; custCounts[n] = (custCounts[n] || 0) + 1; } });
  const custTop = Object.entries(custCounts).sort((a, b) => b[1] - a[1])[0];
  if (custTop) summaryLines.push(`📝 **自定义项目**：「${custTop[0]}」记了 ${custTop[1]} 次，是本月记录最多的自定义项目`);

  summaryLines.push(`💊 以上为数据整理与简单解读，仅供参考，不能替代医生诊断；如有关注的指标请结合实际情况。`);

  // —— 摘要 markdown（v100 格式改版：卡片式 + 红绿灯状态 + 倒计时，微信里一目了然） ——
  const light = sd => sd < 3 ? '🟢 规律' : sd <= 5 ? '🟡 基本规律' : '🔴 波动较大';
  const daysUntil = dstr => {
    const [y, m, d] = dstr.split('-').map(Number);
    return Math.round((new Date(y, m - 1, d, 12).getTime() - now.getTime()) / DAY);
  };
  const summaryMd = [];
  // 头部：大标题 + 核心数字速览
  summaryMd.push(`# 🏠 家庭健康月报 · ${Number(period.slice(5))} 月`);
  summaryMd.push('');
  summaryMd.push(`### 本月共 **${totalCur}** 条　${trend}`);
  summaryMd.push('');
  summaryMd.push(`健康测量 ${hr.length} 条 ｜ 身体事件 ${be.length} 条`);
  summaryMd.push('');
  summaryMd.push('---');
  // 月经预测卡片（引用块 + 倒计时）
  summaryMd.push('## 🌷 月经 & 下次预测');
  if (predictions.length) {
    predictions.forEach(p => {
      const left = daysUntil(p.next);
      const leftStr = left < 0 ? '预测日已过，以实际来潮为准' : left === 0 ? '**就是今天！**' : `还有 **${left}** 天`;
      summaryMd.push(`> **${p.member}**　${light(p.sd)}`);
      summaryMd.push(`> 平均周期 **${p.avg}** 天 ｜ 预测 **${p.next}**`);
      summaryMd.push(`> ${leftStr}`);
      summaryMd.push('');
    });
  } else {
    summaryMd.push('> 月经记录不足两个周期，暂无法预测');
    summaryMd.push('');
  }
  summaryMd.push('---');
  // 痛风卡片（红绿灯：变频繁/持平/减少）
  summaryMd.push('## 🦴 痛风');
  if (goutCur.length === 0) {
    summaryMd.push(goutPrev.length
      ? `> 🟢 本月 **0** 次发作（上月 ${goutPrev.length} 次，有好转 ✅）`
      : '> 🟢 本月 **0** 次发作，继续保持 ✅');
  } else {
    const lastGout = beRaw.filter(e => e.category === 'gout').sort((a, b) => b.ts - a.ts)[0];
    const sinceDays = Math.round((now.getTime() - lastGout.ts) / DAY);
    const locs = {};
    goutCur.forEach(e => { const l = e.payload?.location || '未记录部位'; locs[l] = (locs[l] || 0) + 1; });
    const topLoc = Object.entries(locs).sort((a, b) => b[1] - a[1])[0];
    const freq = goutCur.length > goutPrev.length ? '🔴 比上月变频繁' : goutCur.length === goutPrev.length ? '🟡 与上月持平' : '🟢 比上月减少';
    summaryMd.push(`> ${freq}　本月 **${goutCur.length}** 次（上月 ${goutPrev.length} 次）`);
    summaryMd.push(`> 主要部位：**${topLoc[0]}** ｜ 距上次发作 **${sinceDays}** 天`);
    if (goutCur.length >= 2) summaryMd.push('> ⚠️ 发作偏频繁：建议少海鲜/啤酒/内脏，考虑复查尿酸');
  }
  summaryMd.push('');
  summaryMd.push('---');
  // 异常情况卡片（仅在有时显示，≥3 次标红提醒）
  if (anomalyCur.length) {
    summaryMd.push('## 😖 异常情况');
    const types = {};
    anomalyCur.forEach(e => { const t = e.payload?.anomaly_type || '未分类'; types[t] = (types[t] || 0) + 1; });
    Object.entries(types).sort((a, b) => b[1] - a[1]).forEach(([t, n]) => {
      summaryMd.push(`> ${n >= 3 ? '🔴' : '🟡'} **${t}** × ${n}${n >= 3 ? '　⚠️ 反复出现，留意诱因' : ''}`);
    });
    summaryMd.push('');
    summaryMd.push('---');
  }
  // 成员明细
  summaryMd.push('## 👨‍👩‍👧 成员明细');
  if (memberLines.length) memberLines.forEach(l => summaryMd.push('> ' + l));
  else summaryMd.push('> （本月无记录）');
  summaryMd.push('');
  summaryMd.push('---');
  summaryMd.push('📄 完整月报（含当月全部数据）已存云端备份');
  summaryMd.push('💊 数据解读仅供参考，不能替代医生诊断');
  summaryMd.push(`🕐 生成于 ${now.toISOString().slice(0, 16).replace('T', ' ')} UTC`);

  // —— 完整月报文档（报告 + 数据合并为一个 JSON 文件） ——
  const reportDoc = {
    doc: 'family-health-monthly-report',
    version: 2,
    period,
    generated_at: now.toISOString(),
    summary_md: summaryMd.join('\n'),
    summary_lines: summaryLines,
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
  const body = new URLSearchParams({ title: `🏠 健康月报 ${Number(period.slice(5))}月 ｜ ${totalCur} 条记录`, desp: summaryMd.join('\n') });
  const sr = await fetch(`https://sctapi.ftqq.com/${sendKey}.send`, { method: 'POST', body });
  const rj = await sr.json().catch(() => ({}));
  if (rj && rj.code === 0) console.log('✓ 微信推送成功');
  else { console.error('推送失败:', JSON.stringify(rj)); process.exit(1); }
})().catch(e => { console.error('✗', e.stack || e.message); process.exit(1); });
