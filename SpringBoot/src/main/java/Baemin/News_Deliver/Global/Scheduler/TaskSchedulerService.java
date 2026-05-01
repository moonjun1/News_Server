package Baemin.News_Deliver.Global.Scheduler;

import Baemin.News_Deliver.Domain.Auth.Entity.Auth;
import Baemin.News_Deliver.Domain.Auth.Entity.User;
import Baemin.News_Deliver.Domain.Auth.Repository.AuthRepository;
import Baemin.News_Deliver.Domain.Auth.Repository.UserRepository;
import Baemin.News_Deliver.Domain.Kakao.Exception.KakaoException;
import Baemin.News_Deliver.Domain.Kakao.service.KakaoMessageService;
import Baemin.News_Deliver.Domain.Kakao.service.KakaoSchedulerService;
import Baemin.News_Deliver.Domain.Mypage.Entity.Setting;
import Baemin.News_Deliver.Domain.Mypage.Repository.SettingRepository;
import Baemin.News_Deliver.Global.Exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * {@code TaskSchedulerService}는 사용자 설정(Setting)에 따라
 * 동적으로 카카오 뉴스 메시지를 전송할 스케줄을 등록/취소하는 서비스입니다.
 *
 * <p>
 * Spring의 {@link TaskScheduler}를 사용하여 사용자별로 개별 {@link ScheduledFuture} 작업을
 * 관리합니다.
 * 각 작업은 userId-settingId 조합으로 유니크하게 식별됩니다.
 * </p>
 *
 * <p>
 * 기능 요약:
 * </p>
 * <ul>
 * <li>사용자 설정에 따른 cron 스케줄 등록</li>
 * <li>기존 스케줄 취소 및 갱신</li>
 * <li>스케줄 실행 시 카카오 메시지 발송 트리거</li>
 * </ul>
 *
 * @author 배세정
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskSchedulerService {

    @Qualifier("customTaskScheduler") // 커스텀한 테스크 스케쥴러로 등록함.
    private final TaskScheduler taskScheduler;

    private final KakaoMessageService kakaoMessageService;
    private final UserRepository userRepository;
    private final AuthRepository authRepository;
    private final SettingRepository settingRepository;

    // userId-settingId 조합으로 key 관리
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final KakaoSchedulerService kakaoSchedulerService;

    /**
     * 주어진 {@link Setting}을 기반으로 동적 스케줄을 등록합니다.
     *
     * <p>
     * 1. 설정 정보에서 cron 표현식 생성<br>
     * 2. 기존 스케줄이 있을 경우 취소 후 재등록<br>
     * 3. 해당 시간마다 {@code KakaoMessageService.sendKakaoMessage()} 실행
     * </p>
     *
     * @param setting {@link Setting} 사용자 알림 설정 객체
     * @throws IllegalArgumentException 유저 정보 또는 토큰이 존재하지 않을 경우
     */
    @Transactional
    public void scheduleUser(Setting setting) {
        Long settingId = setting.getId();

        setting = settingRepository.findByIdWithDays(settingId)
                .orElseThrow(() -> new KakaoException(ErrorCode.SETTING_NOT_FOUND));

        Long userId = setting.getUser().getId();
        String taskKey = generateTaskKey(userId, settingId);

        /* 이미 등록된 경우 기존 스케줄 취소 */
        if (scheduledTasks.containsKey(taskKey)) {
            cancelUser(userId, settingId);
        }

        log.info("[Scheduler] 유저 {} / setting {} 토큰 확인 중 - {}", userId, settingId, LocalDateTime.now());

        String refreshAccessToken = userRepository.findById(userId)
                .flatMap(user -> authRepository.findByUser(user)
                        .map(Auth::getKakaoRefreshToken))
                .orElseThrow(() -> new KakaoException(ErrorCode.OAUTH2_PROCESS_FAILED));

        log.info("[Scheduler] 토큰 확인 완료 - {}", refreshAccessToken);

        String cron = kakaoSchedulerService.getCron(setting);

        Runnable task = () -> {
            log.info("[Scheduler] 유저 {} / setting {} 메시지 발송 트리거 - {}", userId, settingId, LocalDateTime.now());

            try {

                Optional<Setting> optionalSetting = settingRepository.findByIdWithDays(settingId);

                if (optionalSetting.isEmpty()) {
                    log.warn("[Scheduler] 유저 {} / setting {} 설정 정보가 없음", userId, settingId);
                    throw new KakaoException(ErrorCode.SETTING_NOT_FOUND);
                }

                kakaoMessageService.sendKakaoMessage(refreshAccessToken, userId);

            } catch (Exception e) {
                log.error("[Scheduler] 유저 {} / setting {} 메시지 발송 중 예외 발생: {}", userId, settingId, e.getMessage(), e);
                throw new KakaoException(ErrorCode.MESSAGE_SEND_FAILED);
            }
        };

        CronTrigger trigger = new CronTrigger(cron);
        ScheduledFuture<?> future = taskScheduler.schedule(task, trigger);
        scheduledTasks.put(taskKey, future);

        log.info("[Scheduler] 유저 {} / setting {} 스케줄 등록 완료 (cron: {})", userId, settingId, cron);
    }

    /**
     * 사용자 ID와 설정 ID에 해당하는 스케줄을 취소합니다.
     *
     * @param userId    사용자 ID
     * @param settingId 설정 ID
     */
    public void cancelUser(Long userId, Long settingId) {
        String taskKey = generateTaskKey(userId, settingId);
        ScheduledFuture<?> future = scheduledTasks.get(taskKey);
        if (future != null) {
            future.cancel(false);
            scheduledTasks.remove(taskKey);
            log.info("[Scheduler] 유저 {} / setting {} 스케줄 취소됨", userId, settingId);
        }
    }

    /**
     * 스케줄 작업 고유 키를 생성합니다.
     *
     * @param userId    사용자 ID
     * @param settingId 설정 ID
     * @return userId-settingId 형식의 문자열 키
     */
    // 중복 스케쥴러 삭제 메서드용
    private String generateTaskKey(Long userId, Long settingId) {
        return userId + "-" + settingId;
    }

}
