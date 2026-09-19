package cn.sfj.jiaowutong.web;

import cn.sfj.jiaowutong.common.ApiResult;
import cn.sfj.jiaowutong.security.CurrentUserHolder;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.service.ActivityService;
import cn.sfj.jiaowutong.web.dto.ActivityCheckInRequest;
import cn.sfj.jiaowutong.web.dto.ActivitySignupRequest;
import cn.sfj.jiaowutong.web.vo.ActivityCheckInView;
import cn.sfj.jiaowutong.web.vo.ActivityView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 矫正对象手机端·公益活动：
 * - GET  /offender/activities          本所活动列表（含本人报名/打卡状态）
 * - GET  /offender/activities/{id}     活动详情
 * - POST /offender/activities/{id}/signup  手机报名（幂等）
 * - POST /offender/activities/{id}/check-in 现场打卡（服务端半径校验，范围外标异常）
 */
@RestController
@RequestMapping("/api/offender/activities")
public class OffenderActivityController {

    private final ActivityService activityService;

    public OffenderActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    public ApiResult<List<ActivityView>> list() {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(activityService.list(user));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(activityService.detail(id, user));
    }

    @PostMapping("/{id}/signup")
    public ApiResult<ActivityView> signup(@PathVariable Long id,
                                          @RequestBody(required = false) ActivitySignupRequest request) {
        LoginUser user = CurrentUserHolder.require();
        String note = request == null ? null : request.note();
        return ApiResult.success(activityService.signup(id, note, user));
    }

    @PostMapping("/{id}/check-in")
    public ApiResult<ActivityCheckInView> checkIn(@PathVariable Long id,
                                                  @Valid @RequestBody ActivityCheckInRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(activityService.checkIn(
                id, request.fixTime(), request.lat(), request.lng(), user));
    }
}
