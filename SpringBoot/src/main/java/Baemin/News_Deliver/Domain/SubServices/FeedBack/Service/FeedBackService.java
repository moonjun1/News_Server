package Baemin.News_Deliver.Domain.SubServices.FeedBack.Service;

import Baemin.News_Deliver.Domain.Kakao.entity.History;
import Baemin.News_Deliver.Domain.Kakao.repository.HistoryRepository;
import Baemin.News_Deliver.Domain.SubServices.Exception.SubServicesException;
import Baemin.News_Deliver.Domain.SubServices.FeedBack.DTO.FeedbackRequest;
import Baemin.News_Deliver.Domain.SubServices.FeedBack.Entity.Feedback;
import Baemin.News_Deliver.Domain.SubServices.FeedBack.Repository.FeedbackRepository;
import Baemin.News_Deliver.Global.Exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedBackService {

    private final FeedbackRepository feedbackRepository;
    private final HistoryRepository historyRepository;

    // ======================= 키워드 반영도 피드백 메서드 =========================

    /**
     * 키워드 반영도 피드백 메서드
     *
     * @param request 피드백 요청 DTO
     * @return 피드백 결과 반환
     */
    @CacheEvict(value = "groupedNewsHistory", allEntries = true)
    public Long keywordFeedBack(FeedbackRequest request){

        log.info("[CacheEvict] 피드백 API 호출로 인한, 히스토리 캐시 삭제");

        /* 피드백 객체 반환 */
        Optional<Feedback> optionalFeedback = feedbackRepository.findById(request.getHistoryId());

        /* 히스토리 객체 반환 */
        History history = historyRepository.findById(request.getHistoryId())
                .orElseThrow(() -> new SubServicesException(ErrorCode.HISTORY_NOT_FOUND));

        if (optionalFeedback.isPresent()) {

            /* 키워드 반영도에 따른 Actoin 분류*/
            Feedback feedback = optionalFeedback.get();
            Long currentValue = feedback.getKeywordReflection(); // 현재 저장된 값
            Long requestValue = request.getFeedbackValue(); // 유저가 누른 값 (1 또는 -1)

            if (currentValue == null)
                currentValue = 0L;
            if (currentValue.equals(requestValue))
                feedback.setKeywordReflection(0L); // 같은 값이면 취소 → 0
             else
                feedback.setKeywordReflection(requestValue); // 반대 값이거나 기존이 0 → 새 값 반영

            feedbackRepository.save(feedback); // 저장

            return feedback.getKeywordReflection();
        } else {

            /* null일때, 눌렀으면 입력된 값으로 수정 */
            Feedback feedback = Feedback.builder()
                    .history(history)
                    .keywordReflection(request.getFeedbackValue())
                    .contentQuality(null)
                    .build();
            feedbackRepository.save(feedback);
            return feedback.getKeywordReflection();
        }

    }

    // ======================= 콘텐츠 품질 피드백 메서드 =========================

    /**
     * 콘텐츠 품질 피드백 메서드
     *
     * @param request 피드백 요청 DTO
     * @return 피드백 결과 반환
     */
    @CacheEvict(value = "groupedNewsHistory", allEntries = true)
    public Long contentQualityFeedback(FeedbackRequest request) {

        log.info("[CacheEvict] 피드백 API 호출로 인한, 히스토리 캐시 삭제");

        // 피드백 조회
        Optional<Feedback> optionalFeedback = feedbackRepository.findById(request.getHistoryId());

        // 히스토리 조회
        History history = historyRepository.findById(request.getHistoryId())
                .orElseThrow(() -> new SubServicesException(ErrorCode.HISTORY_NOT_FOUND));

        if (optionalFeedback.isPresent()) {
            Feedback feedback = optionalFeedback.get();
            Long currentValue = feedback.getContentQuality(); // 현재 값
            Long requestValue = request.getFeedbackValue();   // 요청 값

            if (currentValue == null) currentValue = 0L;

            if (currentValue.equals(requestValue)) {
                feedback.setContentQuality(0L); // 같은 값이면 취소
            } else {
                feedback.setContentQuality(requestValue); // 다른 값이면 반영
            }

            feedbackRepository.save(feedback);
            return feedback.getContentQuality();

        } else {
            Feedback feedback = Feedback.builder()
                    .history(history)
                    .keywordReflection(null)
                    .contentQuality(request.getFeedbackValue())
                    .build();
            feedbackRepository.save(feedback);
            return feedback.getContentQuality();
        }
    }

}
