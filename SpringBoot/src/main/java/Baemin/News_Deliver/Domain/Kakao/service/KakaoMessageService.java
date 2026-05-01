package Baemin.News_Deliver.Domain.Kakao.service;

import Baemin.News_Deliver.Domain.Kakao.Helper.KakaoMessageHelper;
import Baemin.News_Deliver.Domain.Kakao.Manager.KakaoMessageManager;
import Baemin.News_Deliver.Domain.Mypage.DTO.SettingDTO;
import Baemin.News_Deliver.Domain.Mypage.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoMessageService {

    private final SettingService settingService;
    private final KakaoMessageManager kakaoMessageManager;

    /**
     * 유저의 세팅값에 맞는 뉴스를 추출 후, 카카오 메시지로 전송하는 메서드
     *
     * @author 배세정
     * @param refreshAccessToken 유저의 리프레시 토큰
     * @param userId 유저의 고유 번호
     */
    public void sendKakaoMessage(String refreshAccessToken, Long userId) {

        // 유저의 리프레시 토큰에서 엑세스 토큰을 발급
        String accessToken = kakaoMessageManager.getKakaoUserAccessToken(refreshAccessToken, userId);

        // 유저의 모든 세팅(유효한) 조회
        List<SettingDTO> settings = settingService.getAllSettingsByUserId(userId);

        // 현재 시간 측정(분단위 측정)
        LocalTime nowTime = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);

        // 현재 시간 기준 뉴스 받아야 할 유저 리스트 필터링
        List<SettingDTO> currentSettings = KakaoMessageHelper.filterCurrentSettings(settings, nowTime);

        // 현재 시간에 유저에게 발송할 세팅이 있는지 확인
        KakaoMessageHelper.checkCurrentSetting_Exist(currentSettings,nowTime);

        // 뉴스 검색 → 뉴스 저장 → 메시지 발송까지의 전 과정을 처리
        for (SettingDTO setting : currentSettings) {
            kakaoMessageManager.processSetting(accessToken, setting);
        }

    }
}