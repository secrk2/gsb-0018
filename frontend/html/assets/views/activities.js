/* 公益活动管理：发布活动、报名花名册、现场打卡（正常/异常）核查 */
(function (global) {
  const Views = global.Views || (global.Views = {});

  function checkBadge(ci) {
    if (!ci) return '<span class="badge gray">未打卡</span>';
    if (ci.result === 'NORMAL') return '<span class="badge green">✅ 正常打卡</span>';
    return '<span class="badge red">⚠ 位置异常</span>';
  }

  Views.activities = async function (root) {
    const s = Api.getSession();
    root.innerHTML = `<div class="skeleton">加载中…</div>`;

    let offices = [];
    if (s.role === 'SUPERVISOR') {
      try {
        const dash = await Api.get('/dashboard');
        offices = dash.offices;
      } catch { /* ignore */ }
    }

    async function load() {
      root.querySelector('#act-list').innerHTML = `<div class="skeleton">加载中…</div>`;
      try {
        const items = await Api.get('/activities');
        render(items);
      } catch (e) {
        root.querySelector('#act-list').innerHTML = ErrorState('活动列表加载失败', e.message);
      }
    }

    root.innerHTML = `
      <div class="page-head" style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
        <h2 style="margin:0">🤝 公益活动</h2>
        <div class="desc" style="flex:1;min-width:240px">发布后对象在手机端报名；现场打卡由服务端按活动点半径校验位置，范围外打卡标为异常并记录实际距离</div>
        <button class="btn primary" id="btn-new">＋ 发布活动</button>
      </div>
      <div class="card"><div id="act-list"></div></div>`;

    root.querySelector('#btn-new').onclick = openPublish;

    function render(items) {
      const slot = root.querySelector('#act-list');
      if (!items.length) {
        slot.innerHTML = `<div class="state-box"><div class="ico">🤝</div><h3>尚未发布公益活动</h3><p>点击右上角「发布活动」安排一次公益劳动。</p></div>`;
        return;
      }
      const now = new Date().toISOString();
      slot.innerHTML = `
        <div class="table-wrap">
          <table class="data">
            <thead><tr>
              <th>活动</th>${s.role === 'SUPERVISOR' ? '<th>主办所</th>' : ''}
              <th>活动点 / 打卡半径</th><th>活动时间</th><th>报名截止</th><th>报名</th><th>状态</th><th></th>
            </tr></thead>
            <tbody>
              ${items.map((a) => {
                const phase = now < a.signupDeadline ? '报名中'
                  : now < a.startAt ? '报名截止·待开始'
                  : now <= a.endAt ? '进行中' : '已结束';
                return `
                <tr>
                  <td data-label="活动"><b>${UI.esc(a.title)}</b>
                    <div style="font-size:12px;color:var(--ink-muted)">${UI.esc(a.description || '')}</div></td>
                  ${s.role === 'SUPERVISOR' ? `<td data-label="主办所">${UI.esc(a.officeName)}</td>` : ''}
                  <td data-label="活动点">${UI.esc(a.locationName)}
                    <div style="font-size:12px;color:var(--ink-muted)">半径 ${a.radiusMeters} 米</div></td>
                  <td data-label="活动时间" style="white-space:nowrap">${UI.fmtTzFull(a.startAt, a.officeTimezone)}<br/>至 ${UI.fmtTzFull(a.endAt, a.officeTimezone)}</td>
                  <td data-label="报名截止" style="white-space:nowrap">${UI.fmtTzFull(a.signupDeadline, a.officeTimezone)}</td>
                  <td data-label="报名">${a.signupCount}${a.capacity ? '/' + a.capacity : ''} 人</td>
                  <td data-label="状态"><span class="badge ${phase === '进行中' ? 'green' : 'gray'}">${phase}</span></td>
                  <td data-label="操作"><button class="btn sm" data-id="${a.id}">花名册/打卡</button></td>
                </tr>`;
              }).join('')}
            </tbody>
          </table>
        </div>`;
      slot.querySelectorAll('button[data-id]').forEach((b) => {
        b.onclick = () => openRoster(+b.dataset.id);
      });
    }

    function openPublish() {
      const defaultOfficeId = s.role === 'STAFF' ? s.officeId : (offices[0] && offices[0].officeId);
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = `
        <div class="modal wide" role="dialog">
          <div class="modal-head">＋ 发布公益活动</div>
          <div class="modal-body">
            <div class="form-grid">
              <label>活动名称<input class="input" id="f-title" maxlength="64" placeholder="如：青山乡河道垃圾清理"></label>
              <label>活动地点名称<input class="input" id="f-loc" maxlength="128" placeholder="如：东河桥集合点"></label>
              ${s.role === 'SUPERVISOR' ? `<label>主办司法所
                <select class="input" id="f-office">${offices.map((o) => `<option value="${o.officeId}">${UI.esc(o.officeName)}</option>`).join('')}</select></label>` : ''}
              <label>活动点纬度 lat<input class="input" id="f-lat" type="number" step="0.000001" placeholder="如 30.35810"></label>
              <label>活动点经度 lng<input class="input" id="f-lng" type="number" step="0.000001" placeholder="如 114.47290"></label>
              <label>打卡有效半径（米，20~5000）<input class="input" id="f-radius" type="number" min="20" max="5000" value="200"></label>
              <label>容量上限（可空=不限）<input class="input" id="f-cap" type="number" min="1" placeholder="如 30"></label>
              <label>开始时间（按${s.role === 'SUPERVISOR' ? '主办所' : '本所'}时区）<input class="input" id="f-start" type="datetime-local"></label>
              <label>结束时间（按所时区）<input class="input" id="f-end" type="datetime-local"></label>
              <label>报名截止（按所时区）<input class="input" id="f-deadline" type="datetime-local"></label>
              <label style="grid-column:1/-1">活动说明<input class="input" id="f-desc" maxlength="512" placeholder="活动内容、着装与注意事项"></label>
            </div>
            <div style="margin-top:8px"><button class="btn sm" id="f-center">📍 填入司法所中心坐标</button>
              <span style="font-size:12px;color:var(--ink-muted);margin-left:8px">发布后对象现场打卡必须落在半径内，否则记异常</span></div>
            <div class="modal-error" id="pub-err"></div>
          </div>
          <div class="modal-foot">
            <button class="btn" id="pub-cancel">取消</button>
            <button class="btn primary" id="pub-ok">发布</button>
          </div>
        </div>`;
      document.getElementById('modal-root').appendChild(mask);
      const close = () => mask.remove();
      mask.querySelector('#pub-cancel').onclick = close;
      mask.addEventListener('click', (e) => { if (e.target === mask) close(); });

      const tz0 = 'Asia/Shanghai'; // 干警按本所时区；监管员选所后随所中心一起取得真实时区
      let publishTz = tz0;
      mask.querySelector('#f-center').onclick = async () => {
        // 用对象列表里本所/所选所第一名对象的时区与中心坐标（ObjectView 已下发 fenceCenter/timezone）
        const officeId = +(mask.querySelector('#f-office')?.value || s.officeId);
        try {
          const list = await Api.get('/objects?officeId=' + officeId);
          const first = list[0];
          if (!first) { UI.toast('该所暂无对象，无法取中心坐标', 'warn'); return; }
          mask.querySelector('#f-lat').value = first.fenceCenterLat;
          mask.querySelector('#f-lng').value = first.fenceCenterLng;
          publishTz = first.timezone || 'Asia/Shanghai';
          UI.toast('已填入「' + first.officeName + '」围栏中心（' + publishTz + '）', 'success');
        } catch (e) { UI.toast(e.message, 'error'); }
      };
      // 干警本所时区：打开发布窗时从本所对象取一次时区（可能是 Asia/Urumqi）
      if (s.role === 'STAFF') {
        Api.get('/objects').then((list) => {
          if (list[0] && list[0].timezone) publishTz = list[0].timezone;
        }).catch(() => {});
      }
      const officeSel0 = mask.querySelector('#f-office');
      if (officeSel0) {
        officeSel0.onchange = () => { publishTz = 'Asia/Shanghai'; };
      }

      // 默认时间：明天 09:00-12:00，报名截止明天 08:00（墙钟，提交时转 UTC）
      const d = new Date(Date.now() + 86400000);
      const pad = (n) => String(n).padStart(2, '0');
      const tomorrow = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
      mask.querySelector('#f-start').value = tomorrow + 'T09:00';
      mask.querySelector('#f-end').value = tomorrow + 'T12:00';
      mask.querySelector('#f-deadline').value = tomorrow + 'T08:00';

      mask.querySelector('#pub-ok').onclick = async () => {
        const err = mask.querySelector('#pub-err');
        const body = {
          title: mask.querySelector('#f-title').value.trim(),
          locationName: mask.querySelector('#f-loc').value.trim(),
          officeId: s.role === 'SUPERVISOR'
            ? +mask.querySelector('#f-office').value : undefined,
          lat: parseFloat(mask.querySelector('#f-lat').value),
          lng: parseFloat(mask.querySelector('#f-lng').value),
          radiusMeters: parseInt(mask.querySelector('#f-radius').value, 10),
          capacity: mask.querySelector('#f-cap').value ? parseInt(mask.querySelector('#f-cap').value, 10) : null,
          startAt: UI.wallTzToIso(mask.querySelector('#f-start').value, publishTz),
          endAt: UI.wallTzToIso(mask.querySelector('#f-end').value, publishTz),
          signupDeadline: UI.wallTzToIso(mask.querySelector('#f-deadline').value, publishTz),
          description: mask.querySelector('#f-desc').value.trim(),
        };
        try {
          await Api.post('/activities', body);
          UI.toast('活动已发布，对象手机端可见并可报名', 'success');
          close(); load();
        } catch (e) {
          err.style.display = 'block';
          err.className = 'login-error';
          err.textContent = e.message;
        }
      };
    }

    async function openRoster(id) {
      let d;
      try {
        d = await Api.get('/activities/' + id);
      } catch (e) { UI.toast(e.message, 'error'); return; }
      const a = d.activity;
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      const notChecked = '—';
      mask.innerHTML = `
        <div class="modal wide" role="dialog">
          <div class="modal-head">🤝 ${UI.esc(a.title)} · 报名与打卡花名册</div>
          <div class="modal-body">
            <div style="font-size:13px;color:var(--ink-secondary);margin-bottom:8px">
              ${UI.esc(a.locationName)}（半径 ${a.radiusMeters} 米）·
              ${UI.fmtTzFull(a.startAt, a.officeTimezone)} ~ ${UI.fmtTzFull(a.endAt, a.officeTimezone)} ·
              报名 ${a.signupCount} 人
            </div>
            <div class="table-wrap">
              <table class="data">
                <thead><tr><th>对象</th><th>报名时间</th><th>现场打卡</th><th>打卡定位/距离</th></tr></thead>
                <tbody>
                  ${d.roster.length ? d.roster.map((r) => `
                    <tr>
                      <td data-label="对象"><b>${UI.esc(r.maskedName)}</b>
                        <div style="font-size:12px;color:var(--ink-muted)">${UI.esc(r.correctionNo)}</div></td>
                      <td data-label="报名时间" style="white-space:nowrap">${UI.fmtTzFull(r.signedAt, a.officeTimezone)}</td>
                      <td data-label="现场打卡">${checkBadge(r.checkedIn ? r.checkIn : null)}</td>
                      <td data-label="打卡定位">${r.checkedIn ? ciText(r.checkIn, a.officeTimezone) : notChecked}</td>
                    </tr>`).join('')
                    : '<tr><td colspan="4" style="color:var(--ink-muted)">暂无对象报名</td></tr>'}
                </tbody>
              </table>
            </div>
          </div>
          <div class="modal-foot"><button class="btn primary" id="roster-close">关闭</button></div>
        </div>`;
      document.getElementById('modal-root').appendChild(mask);
      mask.querySelector('#roster-close').onclick = () => mask.remove();
      mask.addEventListener('click', (e) => { if (e.target === mask) mask.remove(); });
    }

    function ciText(ci, tz) {
      const color = ci.insideRange ? 'var(--good)' : 'var(--critical)';
      return `<div style="font-size:12.5px">
          <span style="color:${color}">距活动点 ${ci.distanceMeters} 米 / 半径 ${ci.radiusMeters} 米</span><br/>
          <span style="color:var(--ink-muted)">${Number(ci.lat).toFixed(5)}, ${Number(ci.lng).toFixed(5)} ·
          ${UI.fmtTzFull(ci.fixTime, tz)}</span></div>`;
    }

    await load();
  };
})(window);
