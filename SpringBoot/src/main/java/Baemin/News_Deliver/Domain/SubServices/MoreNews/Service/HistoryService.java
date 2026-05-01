package Baemin.News_Deliver.Domain.SubServices.MoreNews.Service;

import Baemin.News_Deliver.Domain.Auth.Service.AuthService;
import Baemin.News_Deliver.Domain.Kakao.entity.History;
import Baemin.News_Deliver.Domain.Kakao.repository.HistoryRepository;
import Baemin.News_Deliver.Domain.SubServices.FeedBack.Entity.Feedback;
import Baemin.News_Deliver.Domain.SubServices.FeedBack.Repository.FeedbackRepository;
import Baemin.News_Deliver.Domain.SubServices.MoreNews.DTO.GroupedNewsHistoryResponse;
import Baemin.News_Deliver.Domain.SubServices.MoreNews.DTO.NewsHistoryResponse;
import Baemin.News_Deliver.Domain.SubServices.MoreNews.DTO.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final HistoryRepository historyRepository;
    private final FeedbackRepository feedbackRepository;
    private final AuthService authService;

    /**
     * 내 히스토리 조회하기 메서드
     *
     * @param page 현재 페이지 번호 (0부터 시작)
     * @param size 페이지당 아이템 수
     * @param authentication 로그인 인증 객체 (카카오 ID 포함)
     * @return 페이지 정보 + 그룹핑된 뉴스 히스토리 데이터
     */
    @Cacheable(
            value = "groupedNewsHistory",                    // Redis에서 사용할 캐시 이름
            key = "'user:' + #authentication.name + ':page:' + #page + ':size:' + #size", // 캐시 Key
            cacheManager = "redisCacheManager"
    )
    public PageResponse<GroupedNewsHistoryResponse> getGroupedNewsHistory(int page, int size, Authentication authentication) {

        /* 캐시 미스 로그 */
        log.info("[Cache Miss] {}번 유저 내 히스토리 조회 시 캐시 미스 : DB를 조회합니다.",authentication.getName());

        // 1. 인증 객체에서 카카오 ID 추출 → 유저 ID 조회
        String kakaoId = authentication.getName();
        Long userId = authService.findByKakaoId(kakaoId).getId();

        // 2. 해당 유저가 수신한 모든 뉴스 히스토리 조회
        List<History> allHistories = historyRepository.findAllBySetting_User_Id(userId);

        // 3. 히스토리 ID 리스트 생성
        List<Long> historyIds = allHistories.stream()
                .map(History::getId)
                .toList();

        // 4. 피드백 정보를 미리 조회하여 Map<HistoryId, Feedback>으로 변환
        Map<Long, Feedback> feedbackMap = feedbackRepository.findAllById(historyIds)
                .stream()
                .collect(Collectors.toMap(fb -> fb.getHistory().getId(), fb -> fb));

        // 5. 히스토리를 "설정 ID + 시간(시 단위)" 기준으로 그룹핑
        Map<String, List<History>> grouped = allHistories.stream()
                .collect(Collectors.groupingBy(h -> {
                    Long settingId = h.getSetting().getId();
                    LocalDateTime truncatedPublishedAt = h.getPublishedAt().truncatedTo(ChronoUnit.HOURS);
                    return settingId + "_" + truncatedPublishedAt;
                }));

        // 6. 그룹핑된 데이터 가공 → GroupedNewsHistoryResponse로 변환
        List<GroupedNewsHistoryResponse> groupedList = grouped.entrySet().stream()
                .map(entry -> {
                    List<History> histories = entry.getValue();
                    History any = histories.get(0); // 대표 히스토리 하나 선택

                    // 각 뉴스 히스토리마다 Feedback과 함께 DTO로 변환
                    List<NewsHistoryResponse> newsResponses = histories.stream()
                            .map(history -> {
                                Feedback feedback = feedbackMap.get(history.getId());
                                return NewsHistoryResponse.from(history, feedback);
                            })
                            .toList();

                    // 그룹 단위로 응답 객체 생성
                    return GroupedNewsHistoryResponse.builder()
                            .settingId(any.getSetting().getId())
                            .publishedAt(any.getPublishedAt().truncatedTo(ChronoUnit.HOURS))
                            .settingKeyword(any.getSettingKeyword())
                            .blockKeyword(any.getBlockKeyword())
                            .newsList(newsResponses)
                            .build();
                })
                // 최신순으로 정렬 (publishedAt 기준)
                .sorted(Comparator.comparing(GroupedNewsHistoryResponse::getPublishedAt).reversed())
                .toList();

        // 7. 페이지네이션 처리 (subList 사용)
        int total = groupedList.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, total);

        // 요청한 페이지 범위를 벗어난 경우 빈 리스트 반환
        List<GroupedNewsHistoryResponse> paginated = fromIndex >= total
                ? Collections.emptyList()
                : groupedList.subList(fromIndex, toIndex);

        // 8. 최종 응답 DTO 반환 (페이지 정보 포함)
        return PageResponse.<GroupedNewsHistoryResponse>builder()
                .data(paginated)
                .currentPage(page)
                .pageSize(size)
                .totalPages((int) Math.ceil((double) total / size))
                .totalElements(total)
                .build();
    }


}
