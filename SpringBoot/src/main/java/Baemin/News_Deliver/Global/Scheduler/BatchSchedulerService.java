package Baemin.News_Deliver.Global.Scheduler;

import Baemin.News_Deliver.Domain.HotTopic.service.HotTopicService;
import Baemin.News_Deliver.Domain.Kakao.Exception.KakaoException;
import Baemin.News_Deliver.Global.Exception.ErrorCode;
import Baemin.News_Deliver.Global.News.Batch.service.BatchService;
import Baemin.News_Deliver.Global.News.ElasticSearch.service.NewsEsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;

/**
 * {@code BatchSchedulerService}는 뉴스, 엘라스틱서치 인덱싱, 핫토픽 수집 등의
 * 배치 작업을 스케줄링하는 서비스입니다.
 *
 * <p>Spring의 {@link TaskScheduler}를 이용하여 주기적인 작업을 등록하며,
 * 서버가 시작될 때 {@link PostConstruct}를 통해 자동 실행됩니다.</p>
 *
 * <p>실행되는 주요 작업:</p>
 * <ul>
 *     <li>DB 뉴스 수집 및 저장</li>
 *     <li>Elasticsearch 인덱싱</li>
 *     <li>핫토픽 수집 및 저장</li>
 *     <li>사용자 맞춤 스케줄 등록</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchSchedulerService {

    private final TaskScheduler taskScheduler;
    private final BatchService batchService;
    private final NewsEsService newsEsService;
    private final SchedulerInitializer schedulerInitializer;
    private final HotTopicService hotTopicService;
    private ScheduledFuture<?> batchFuture;

    /**
     * 서버 시작 시 실행되며, 매일 자정(현재는 테스트로 오후 11시 59분)에 실행될 배치를 등록합니다.
     *
     * <p>스케줄링된 작업에는 DB 저장, Elasticsearch 인덱싱, 핫토픽 수집,
     * 사용자 맞춤 뉴스 전송 스케줄 등록이 포함됩니다.</p>
     */

    @PostConstruct
    public void scheduleNewsBatch() {
        String cron = "0 0 0 * * *";

        Runnable batchTask = () -> {
            log.info("[BatchScheduler] 자정 배치 시작 - {}", LocalDateTime.now());

            // DB 배치
            try {
                LocalDateTime start = LocalDateTime.now();
                log.info("[BatchScheduler] DB 배치 시작");

                batchService.runBatch();

                LocalDateTime end = LocalDateTime.now();
                log.info("[BatchScheduler] DB 배치 완료 (실행 시간: {}초)", Duration.between(start, end).toSeconds());

            } catch (Exception e) {
                log.error("[BatchScheduler] DB 배치 실패: DB 배치 중 예외 발생: {}", e.getMessage(), e);
                throw new KakaoException(ErrorCode.BATCH_SCHEDULER_FAILED);
            }

            // 엘라스틱 인덱싱
            try {
                LocalDateTime start = LocalDateTime.now();
                log.info("[BatchScheduler] 엘라스틱서치 인덱싱 시작");

                newsEsService.esBulkService();

                LocalDateTime end = LocalDateTime.now();
                log.info("[BatchScheduler] 엘라스틱서치 인덱싱 완료 (실행 시간: {}초)", Duration.between(start, end).toSeconds());

            } catch (Exception e) {
                log.error("[BatchScheduler] 엘라스틱 서치 인덱싱 중 예외 발생: {}", e.getMessage(), e);
                throw new KakaoException(ErrorCode.ES_SCHEDULER_FAILED);
            }

            // 핫토픽
            try {
                LocalDateTime start = LocalDateTime.now();
                log.info("[BatchScheduler] 핫토픽 배치 시작");

                hotTopicService.getAndSaveHotTopic();

                LocalDateTime end = LocalDateTime.now();
                log.info("[BatchScheduler] 핫토픽 배치 완료 (실행 시간: {}초)", Duration.between(start, end).toSeconds());

            } catch (Exception e) {
                log.error("[BatchScheduler] 핫토픽 배치 중 예외 발생: {}", e.getMessage(), e);
                throw new KakaoException(ErrorCode.HT_SCHEDULER_FAILED);
            }

            // 사용자 셋팅 스케줄 등록
            try {
                LocalDateTime start = LocalDateTime.now();
                log.info("[BatchScheduler] 사용자 셋팅 스케줄 등록 시작");

                schedulerInitializer.scheduleAllUserSettings();

                LocalDateTime end = LocalDateTime.now();
                log.info("[BatchScheduler] 사용자 셋팅 스케줄 등록 완료 (실행 시간: {}초)", Duration.between(start, end).toSeconds());

            } catch (Exception e) {
                log.error("[BatchScheduler] 사용자 스케줄러 등록 중 예외 발생: {}", e.getMessage(), e);
                throw new KakaoException(ErrorCode.SETTING_SCHEDULER_FAILED);
            }
        };

        batchFuture = taskScheduler.schedule(batchTask, new CronTrigger(cron));
    }

    /**
     * 현재 등록된 배치 스케줄을 취소합니다.
     *
     * <p>테스트나 관리 용도로 사용되며, 이미 실행 중인 스케줄이 있는 경우에만 취소됩니다.</p>
     */
    public void cancelNewsSchedule() {
        if (batchFuture != null && !batchFuture.isCancelled()) {
            batchFuture.cancel(false);
            log.info("[BatchScheduler] 자정 뉴스 배치 스케줄 취소");
        }
    }
}
