/* 矫正对象手机端·请销假：申请、查看两级审批进度/退回意见、退回重提、销假 */
(function (global) {
  const LEAVE_BADGE = {
    PENDING_OFFICE: 'amber', PENDING_BUREAU: 'amber',
    OFFICE_RETURNED: 'red', BUREAU_RETURNED: 'red',
    APPROVED: 'green', COMPLETED: 'gray', OVERDUE: 'red',
  };

  async function mount(root, o) {
    const slot = root.querySelector('#my-leave-slot');
    if (!slot) return;
    let leaves = [];
    try {
      leaves = await Api.get('/offender/leaves');
    } catch (e) {
      slot.innerHTML = `<div style="color:var(--critical);font-size:13px">加载失败：${UI.esc(e.message)}</div>`;
      return;
    }

    const open = leaves.filter((l) => ['PENDING_OFFICE', 'PENDING_BUREAU', 'APPROVED',
      'OFFICE_RETURNED', 'BUREAU_RETURNED'].includes(l.status));
    const cur = open[0] || leaves[0];

    function paint() {
      if (!cur) {
        slot.innerHTML = `
          <div style="font-size:13px;color:var(--ink-secondary);margin-bottom:10px">
            暂无请假记录。请假须事先申请，经<b>司法所初审、区局复核</b>两级通过后方可离所；
            批准假期内越出电子围栏不算违规，假期结束须及时销假。</div>
          <button class="btn sm primary" id="lv-new" style="width:100%">＋ 发起请假申请</button>
          ${historyHtml(leaves)}`;
        bindNew();
        return;
      }
      const st = cur.status;
      slot.innerHTML = `
        <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
          <span class="badge ${LEAVE_BADGE[st] || 'gray'}">${UI.esc(UI.LEAVE_STATUS_LABEL[st] || st)}${cur.revision > 1 ? '（第 ' + cur.revision + ' 轮）' : ''}</span>
          <span style="font-size:12px;color:var(--ink-muted)">单号 #${cur.id}</span>
        </div>
        <dl class="kv" style="grid-template-columns:72px 1fr;margin-top:8px;font-size:13px">
          <dt>目的地</dt><dd>${UI.esc(cur.destination)}</dd>
          <dt>事由</dt><dd style="white-space:pre-wrap">${UI.esc(cur.reason)}</dd>
          <dt>假期</dt><dd>${UI.fmtTzFull(cur.startAt, o.timezone)}<br/>至 ${UI.fmtTzFull(cur.endAt, o.timezone)}</dd>
        </dl>
        <div id="lv-progress" style="margin-top:8px"></div>
        <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:10px">
          ${st === 'OFFICE_RETURNED' || st === 'BUREAU_RETURNED'
            ? `<button class="btn sm primary" id="lv-resubmit" style="flex:1">按退回意见修改重提</button>` : ''}
          ${st === 'APPROVED' ? `<button class="btn sm primary" id="lv-return" style="flex:1">我已返所·立即销假</button>` : ''}
          ${['PENDING_OFFICE', 'PENDING_BUREAU'].includes(st)
            ? '<div style="font-size:12.5px;color:var(--ink-muted)">审批中，请耐心等待；通过/退回都会在这里提示。</div>' : ''}
          ${(!open.length) ? `<button class="btn sm" id="lv-new">＋ 再次请假</button>` : ''}
        </div>
        ${(!open.length || leaves.length > 1) ? historyHtml(leaves.filter((l) => l.id !== cur.id)) : ''}`;

      loadProgress();
      const bNew = slot.querySelector('#lv-new');
      if (bNew) bNew.onclick = () => form(null);
      const bRe = slot.querySelector('#lv-resubmit');
      if (bRe) bRe.onclick = () => form(cur);
      const bRet = slot.querySelector('#lv-return');
      if (bRet) bRet.onclick = doReturn;
    }

    function historyHtml(list) {
      if (!list.length) return '';
      return `<div style="margin-top:12px;border-top:1px solid var(--hairline);padding-top:8px">
        <div style="font-size:12px;font-weight:650;color:var(--ink-secondary);margin-bottom:6px">历史请假单</div>
        ${list.slice(0, 5).map((l) => `
          <button class="lv-hist" data-id="${l.id}"
            style="width:100%;text-align:left;background:none;border:0;padding:6px 2px;cursor:pointer;
            border-bottom:1px dashed var(--hairline);font-size:12.5px">
            <span class="badge ${LEAVE_BADGE[l.status] || 'gray'}">${UI.esc(UI.LEAVE_STATUS_LABEL[l.status] || l.status)}</span>
            ${UI.esc(l.destination)} · ${UI.fmtTz(l.startAt, o.timezone)}~${UI.fmtTz(l.endAt, o.timezone)}
          </button>`).join('')}
      </div>`;
    }

    slot.onclick = async (e) => {
      const b = e.target.closest('.lv-hist');
      if (!b) return;
      try {
        const d = await Api.get('/offender/leaves/' + b.dataset.id);
        showLogs(d);
      } catch (err) { UI.toast(err.message, 'error'); }
    };

    async function loadProgress() {
      const ps = slot.querySelector('#lv-progress');
      if (!ps) return;
      try {
        const d = await Api.get('/offender/leaves/' + cur.id);
        ps.innerHTML = d.logs.map((g) => `
          <div style="font-size:12.5px;padding:5px 0;border-bottom:1px dashed var(--hairline)">
            <b>${UI.esc(g.actionLabel)}</b>
            <span style="color:var(--ink-muted)"> · ${UI.esc(g.operatorName)} · ${UI.fmtTzFull(g.createdAt, o.timezone)}</span>
            ${g.comment ? `<div style="color:${g.action.endsWith('RETURN') ? 'var(--critical)' : 'var(--ink-secondary)'};margin-top:2px">${UI.esc(g.comment)}</div>` : ''}
          </div>`).join('');
      } catch { /* 进度加载失败不阻塞 */ }
    }

    function bindNew() {
      const b = slot.querySelector('#lv-new');
      if (b) b.onclick = () => form(null);
    }

    function form(existing) {
      const isRe = !!existing;
      // 默认墙钟：后天 09:00 开始、当天 17:00 结束（按对象所时区解释后转 UTC 提交）
      const d = new Date(Date.now() + 2 * 86400000);
      const pad = (n) => String(n).padStart(2, '0');
      const day = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = `
        <div class="modal" role="dialog">
          <div class="modal-head">${isRe ? '↩️ 按退回意见修改重提（第 ' + (existing.revision + 1) + ' 轮）' : '📝 发起请假申请'}</div>
          <div class="modal-body">
            ${isRe ? `<div class="confirm-warn">⚠️ 上一轮被退回，修改后提交将从司法所初审重新走两级审批，历次内容与意见均留痕。</div>` : ''}
            <div class="field"><label>目的地（具体到县/乡/单位）</label>
              <input class="input" id="m-dest" maxlength="128" value="${isRe ? UI.esc(existing.destination) : ''}" placeholder="如：县人民医院"></div>
            <div class="field"><label>请假事由（不少于 4 字）</label>
              <textarea class="input" id="m-reason" maxlength="256" placeholder="如：家属住院手术需赴县医院陪护">${isRe ? UI.esc(existing.reason) : ''}</textarea></div>
            <div class="field"><label>假期开始（${UI.esc(o.timezone)} 时间）</label>
              <input class="input" id="m-start" type="datetime-local" value="${isRe ? UI.isoToWallInput(existing.startAt, o.timezone) : day + 'T09:00'}"></div>
            <div class="field"><label>假期结束（${UI.esc(o.timezone)} 时间）</label>
              <input class="input" id="m-end" type="datetime-local" value="${isRe ? UI.isoToWallInput(existing.endAt, o.timezone) : day + 'T17:00'}"></div>
            <div class="modal-error" id="m-err"></div>
          </div>
          <div class="modal-foot">
            <button class="btn" id="m-cancel">取消</button>
            <button class="btn primary" id="m-ok">${isRe ? '重新提交' : '提交申请'}</button>
          </div>
        </div>`;
      document.getElementById('modal-root').appendChild(mask);
      const close = () => mask.remove();
      mask.querySelector('#m-cancel').onclick = close;
      mask.addEventListener('click', (e) => { if (e.target === mask) close(); });
      mask.querySelector('#m-ok').onclick = async () => {
        const body = {
          destination: mask.querySelector('#m-dest').value.trim(),
          reason: mask.querySelector('#m-reason').value.trim(),
          startAt: UI.wallTzToIso(mask.querySelector('#m-start').value, o.timezone),
          endAt: UI.wallTzToIso(mask.querySelector('#m-end').value, o.timezone),
        };
        try {
          if (isRe) await Api.post('/offender/leaves/' + existing.id + '/resubmit', body);
          else await Api.post('/offender/leaves', body);
          UI.toast(isRe ? '已重新提交，等待司法所初审' : '申请已提交，等待司法所初审', 'success');
          close();
          global.OffenderLeave.reload(o, root);
        } catch (e) {
          const err = mask.querySelector('#m-err');
          err.style.display = 'block'; err.className = 'login-error'; err.textContent = e.message;
        }
      };
    }

    async function doReturn() {
      try {
        await UI.confirmModal({
          title: '确认销假返所',
          icon: '🏠',
          bodyHtml: '销假后档案恢复「<b>在矫</b>」，电子围栏越界报警将同步恢复。请确认你已实际返回规定活动范围。',
          reasonLabel: '销假备注',
          reasonPlaceholder: '如：已到家/已返所（选填）',
          requireReason: false,
          confirmText: '确认销假',
        });
      } catch { return; }
      try {
        await Api.post('/offender/leaves/' + cur.id + '/return', {});
        UI.toast('销假成功，已恢复在矫状态', 'success');
        await reload(o, root);
      } catch (e) { UI.toast(e.message, 'error'); }
    }

    function showLogs(d) {
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = `
        <div class="modal" role="dialog">
          <div class="modal-head">📝 请假单 #${d.leave.id} 留痕</div>
          <div class="modal-body" style="max-height:60vh;overflow:auto">
            ${d.logs.map((g) => `
              <div style="font-size:13px;padding:7px 0;border-bottom:1px dashed var(--hairline)">
                <b>${UI.esc(g.actionLabel)}</b> <span style="color:var(--ink-muted)">· 第 ${g.revision} 轮 · ${UI.fmtTzFull(g.createdAt, o.timezone)}</span>
                <div style="margin-top:3px">${UI.esc(g.operatorName)}${g.comment ? '：' + UI.esc(g.comment) : ''}</div>
              </div>`).join('')}
          </div>
          <div class="modal-foot"><button class="btn primary" id="lg-close">关闭</button></div>
        </div>`;
      document.getElementById('modal-root').appendChild(mask);
      mask.querySelector('#lg-close').onclick = () => mask.remove();
      mask.addEventListener('click', (e) => { if (e.target === mask) mask.remove(); });
    }

    paint();
  }

  async function reload(o, root) {
    await mount(root, o);
  }

  global.OffenderLeave = { mount, reload };
})(window);
