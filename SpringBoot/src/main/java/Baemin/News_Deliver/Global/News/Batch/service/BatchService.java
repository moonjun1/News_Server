package Baemin.News_Deliver.Global.News.Batch.service;

import Baemin.News_Deliver.Global.NewsMonitoring.Service.IntermediateBatchRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * 뉴스 섹션별 배치 실행 서비스
 *
 * <p>이 서비스는 Spring Batch 기반으로, 미리 정의된 각 뉴스 섹션에 대해 반복적으로
 * Job을 실행합니다. 주로 수동 또는 예약된 호출을 통해 작동하며,
 * 각 섹션별로 {@code JobParameters}에 "section"을 포함해 전달합니다.</p>
 *
 * <p>총 섹션 목록은 다음과 같습니다:</p>
 * <ul>
 *     <li>politics</li>
 *     <li>economy</li>
 *     <li>society</li>
 *     <li>culture</li>
 *     <li>tech</li>
 *     <li>entertainment</li>
 *     <li>opinion</li>
 * </ul>
 *
 * <p>모든 섹션을 처리하는데 걸린 총 소요 시간도 로그로 출력됩니다.</p>
 *
 * <p>이 서비스는 컨트롤러에서 호출되어 사용되며, 성공 시 200 OK를, 실패 시 500을 응답합니다.</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class BatchService {

    private final JobLauncher jobLauncher;
    private final Job newsDataSaveJob;
    private final IntermediateBatchRedisService intermediateBatchRedisService;


    /** 처리할 섹션 목록 */
    private  String[] sections = {
            "politics", "economy", "society", "culture", "tech", "entertainment", "opinion"
    };
    /**
     * 섹션별 뉴스 저장 배치 실행
     *
     * 각 섹션에 대해 하나의 Job을 실행하며,
     * JobParameter로 섹션명과 현재 시간(`time`)을 함께 전달합니다.
     */
    public void runBatch() throws JobInstanceAlreadyCompleteException, JobExecutionAlreadyRunningException, JobParametersInvalidException, JobRestartException {
        long totalStart = System.currentTimeMillis(); // 전체 시작 시간

        for (String section : sections) {
            int count = intermediateBatchRedisService.getBatchCount(section);

            JobParameters params = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .addString("section", section)
                    .addLong("offset", Long.valueOf(count))
                    .toJobParameters();

            log.info("📦 섹션별 배치 시작: {}", section);
            jobLauncher.run(newsDataSaveJob, params);
        }

        intermediateBatchRedisService.flushIntermediateBatchKeys();

        long totalEnd = System.currentTimeMillis(); // 전체 끝 시간
        log.info("✅ 전체 섹션 배치 소요 시간: {} ms", (totalEnd - totalStart));
    }

}
