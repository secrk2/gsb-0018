package cn.sfj.jiaowutong.web;

import cn.sfj.jiaowutong.common.ApiResult;
import cn.sfj.jiaowutong.security.CurrentUserHolder;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.service.ActivityService;
import cn.sfj.jiaowutong.web.dto.ActivityPublishRequest;
import cn.sfj.jiaowutong.web.vo.ActivityView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 公益活动管理端（干警/监管员）：
 * - GET  /activities        活动列表（含报名人数）
 * - GET  /activities/{id}   活动详情 + 报名/打卡花名册（异常打卡逐条可见）
 * - POST /activities        发布活动（活动点坐标 + 打卡半径）
 */
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
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

    @PostMapping
    public ApiResult<ActivityView> publish(@Valid @RequestBody ActivityPublishRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(activityService.publish(
                request.title(), request.description(), request.officeId(),
                request.locationName(), request.lat(), request.lng(), request.radiusMeters(),
                request.startAt(), request.endAt(), request.signupDeadline(), request.capacity(), user));
    }
}
