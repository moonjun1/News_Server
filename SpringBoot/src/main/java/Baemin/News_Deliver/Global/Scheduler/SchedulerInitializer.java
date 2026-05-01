package Baemin.News_Deliver.Global.Scheduler;

import Baemin.News_Deliver.Domain.Kakao.Exception.KakaoException;
import Baemin.News_Deliver.Domain.Kakao.service.KakaoSchedulerService;
import Baemin.News_Deliver.Domain.Mypage.DTO.SettingDTO;
import Baemin.News_Deliver.Domain.Mypage.Entity.Setting;
import Baemin.News_Deliver.Domain.Mypage.Repository.SettingRepository;
import Baemin.News_Deliver.Domain.Mypage.service.SettingService;
import Baemin.News_Deliver.Global.Exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code SchedulerInitializer}는 서버 시작 시 또는 수동 트리거 시
 * 모든 사용자 {@link Setting} 정보를 기반으로 개별 스케줄을 등록하는 클래스입니다.
 *
 * <p>ElasticSearch 뉴스 배치, 사용자 맞춤 뉴스 발송 등의 스케줄 등록 작업을 담당합니다.</p>
 *
 * <p>현재는 {@code @PostConstruct}를 통해 서버 시작 시 자동으로 실행됩니다.</p>
 *
 * @author 배세정
 */

@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerInitializer { //서버시작시 또는 특정 시간에 모든 셋팅값을 가져옴.
    private final SettingService settingService;
    private final TaskSchedulerService taskSchedulerService;
    private final KakaoSchedulerService kakaoSchedulerService;
    private final SettingRepository settingRepository;
    //private final BatchSchedulerService batchSchedulerService;

    /**
     * 서버 시작 시 전체 스케줄 초기화(테스트용)
     */
    @PostConstruct
    public void init() {

        //batchSchedulerService.scheduleNewsBatch();

        //DB 없을 시 대체 오류 확인
//        if (isSettingTableAvailable()) {
//            scheduleAllUserSettings();
//        } else {
//            log.warn("[SchedulerInit] setting 테이블이 없어 스케줄러를 실행하지 않습니다.");
//        }

    }

    /**
     * 모든 사용자 설정(Setting)을 조회하여, 개별 스케줄을 등록합니다.
     *
     * <p>이 메서드는 서버 시작 시 또는 수동으로 호출되어,
     * {@link TaskSchedulerService#scheduleUser(Setting)}를 통해 각각의 사용자에 대한 크론을 등록합니다.</p>
     */
    @PostConstruct
    public void scheduleAllUserSettings() {
        List<Setting> settings = settingService.getAllSettings();

        //DB에 settings값이 없을 때 스케쥴러 취소 코드
        if (settings == null || settings.isEmpty()) {
            log.warn("[SchedulerInit] 등록할 Setting이 없어 스케줄러를 실행하지 않습니다.");
            // throw new KakaoException(ErrorCode.SETTING_NOT_FOUND);
        }

        for (Setting setting : settings) {
            try {
                //셋팅값 반환
                taskSchedulerService.scheduleUser(setting);

            } catch (Exception e) {
                log.error("[SchedulerInit] 유저 {} / setting {} 에 대한 크론 등록 중 예외 발생: {}", setting.getUser().getId(), e.getMessage(), e);
                throw new KakaoException(ErrorCode.SETTING_CRON_FAILED);
            }
        }
    }

    //테이블 존재 확인용 코드
    private boolean isSettingTableAvailable() {
        try {
            settingRepository.count();
            return true;
        } catch (Exception e) {
            log.warn("[SchedulerInit] 테이블 확인 중 오류 발생: {}", e.getMessage());
            return false;
        }
    }

}
