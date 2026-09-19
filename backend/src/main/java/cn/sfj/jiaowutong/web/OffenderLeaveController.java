package cn.sfj.jiaowutong.web;

import cn.sfj.jiaowutong.common.ApiResult;
import cn.sfj.jiaowutong.security.CurrentUserHolder;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.service.LeaveService;
import cn.sfj.jiaowutong.web.dto.LeaveApplyRequest;
import cn.sfj.jiaowutong.web.dto.LeaveReturnRequest;
import cn.sfj.jiaowutong.web.vo.LeaveView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 矫正对象手机端·请销假：
 * - POST /offender/leaves              提交请假申请
 * - GET  /offender/leaves              我的请假单（逐轮留痕在详情中）
 * - GET  /offender/leaves/{id}         单据详情 + 审批留痕
 * - POST /offender/leaves/{id}/resubmit 退回后修改重提
 * - POST /offender/leaves/{id}/return    销假返所
 */
@RestController
@RequestMapping("/api/offender/leaves")
public class OffenderLeaveController {

    private final LeaveService leaveService;

    public OffenderLeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @PostMapping
    public ApiResult<LeaveView> apply(@Valid @RequestBody LeaveApplyRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(leaveService.apply(
                request.destination(), request.reason(), request.startAt(), request.endAt(), user));
    }

    @GetMapping
    public ApiResult<List<LeaveView>> mine() {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(leaveService.mine(user));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(leaveService.detail(id, user));
    }

    @PostMapping("/{id}/resubmit")
    public ApiResult<LeaveView> resubmit(@PathVariable Long id,
                                         @Valid @RequestBody LeaveApplyRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(leaveService.resubmit(
                id, request.destination(), request.reason(), request.startAt(), request.endAt(), user));
    }

    @PostMapping("/{id}/return")
    public ApiResult<LeaveView> returnLeave(@PathVariable Long id,
                                            @RequestBody(required = false) LeaveReturnRequest request) {
        LoginUser user = CurrentUserHolder.require();
        String note = request == null ? null : request.note();
        return ApiResult.success(leaveService.returnFromLeave(id, note, user));
    }
}
