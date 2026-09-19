package cn.sfj.jiaowutong.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 请销假逾期扫描调度器：每 60 秒触发一次 {@link LeaveService#processOverdue(Instant)}。
 * 独立成 Bean 以便业务服务在测试中直接调用处置方法，而不依赖 Spring 调度线程。
 */
@Component
public class LeaveOverdueScheduler {

    private final LeaveService leaveService;

    public LeaveOverdueScheduler(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 20_000L)
    public void scan() {
        leaveService.processOverdue(Instant.now());
    }
}
