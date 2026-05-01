package Baemin.News_Deliver.Domain.Kakao.service;

import Baemin.News_Deliver.Domain.Mypage.DTO.SettingDTO;
import Baemin.News_Deliver.Domain.Mypage.Entity.Days;
import Baemin.News_Deliver.Domain.Mypage.Entity.Setting;
import Baemin.News_Deliver.Domain.Mypage.Repository.SettingRepository;
import Baemin.News_Deliver.Domain.Mypage.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code KakaoSchedulerService}는 사용자 설정(Setting)을 기반으로
 * 스케줄링을 위한 Cron 표현식을 생성하는 서비스입니다.
 * <p>
 * Cron 표현식은 스케줄러(TaskScheduler)에서 사용되며,
 * 설정된 요일(Days)과 시간(DeliveryTime)을 기반으로 생성됩니다.
 *
 * @author 배세정
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoSchedulerService {

    private final SettingService settingService;

    /**
     * 요일 번호(1~7)를 영문 요일 문자열로 매핑하는 정적 Map입니다.
     * 1: 월요일(MON), 7: 일요일(SUN)
     */
    //1(월)~7(일)로 매핑
    private static final Map<Integer, String> DAY_MAP = Map.of(
            1, "MON",
            2, "TUE",
            3, "WED",
            4, "THU",
            5, "FRI",
            6, "SAT",
            7, "SUN"
    );

    /**
     * 주어진 {@link Setting} 객체로부터 Cron 표현식을 생성하여 반환합니다.
     *
     * @param setting {@link Setting} 사용자 설정 객체 (시간 및 요일 포함)
     * @return Cron 형식의 문자열 (예: {@code 0 30 9 ? * MON,WED,FRI})
     */
    public String getCron(Setting setting) {
        LocalDateTime deliveryTime = setting.getDeliveryTime();
        List<Days> days = setting.getDays();

        log.info("deliveryTime: {}", deliveryTime);
        log.info("days: {}", days);

        String cron = toCron(deliveryTime, days);
        log.info("생성된 Cron 표현식: {}", cron);
        return cron;
    }

    /**
     * 시간 및 요일 정보를 기반으로 Cron 표현식을 생성합니다.
     *
     * @param deliveryTime 뉴스 전달 시간
     * @param days         전달 요일 리스트
     * @return Cron 형식의 문자열 (초 분 시 ? * 요일들)
     */
    private String toCron(LocalDateTime deliveryTime, List<Days> days) {
        int hour = deliveryTime.getHour();
        int minute = deliveryTime.getMinute();

        String cronDays = days.stream()
                .map(day -> DAY_MAP.get(day.getDeliveryDay()))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));

        return String.format("0 %d %d ? * %s", minute, hour, cronDays);
    }


}
