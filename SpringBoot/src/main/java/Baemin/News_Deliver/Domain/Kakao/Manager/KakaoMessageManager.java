package Baemin.News_Deliver.Domain.Kakao.Manager;

import Baemin.News_Deliver.Domain.Kakao.Exception.KakaoException;
import Baemin.News_Deliver.Domain.Kakao.Helper.KakaoMessageHelper;
import Baemin.News_Deliver.Domain.Kakao.entity.History;
import Baemin.News_Deliver.Domain.Kakao.repository.HistoryRepository;
import Baemin.News_Deliver.Domain.Kakao.repository.NewsRepository;
import Baemin.News_Deliver.Domain.Kakao.service.KakaoNewsService;
import Baemin.News_Deliver.Domain.Mypage.DTO.SettingDTO;
import Baemin.News_Deliver.Domain.Mypage.Entity.Setting;
import Baemin.News_Deliver.Domain.Mypage.Repository.SettingRepository;
import Baemin.News_Deliver.Domain.Mypage.service.SettingService;
import Baemin.News_Deliver.Global.Exception.ErrorCode;
import Baemin.News_Deliver.Global.Kakao.KakaoTokenProvider;
import Baemin.News_Deliver.Global.News.Batch.entity.News;
import Baemin.News_Deliver.Global.News.ElasticSearch.dto.NewsEsDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoMessageManager {

    private static final String KAKAO_SEND_TOME_URL = "https://kapi.kakao.com/v2/api/talk/memo/send";
    private final RestTemplate restTemplate = new RestTemplate();

    private final KakaoTokenProvider kakaoTokenProvider;
    private final KakaoNewsService kakaoNewsService;
    private final SettingService settingService;
    private final KakaoNewsService newsService;

    private final NewsRepository newsRepository;
    private final SettingRepository settingRepository;
    private final HistoryRepository historyRepository;

    /**
     * 카카오 사용자 Access Token을 Refresh Token을 이용해 갱신 후 반환하는 메서드
     *
     * @param refreshAccessToken 사용자의 카카오 Refresh Token
     * @param userId 사용자 고유 ID
     * @return 갱신된 Access Token 문자열
     * @throws RuntimeException Access Token을 가져오지 못했을 경우
     */
    public String getKakaoUserAccessToken(String refreshAccessToken, Long userId) {

        // 유저의 refresh 토큰을 통해 access 토큰 추출
        String accessToken = kakaoTokenProvider.refreshAccessToken(refreshAccessToken);
        if (accessToken == null || accessToken.isEmpty()) {
            throw new KakaoException(ErrorCode.KAKAO_TOKEN_ACCESS_FAILED);
        }

        // 서버 로그 기록
        log.info("[카카오 메시지 전송 서비스] : refreshAccessToken({})을 통해, accessToken({}) 추출 완료", refreshAccessToken,accessToken);

        getNewsEsDocumentList_Fixed(userId);
        return accessToken;
    }

    /**
     * 사용자의 키워드를 바탕으로 뉴스를 검색하여 리스트로 반환하는 메서드
     *
     * @param userId 유저의 고유 번호
     */
    public void getNewsEsDocumentList_Fixed(Long userId) {

        List<SettingDTO> settings = settingService.getAllSettingsByUserId(userId);
        List<NewsEsDocument> totalNewsList = new ArrayList<>();

        for (SettingDTO setting : settings) {
            log.info("셋팅값 확인용 코드 : " + setting.getSettingKeywords());
            log.info("셋팅 제외 확인용 코드 : " + setting.getBlockKeywords());

            List<String> keywords = setting.getSettingKeywords(); // 예: [이재명]
            List<String> blockKeywords = setting.getBlockKeywords(); // 예: [한국, 중국]

            if (keywords == null || keywords.isEmpty()) {
                log.warn("세팅에 키워드가 없습니다. 스킵합니다.");
                continue;
            }

            List<NewsEsDocument> newsList = newsService.searchNewsWithFallback(keywords, blockKeywords);

            log.info(">> 세팅당 검색된 뉴스 수: {}", newsList.size());

            // 세팅당 5개만 취하고 싶다면 limit 적용
            if (newsList.size() > 5) {
                newsList = newsList.subList(0, 5);
            }

            // 뉴스 히스토리 저장
            saveHistory(newsList, List.of(setting));

            totalNewsList.addAll(newsList);
        }

        log.info("✅ 전체 검색된 뉴스 총합: {}", totalNewsList.size());
    }

    /**
     * 전송된 뉴스 정보를 히스토리로 저장합니다. 중복 뉴스는 저장하지 않습니다.
     *
     * 히스토리 저장 시 히스토리 조회 캐시 삭제
     *
     * @param newsList 뉴스 리스트
     * @param settings 해당 뉴스에 적용된 사용자 설정들
     * @return 저장이 이루어진 경우 true, 아무 것도 저장되지 않았으면 false
     */
    @CacheEvict(value = "groupedNewsHistory", allEntries = true)
    public boolean saveHistory(List<NewsEsDocument> newsList, List<SettingDTO> settings) {
        if (newsList == null || newsList.isEmpty()) {
            log.warn("해당 키워드로 검색된 뉴스가 없습니다.");
            throw new KakaoException(ErrorCode.NO_NEWS_DATA);
        }

        // 중복 저장 방지용 플래그
        boolean saved = false;

        for (NewsEsDocument newsDoc : newsList) {

            /* DB와 ES 동기화 되어있지 않을 시, 예외 */
            News newsitem = newsRepository.findById(Long.parseLong(newsDoc.getId()))
                    .orElse(null);
            if (newsitem == null) {
                log.warn("DB에 존재하지 않는 뉴스입니다. id={}", newsDoc.getId());
                continue; // 이 뉴스는 히스토리 저장 생략
            }

            for (SettingDTO settingDTO : settings) {
                Setting setting = settingRepository.findById(settingDTO.getId())
                        .orElseThrow(() -> new KakaoException(ErrorCode.SETTING_NOT_FOUND));

                // 중복 저장 방지용 코드
                boolean exists = historyRepository.existsBySettingAndNews(setting, newsitem);
                if (exists) {
                    log.info("이미 저장된 뉴스입니다. (settingId={}, newsId={})", setting.getId(), newsitem.getId());
                    continue;
                }

                History history = History.builder()
                        .publishedAt(LocalDateTime.now())
                        .setting(setting)
                        .news(newsitem)
                        .settingKeyword(String.join(",", settingDTO.getSettingKeywords()))
                        .blockKeyword(
                                settingDTO.getBlockKeywords() != null ? String.join(",", settingDTO.getBlockKeywords())
                                        : null)
                        .build();

                historyRepository.save(history);
                saved = true;
            }
        }

        return saved;
    }

    /**
     * 개별 카카오톡 전송 메서드
     *
     * @param accessToken 유저 엑세스 토큰
     * @param newsList    뉴스 리스트
     * @return T,F
     */
    @CacheEvict(value = "groupedNewsHistory", allEntries = true)
    public boolean sendSingleKakaoMessage(String accessToken, List<NewsEsDocument> newsList) {
        try {

            /* 헤더 설정 */
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Authorization", "Bearer " + accessToken);

            /* 템플릿 설정 */
            Map<String, String> templateArgs = KakaoMessageHelper.createTemplateData(newsList);
            ObjectMapper objectMapper = new ObjectMapper();
            String templateArgsJson = objectMapper.writeValueAsString(templateArgs);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            // params.add("template_id", "122080");
            params.add("template_id", "122693");
            params.add("template_args", templateArgsJson);

            /* 세팅 별 개별 메시지 전송 */
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(KAKAO_SEND_TOME_URL, entity, String.class);
            log.info("카카오 메시지 전송 응답: {}", response.getBody());

            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.error("카카오 메시지 전송 실패: ", e);
            return false;
        }
    }

    /**
     * 메시지 발송을 과정 프로세스 관리 메서드
     *
     * @param accessToken 유저의 엑세스 토큰
     * @param setting 유저 세팅
     * @return T,F
     */
    public void processSetting(String accessToken, SettingDTO setting) {

        // 뉴스 검색
        List<NewsEsDocument> newsList = kakaoNewsService.searchNewsWithFallback(
                setting.getSettingKeywords(), setting.getBlockKeywords());

        // 뉴스가 겁색 되지 않을 시의 예외 처리
        if (newsList == null || newsList.isEmpty()) {
            log.info("[스킵] : 세팅 ID {}에 해당하는 뉴스가 검색되지 않음", setting.getId());
        }

        // 뉴스는 최대 5개로 제한
        if (newsList.size() > 5) {
            newsList = newsList.subList(0, 5);
        }

        // 히스토리에 저장
        saveHistory(newsList, List.of(setting));

        // 유저에게 전송
        sendSingleKakaoMessage(accessToken, newsList);
    }

}
