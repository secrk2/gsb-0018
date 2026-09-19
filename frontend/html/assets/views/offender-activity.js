/* 矫正对象手机端·公益活动：报名 + 现场打卡（位置服务端校验，范围外标异常） */
(function (global) {
  async function mount(root, o) {
    const slot = root.querySelector('#my-activity-slot');
    if (!slot) return;
    let list = [];
    try {
      list = await Api.get('/offender/activities');
    } catch (e) {
      slot.innerHTML = `<div style="color:var(--critical);font-size:13px">加载失败：${UI.esc(e.message)}</div>`;
      return;
    }

    if (!list.length) {
      slot.innerHTML = `<div style="font-size:13px;color:var(--ink-muted)">司法所暂未发布公益活动。</div>`;
      return;
    }

    slot.innerHTML = list.map((a) => {
      const now = new Date().toISOString();
      const phase = now < a.signupDeadline ? '报名中'
        : now < a.startAt ? '待开始'
        : now <= a.endAt ? '进行中' : '已结束';
      let ci = '';
      if (a.myCheckInResult === 'NORMAL') ci = '<span class="badge green">✅ 已正常打卡</span>';
      else if (a.myCheckInResult === 'ABNORMAL') ci = '<span class="badge red">⚠ 打卡位置异常</span>';
      else if (a.signedUp && phase === '已结束') ci = '<span class="badge gray">报名但未打卡</span>';
      return `
      <div class="act-card" data-id="${a.id}">
        <div style="display:flex;justify-content:space-between;gap:8px;align-items:flex-start">
          <b style="font-size:14.5px">${UI.esc(a.title)}</b>
          <span class="badge ${phase === '进行中' ? 'green' : 'gray'}">${phase}</span>
        </div>
        <div style="font-size:12.5px;color:var(--ink-secondary);margin-top:5px">${UI.esc(a.description || '')}</div>
        <div style="font-size:12.5px;color:var(--ink-muted);margin-top:5px">
          📍 ${UI.esc(a.locationName)}（打卡半径 ${a.radiusMeters} 米）<br/>
          🕒 ${UI.fmtTzFull(a.startAt, o.timezone)} ~ ${UI.fmtTzFull(a.endAt, o.timezone)}<br/>
          报名截止 ${UI.fmtTzFull(a.signupDeadline, o.timezone)} · 已报名 ${a.signupCount}${a.capacity ? '/' + a.capacity : ''} 人
        </div>
        <div style="margin-top:8px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
          ${a.signedUp ? '<span class="badge green">已报名</span>' : ''}
          ${ci}
          ${!a.signedUp && now < a.signupDeadline
            ? `<button class="btn sm primary" data-act="signup" data-id="${a.id}">手机报名</button>` : ''}
          ${a.signedUp && phase === '进行中' && !a.myCheckInResult
            ? `<button class="btn sm primary" data-act="checkin" data-id="${a.id}">📍 现场打卡</button>` : ''}
          ${a.myCheckInResult ? `<button class="btn sm" data-act="detail" data-id="${a.id}">我的打卡</button>` : ''}
        </div>
      </div>`;
    }).join('');

    slot.onclick = (e) => {
      const btn = e.target.closest('button[data-act]');
      if (!btn) return;
      const id = +btn.dataset.id;
      const a = list.find((x) => x.id === id);
      if (btn.dataset.act === 'signup') doSignup(a);
      if (btn.dataset.act === 'checkin') doCheckIn(a);
      if (btn.dataset.act === 'detail') showMyCheckIn(a);
    };

    async function doSignup(a) {
      try {
        await Api.post('/offender/activities/' + a.id + '/signup', {});
        UI.toast('报名成功，请到现场按时打卡', 'success');
        global.OffenderActivity.reload(o, root);
      } catch (e) { UI.toast(e.message, 'error'); }
    }

    async function doCheckIn(a) {
      const fix = global.OffenderFix.get();
      if (!fix) {
        UI.toast('请先在上方“今日报到”获取当前定位，再到现场打卡', 'warn');
        return;
      }
      const ageSec = Math.round((Date.now() - fix.fixTs) / 1000);
      if (ageSec > 300) {
        UI.toast('定位已超过 5 分钟，现场打卡必须用当前位置，请重新获取定位', 'error');
        return;
      }
      const btn = slot.querySelector(`button[data-act="checkin"][data-id="${a.id}"]`);
      if (btn) { btn.disabled = true; btn.textContent = '打卡提交中…'; }
      try {
        const res = await Api.post('/offender/activities/' + a.id + '/check-in', {
          fixTime: localIso(new Date(fix.fixTs)),
          lat: fix.lat,
          lng: fix.lng,
        });
        if (res.insideRange) {
          await UI.alertModal('✅ 现场打卡成功',
            `<div style="font-size:14px;line-height:1.8">定位在活动点范围内：
              <div style="margin-top:6px">距活动点 <b>${res.distanceMeters}</b> 米（半径 ${res.radiusMeters} 米）</div></div>`, '✅');
        } else {
          await UI.alertModal('⚠ 打卡位置异常',
            `<div style="font-size:14px;line-height:1.8">
              你当前定位<b style="color:var(--critical)">不在活动点范围内</b>，本次打卡已标记为
              <b style="color:var(--critical)">异常</b>并留痕，不能计为正常到场。
              <div style="margin-top:6px">距活动点 <b>${res.distanceMeters}</b> 米，超出半径 ${res.radiusMeters} 米。
              请立即前往「${UI.esc(a.locationName)}」后联系司法所说明（异常记录无法重复打卡覆盖）。</div></div>`, '⚠');
        }
        global.OffenderActivity.reload(o, root);
      } catch (e) {
        if (btn) { btn.disabled = false; btn.textContent = '📍 现场打卡'; }
        if (e.code === 'STALE_LOCATION' || e.code === 'NOT_SIGNED_UP'
            || e.code === 'CHECKIN_NOT_OPEN' || e.code === 'CHECKIN_CLOSED') {
          UI.alertModal('打卡未通过',
            `<div style="font-size:13.5px;line-height:1.7">${UI.esc(e.message)}</div>`, '⛔');
        } else {
          UI.toast(e.message, 'error');
        }
      }
    }

    function showMyCheckIn(a) {
      if (!a.myCheckIn) { UI.toast('暂无打卡记录', 'warn'); return; }
      const ci = a.myCheckIn;
      UI.alertModal(ci.insideRange ? '✅ 我的正常打卡' : '⚠ 我的异常打卡',
        `<div style="font-size:13.5px;line-height:1.8">
          结果：${ci.insideRange ? '正常（在活动点范围内）' : '<b style="color:var(--critical)">位置异常（不在活动点范围内）</b>'}<br/>
          距活动点：${ci.distanceMeters} 米 / 半径 ${ci.radiusMeters} 米<br/>
          坐标：${Number(ci.lat).toFixed(5)}, ${Number(ci.lng).toFixed(5)}<br/>
          定位时间：${UI.fmtTzFull(ci.fixTime, o.timezone)}${ci.alreadyChecked ? '<br/><span style="color:var(--ink-muted)">（重复操作，显示的是首次打卡记录）</span>' : ''}
        </div>`, ci.insideRange ? '✅' : '⚠');
    }
  }

  async function reload(o, root) { await mount(root, o); }

  global.OffenderActivity = { mount, reload };
})(window);
