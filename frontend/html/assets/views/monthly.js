/* 月度报到：花名册 + 一次勾选多个对象批量完成（部分失败逐条说明） */
(function (global) {
  const Views = global.Views || (global.Views = {});

  function monthNow() {
    // 页面按干警/区局本地时区取当前月即可（默认 Asia/Shanghai；跨时区伊宁对象由服务端按其月份强校验）
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
  }

  Views.monthly = async function (root) {
    root.innerHTML = `<div class="skeleton">加载中…</div>`;
    let month = monthNow();
    let items = [];

    async function load() {
      listRoot.innerHTML = `<div class="skeleton">加载中…</div>`;
      try {
        items = await Api.get('/monthly-reports?month=' + month);
        render();
      } catch (e) {
        listRoot.innerHTML = ErrorState('月度花名册加载失败', e.message);
      }
    }

    root.innerHTML = `
      <div class="page-head" style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
        <h2 style="margin:0">🗓️ 月度报到</h2>
        <div class="desc" style="flex:1;min-width:260px">勾选多名对象可一次批量完成月度报到；系统逐条处理，已完成的自动跳过、越权/终态逐条标失败，并逐人反馈结果</div>
        <label style="font-size:13px;color:var(--ink-secondary)">月份
          <input class="input" id="f-month" type="month" style="margin-left:6px"></label>
      </div>
      <div class="card">
        <div class="filter-bar">
          <label style="font-size:13px"><input type="checkbox" id="chk-only-pending"> 仅看未完成</label>
          <span style="flex:1"></span>
          <span id="sel-info" style="font-size:12.5px;color:var(--ink-muted)">已选 0 人</span>
          <button class="btn primary" id="btn-batch">批量完成所选（0）</button>
        </div>
        <div id="list-root"></div>
      </div>`;

    const listRoot = root.querySelector('#list-root');
    root.querySelector('#f-month').value = month;
    root.querySelector('#f-month').onchange = (e) => { month = e.target.value || monthNow(); load(); };
    root.querySelector('#chk-only-pending').onchange = render;
    root.querySelector('#btn-batch').onclick = doBatch;

    function render() {
      const onlyPending = root.querySelector('#chk-only-pending').checked;
      const rows = items.filter((r) => !onlyPending || (!r.completed && r.active));
      const selectable = items.filter((r) => !r.completed && r.active);
      if (!rows.length) {
        listRoot.innerHTML = `<div class="state-box"><div class="ico">🗓️</div><h3>该月没有可显示的对象</h3></div>`;
        updateSel();
        return;
      }
      listRoot.innerHTML = `
        <div class="table-wrap">
          <table class="data">
            <thead><tr>
              <th style="width:36px"><input type="checkbox" id="chk-all" title="全选未完成"></th>
              <th>编号/对象</th><th>司法所</th><th>档案状态</th><th>${month} 月度报到</th><th>办理信息</th>
            </tr></thead>
            <tbody>
              ${rows.map((r) => `
                <tr class="${r.completed ? '' : 'active-row'}">
                  <td>${(!r.completed && r.active) ? `<input type="checkbox" class="chk-obj" data-id="${r.offenderId}">` : ''}</td>
                  <td data-label="对象"><b>${UI.esc(r.maskedName)}</b><div style="font-size:12px;color:var(--ink-muted)">${UI.esc(r.correctionNo)}</div></td>
                  <td data-label="司法所">${UI.esc(r.officeName)}</td>
                  <td data-label="状态">${UI.statusBadge(r.status)}</td>
                  <td data-label="月度报到">${r.completed
                    ? '<span class="badge green">✅ 已完成</span>'
                    : (r.active ? '<span class="badge gray">🕒 未完成</span>'
                      : '<span class="badge gray">终态免办</span>')}</td>
                  <td data-label="办理信息" style="font-size:12.5px;color:var(--ink-muted)">${r.completed
                    ? `${UI.fmtTzFull(r.completedAt, r.officeTimezone || 'Asia/Shanghai')} · ${UI.esc(r.operatorName)}${r.note ? ' · ' + UI.esc(r.note) : ''}`
                    : '—'}</td>
                </tr>`).join('')}
            </tbody>
          </table>
        </div>`;
      listRoot.querySelectorAll('.chk-obj').forEach((c) => { c.onchange = updateSel; });
      const all = listRoot.querySelector('#chk-all');
      if (all) {
        all.onchange = () => {
          listRoot.querySelectorAll('.chk-obj').forEach((c) => { c.checked = all.checked; });
          updateSel();
        };
      }
      updateSel();
    }

    function selectedIds() {
      return [...listRoot.querySelectorAll('.chk-obj:checked')].map((c) => +c.dataset.id);
    }
    function updateSel() {
      const n = selectedIds().length;
      root.querySelector('#sel-info').textContent = `已选 ${n} 人`;
      root.querySelector('#btn-batch').textContent = `批量完成所选（${n}）`;
      root.querySelector('#btn-batch').disabled = n === 0;
    }

    async function doBatch() {
      const ids = selectedIds();
      if (!ids.length) return;
      let note = null;
      try {
        note = await UI.confirmModal({
          title: `批量完成 ${ids.length} 人的月度报到`,
          icon: '🗓️',
          warn: '系统将逐人办理并逐条反馈：本月已完成的自动跳过，终态/越权对象会标失败，不会整批回滚。',
          bodyHtml: `月份：<b>${month}</b>。可填写统一备注（选填）。`,
          reasonLabel: '办理备注',
          reasonPlaceholder: '如：本月集中点验，当面完成月度报到（选填）',
          requireReason: false,
          confirmText: '确认批量办理',
        });
      } catch { return; }

      const btn = root.querySelector('#btn-batch');
      btn.disabled = true; btn.textContent = '批量办理中…';
      try {
        const res = await Api.post('/monthly-reports/batch-complete', {
          month, offenderIds: ids, note,
        });
        showResult(res);
        await load();
        // load 重绘后保持勾选清空
      } catch (e) {
        UI.toast(e.message, 'error');
      } finally {
        btn.disabled = false;
      }
    }

    function showResult(res) {
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      const tone = (o) => o === 'SUCCESS' ? 'green' : o === 'SKIPPED' ? 'amber' : 'red';
      const label = (o) => o === 'SUCCESS' ? '成功' : o === 'SKIPPED' ? '跳过' : '失败';
      mask.innerHTML = `
        <div class="modal wide" role="dialog">
          <div class="modal-head">🗓️ 批量办理结果</div>
          <div class="modal-body">
            <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:12px">
              <span class="badge green">成功 ${res.successCount}</span>
              <span class="badge amber">跳过 ${res.skippedCount}</span>
              <span class="badge red">失败 ${res.failedCount}</span>
              <span style="color:var(--ink-muted);font-size:12.5px;align-self:center">共处理 ${res.requested} 人（${res.monthFrom} ~ ${res.monthTo}）</span>
            </div>
            <div class="table-wrap" style="max-height:340px;overflow:auto">
              <table class="data">
                <thead><tr><th>对象</th><th>结果</th><th>说明（谁成了、谁卡在哪）</th></tr></thead>
                <tbody>
                  ${res.results.map((r) => `
                    <tr>
                      <td data-label="对象">${r.maskedName ? UI.esc(r.maskedName) : '#' + r.offenderId}
                        <div style="font-size:12px;color:var(--ink-muted)">${UI.esc(r.correctionNo || '')}</div></td>
                      <td data-label="结果"><span class="badge ${tone(r.outcome)}">${label(r.outcome)}</span></td>
                      <td data-label="说明">${UI.esc(r.message)}</td>
                    </tr>`).join('')}
                </tbody>
              </table>
            </div>
          </div>
          <div class="modal-foot"><button class="btn primary" id="res-close">知道了</button></div>
        </div>`;
      document.getElementById('modal-root').appendChild(mask);
      mask.querySelector('#res-close').onclick = () => mask.remove();
      mask.addEventListener('click', (e) => { if (e.target === mask) mask.remove(); });
    }

    await load();
  };
})(window);
