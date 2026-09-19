/* 请销假两级审批工作台（司法所初审 / 区局复核 / 退回重提 / 销假） */
(function (global) {
  const Views = global.Views || (global.Views = {});
  const session = () => Api.getSession();

  const TABS = [
    { key: 'OFFICE', label: '司法所待初审', staff: true },
    { key: 'BUREAU', label: '区局待复核', staff: false, bureauOnly: true },
    { key: 'RETURNED', label: '退回待重提', staff: false, offenderHidden: true },
    { key: 'ACTIVE', label: '假期中/逾期', staff: false },
    { key: 'ALL', label: '全部单据', staff: false },
  ];

  function leaveBadge(status) {
    const map = {
      PENDING_OFFICE: 'amber', PENDING_BUREAU: 'amber',
      OFFICE_RETURNED: 'red', BUREAU_RETURNED: 'red',
      APPROVED: 'green', COMPLETED: 'gray', OVERDUE: 'red',
    };
    return `<span class="badge ${map[status] || 'gray'}">${UI.esc(UI.LEAVE_STATUS_LABEL[status] || status)}</span>`;
  }

  Views.leaves = async function (root) {
    const s = session();
    root.innerHTML = `<div class="skeleton">加载中…</div>`;
    let tab = s.role === 'SUPERVISOR' ? 'BUREAU' : 'OFFICE';

    async function load() {
      root.querySelector('#leave-list').innerHTML = `<div class="skeleton">加载中…</div>`;
      try {
        const items = await Api.get('/leaves?stage=' + tab);
        renderList(items);
      } catch (e) {
        root.querySelector('#leave-list').innerHTML = ErrorState('审批列表加载失败', e.message);
      }
    }

    const visibleTabs = TABS.filter((t) => {
      if (s.role === 'SUPERVISOR') return true;
      // 司法所干警看不到“区局待复核”办理入口（可在全部单据中查看）
      return !t.bureauOnly;
    });

    root.innerHTML = `
      <div class="page-head">
        <h2>📝 请销假审批</h2>
        <div class="desc">两级审批：司法所初审 → 区局复核；退回后对象在手机端修改重提，逐轮留痕；批准假期内定位越界不报警，届满未销假自动升违规</div>
      </div>
      <div class="card">
        <div class="tab-bar" id="leave-tabs">
          ${visibleTabs.map((t) => `<button class="tab ${t.key === tab ? 'active' : ''}" data-tab="${t.key}">${t.label}</button>`).join('')}
        </div>
        <div id="leave-list" style="margin-top:12px"></div>
      </div>`;

    root.querySelectorAll('.tab[data-tab]').forEach((b) => {
      b.onclick = () => {
        tab = b.dataset.tab;
        root.querySelectorAll('.tab[data-tab]').forEach((x) => x.classList.toggle('active', x.dataset.tab === tab));
        load();
      };
    });

    function renderList(items) {
      const slot = root.querySelector('#leave-list');
      if (!items.length) {
        slot.innerHTML = `<div class="state-box"><div class="ico">📭</div><h3>暂无单据</h3><p>该环节目前没有需要处理的请假单。</p></div>`;
        return;
      }
      slot.innerHTML = `
        <div class="table-wrap">
          <table class="data">
            <thead><tr>
              <th>编号/对象</th>${s.role === 'SUPERVISOR' ? '<th>司法所</th>' : ''}
              <th>目的地</th><th>假期</th><th>轮次</th><th>状态</th><th>提交时间</th><th></th>
            </tr></thead>
            <tbody>
              ${items.map((l) => `
                <tr>
                  <td data-label="对象"><b>${UI.esc(l.maskedName)}</b><div style="font-size:12px;color:var(--ink-muted)">${UI.esc(l.correctionNo)}</div></td>
                  ${s.role === 'SUPERVISOR' ? `<td data-label="司法所">${UI.esc(l.officeName)}</td>` : ''}
                  <td data-label="目的地">${UI.esc(l.destination)}</td>
                  <td data-label="假期" style="white-space:nowrap">
                    ${UI.fmtTzFull(l.startAt, l.officeTimezone)}<br/>至 ${UI.fmtTzFull(l.endAt, l.officeTimezone)}</td>
                  <td data-label="轮次">第 ${l.revision} 轮</td>
                  <td data-label="状态">${leaveBadge(l.status)}</td>
                  <td data-label="提交时间" style="white-space:nowrap">${UI.fmtTzFull(l.submittedAt, l.officeTimezone)}</td>
                  <td data-label="操作"><button class="btn sm primary" data-id="${l.id}">办理/查看</button></td>
                </tr>`).join('')}
            </tbody>
          </table>
        </div>`;
      slot.querySelectorAll('button[data-id]').forEach((b) => {
        b.onclick = () => openDetail(+b.dataset.id);
      });
    }

    async function openDetail(id) {
      let d;
      try {
        d = await Api.get('/leaves/' + id);
      } catch (e) { UI.toast(e.message, 'error'); return; }
      renderModal(d);
    }

    function renderModal(d) {
      const l = d.leave;
      const s0 = session();
      const canOfficeApprove = s0.role === 'STAFF' && s0.officeId === l.officeId
        && l.status === 'PENDING_OFFICE';
      const canBureauApprove = s0.role === 'SUPERVISOR' && l.status === 'PENDING_BUREAU';
      const canReturn = l.status === 'APPROVED';

      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = `
        <div class="modal wide" role="dialog" aria-modal="true">
          <div class="modal-head">📝 请假单 #${l.id} ${leaveBadge(l.status)}</div>
          <div class="modal-body">
            <dl class="kv" style="grid-template-columns:96px 1fr">
              <dt>对象</dt><dd><b>${UI.esc(l.maskedName)}</b>（${UI.esc(l.correctionNo)}）· ${UI.esc(l.officeName)}</dd>
              <dt>目的地</dt><dd>${UI.esc(l.destination)}</dd>
              <dt>请假事由</dt><dd style="white-space:pre-wrap">${UI.esc(l.reason)}</dd>
              <dt>假期</dt><dd>${UI.fmtTzFull(l.startAt, l.officeTimezone)} 至 ${UI.fmtTzFull(l.endAt, l.officeTimezone)}
                <span style="color:var(--ink-muted)">（按 ${UI.esc(l.officeTimezone)}）</span></dd>
              ${l.returnedAt ? `<dt>销假时间</dt><dd>${UI.fmtTzFull(l.returnedAt, l.officeTimezone)}${l.returnNote ? '：' + UI.esc(l.returnNote) : ''}</dd>` : ''}
              ${l.overdueAt ? `<dt>逾期判定</dt><dd style="color:var(--critical)">${UI.fmtTzFull(l.overdueAt, l.officeTimezone)} 系统自动登记逾假未归并转训诫</dd>` : ''}
            </dl>

            <div style="margin:14px 0 6px;font-weight:600;font-size:13.5px">审批留痕（逐轮完整保留，退回重提不覆盖）</div>
            <div class="timeline" style="max-height:240px;overflow:auto">
              ${d.logs.map((g) => `
                <div class="tl-item">
                  <div class="tl-line">
                    <span class="badge ${g.action.endsWith('RETURN') || g.action === 'OVERDUE' ? 'red'
                      : g.action === 'RETURN' ? 'gray' : 'green'}" style="margin-right:6px">${UI.esc(g.actionLabel)}</span>
                    第 ${g.revision} 轮
                  </div>
                  <div class="tl-meta">${UI.esc(g.operatorName)} · ${UI.fmtTzFull(g.createdAt, l.officeTimezone)}</div>
                  ${g.comment ? `<div class="tl-meta" style="color:var(--ink-secondary)">${UI.esc(g.comment)}</div>` : ''}
                  ${g.contentSnapshot ? `<div class="tl-meta" style="color:var(--ink-muted);font-size:12px">申请内容：${UI.esc(snapshotText(g.contentSnapshot))}</div>` : ''}
                </div>`).join('')}
            </div>

            <div class="modal-error" id="leave-err"></div>
          </div>
          <div class="modal-foot" style="flex-wrap:wrap;gap:8px">
            <button class="btn" id="leave-close">关闭</button>
            ${canOfficeApprove ? `
              <button class="btn danger" id="leave-reject">退回对象修改</button>
              <button class="btn primary" id="leave-approve">司法所初审通过 → 报区局</button>` : ''}
            ${canBureauApprove ? `
              <button class="btn danger" id="leave-reject">区局退回</button>
              <button class="btn primary" id="leave-approve">区局复核通过·准假</button>` : ''}
            ${canReturn ? `<button class="btn primary" id="leave-return">代对象办理销假返所</button>` : ''}
          </div>
        </div>`;
      document.getElementById('modal-root').appendChild(mask);
      const close = () => mask.remove();
      mask.querySelector('#leave-close').onclick = () => { close(); load(); };
      mask.addEventListener('click', (e) => { if (e.target === mask) { close(); load(); } });

      const approveBtn = mask.querySelector('#leave-approve');
      if (approveBtn) {
        approveBtn.onclick = async () => {
          const isBureau = s0.role === 'SUPERVISOR';
          let comment = null;
          try {
            comment = await UI.confirmModal({
              title: isBureau ? '区局复核通过' : '司法所初审通过',
              icon: '✅',
              bodyHtml: `对象 <span class="target-name">${UI.esc(l.maskedName)}</span> 的请假单将${
                isBureau ? '正式准假，档案转「请假外出」，假期内越界不报警并核销未处置越界红点' : '报送区局复核'}。`,
              reasonLabel: '审批意见',
              reasonPlaceholder: '可填意见（选填，不填使用默认意见）',
              requireReason: false,
              confirmText: '确认通过',
            });
          } catch { return; }
          try {
            await Api.post('/leaves/' + l.id + (isBureau ? '/bureau-review' : '/office-review'),
              { approve: true, comment });
            UI.toast(isBureau ? '已准假，定位联动已生效' : '初审通过，已报区局复核', 'success');
            close(); load();
          } catch (e) { UI.toast(e.message, 'error'); }
        };
      }
      const rejectBtn = mask.querySelector('#leave-reject');
      if (rejectBtn) {
        rejectBtn.onclick = async () => {
          const isBureau = s0.role === 'SUPERVISOR';
          let comment;
          try {
            comment = await UI.confirmModal({
              title: isBureau ? '区局退回请假单' : '司法所退回请假单',
              icon: '↩️',
              warn: '退回后对象将在手机端看到退回意见，可修改请假内容后重新提交（同一单据第 ' + (l.revision + 1) + ' 轮，留痕保留）。',
              bodyHtml: `对象 <span class="target-name">${UI.esc(l.maskedName)}</span>：${UI.esc(l.destination)}`,
              reasonLabel: '退回意见',
              reasonPlaceholder: '如：事由不充分/材料不全/时间与教育学习冲突，请修改后重提',
              requireReason: true,
              confirmText: '确认退回',
              danger: true,
            });
          } catch { return; }
          try {
            await Api.post('/leaves/' + l.id + (isBureau ? '/bureau-review' : '/office-review'),
              { approve: false, comment });
            UI.toast('已退回，等待对象修改重提', 'success');
            close(); load();
          } catch (e) { UI.toast(e.message, 'error'); }
        };
      }
      const returnBtn = mask.querySelector('#leave-return');
      if (returnBtn) {
        returnBtn.onclick = async () => {
          let note = null;
          try {
            note = await UI.confirmModal({
              title: '代对象办理销假返所',
              icon: '🏠',
              bodyHtml: `确认对象 <span class="target-name">${UI.esc(l.maskedName)}</span> 已按期返所，销假后档案恢复「在矫」，定位越界报警同步恢复。`,
              reasonLabel: '销假备注',
              reasonPlaceholder: '如：已当面核验返所（选填）',
              requireReason: false,
              confirmText: '确认销假',
            });
          } catch { return; }
          try {
            await Api.post('/leaves/' + l.id + '/return', { note });
            UI.toast('已销假，对象恢复在矫', 'success');
            close(); load();
          } catch (e) { UI.toast(e.message, 'error'); }
        };
      }
    }

    function snapshotText(json) {
      try {
        const o = JSON.parse(json);
        return `${o.destination}；${o.reason}；${o.startAt?.replace('T', ' ').slice(0, 16)} ~ ${o.endAt?.replace('T', ' ').slice(0, 16)}`;
      } catch { return json; }
    }

    await load();
  };
})(window);
