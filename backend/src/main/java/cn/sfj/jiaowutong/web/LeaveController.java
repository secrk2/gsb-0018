package cn.sfj.jiaowutong.web;

import cn.sfj.jiaowutong.common.ApiResult;
import cn.sfj.jiaowutong.security.CurrentUserHolder;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.service.LeaveService;
import cn.sfj.jiaowutong.web.dto.LeaveReviewRequest;
import cn.sfj.jiaowutong.web.vo.LeaveView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 请销假两级审批（管理端）：
 * - GET  /leaves?stage=OFFICE|BUREAU|RETURNED|ACTIVE|ALL 审批工作台
 * - GET  /leaves/{id}                              单据详情 + 逐轮留痕
 * - POST /leaves/{id}/office-review                司法所初审（通过/退回）
 * - POST /leaves/{id}/bureau-review                区局复核（通过/退回）
 * - POST /leaves/{id}/return                       干警代对象销假
 */
@RestController
@RequestMapping("/api/leaves")
public class LeaveController {

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping
    public ApiResult<List<LeaveView>> queue(@RequestParam(defaultValue = "ALL") String stage) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(leaveService.queue(stage, user));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(leaveService.detail(id, user));
    }

    @PostMapping("/{id}/office-review")
    public ApiResult<LeaveView> officeReview(@PathVariable Long id,
                                             @Valid @RequestBody LeaveReviewRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(
                leaveService.officeReview(id, request.approve(), request.comment(), user));
    }

    @PostMapping("/{id}/bureau-review")
    public ApiResult<LeaveView> bureauReview(@PathVariable Long id,
                                             @Valid @RequestBody LeaveReviewRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(
                leaveService.bureauReview(id, request.approve(), request.comment(), user));
    }

    @PostMapping("/{id}/return")
    public ApiResult<LeaveView> returnLeave(@PathVariable Long id,
                                            @RequestBody(required = false) cn.sfj.jiaowutong.web.dto.LeaveReturnRequest request) {
        LoginUser user = CurrentUserHolder.require();
        String note = request == null ? null : request.note();
        return ApiResult.success(leaveService.returnFromLeave(id, note, user));
    }
}
