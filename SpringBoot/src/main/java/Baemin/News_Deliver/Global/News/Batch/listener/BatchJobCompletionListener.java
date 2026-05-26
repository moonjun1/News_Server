package Baemin.News_Deliver.Global.News.Batch.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 뉴스 배치 작업 완료 후 실행되는 리스너
 *
 * <p>해당 리스너는 Spring Batch Job이 성공적으로 완료되었을 때 실행됩니다.
 * 이전 날짜(어제 기준) 뉴스 중에서 제목과 언론사가 동일한 중복 뉴스가 있는 경우,
 * {@code ROW_NUMBER()}를 활용하여 가장 오래된 뉴스만 남기고 나머지를 삭제합니다.</p>
 *
 * <p>삭제 대상은 아래 조건을 만족합니다:</p>
 * <ul>
 *     <li>published_at이 어제 날짜</li>
 *     <li>title, publisher가 동일한 뉴스가 2개 이상 존재할 경우</li>
 *     <li>그 중 가장 오래된 1개만 남기고 나머지 삭제</li>
 * </ul>
 *
 * 로그에 삭제된 뉴스 개수를 출력합니다.
 *
 * @author 김원중
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchJobCompletionListener implements JobExecutionListener {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {

            // 1. 완전 동일 제목+언론사 중복 제거 (가장 최신 기사 유지 - id DESC)
            String exactDupSql = """
                DELETE FROM news
                WHERE id IN (
                    SELECT id FROM (
                        SELECT id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY title, publisher
                                   ORDER BY published_at DESC, id DESC
                               ) AS rn
                        FROM news
                        WHERE published_at >= CURDATE() - INTERVAL 1 DAY
                          AND published_at < CURDATE()
                    ) t
                    WHERE t.rn > 1
                );
                """;

            // 2. 정규화 제목 기반 유사 중복 제거
            //    - 대괄호/소괄호 태그 제거([속보], (종합) 등), 공백 제거 후 앞 30자 비교
            //    - 같은 정규화 제목 그룹에서 가장 최신 기사만 유지
            String similarDupSql = """
                DELETE FROM news
                WHERE id IN (
                    SELECT id FROM (
                        SELECT id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY
                                       LEFT(
                                           REPLACE(
                                               REGEXP_REPLACE(title, '\\\\[.*?\\\\]|\\\\(.*?\\\\)|\\\\s', ''),
                                           ' ', ''),
                                       30
                                       ),
                                       publisher
                                   ORDER BY published_at DESC, id DESC
                               ) AS rn
                        FROM news
                        WHERE published_at >= CURDATE() - INTERVAL 1 DAY
                          AND published_at < CURDATE()
                    ) t
                    WHERE t.rn > 1
                );
                """;

            // 3. 노이즈성 기사 제거 ([속보], [단독] 단독 제목, 광고성 패턴)
            String noiseSql = """
                DELETE FROM news
                WHERE (
                    title REGEXP '^\\\\[(속보|단독|긴급|알림)\\\\]$'
                    OR title LIKE '%〔%'
                    OR title LIKE '%▶%'
                    OR LENGTH(title) < 10
                )
                AND published_at >= CURDATE() - INTERVAL 1 DAY
                AND published_at < CURDATE();
                """;

            int exactDeleted = jdbcTemplate.update(exactDupSql);
            log.info("✅ 완전 중복 제거: {}건", exactDeleted);

            int similarDeleted = jdbcTemplate.update(similarDupSql);
            log.info("✅ 유사 제목 중복 제거: {}건", similarDeleted);

            int noiseDeleted = jdbcTemplate.update(noiseSql);
            log.info("✅ 노이즈 기사 제거: {}건", noiseDeleted);

            log.info("✅ 전체 중복/노이즈 제거 완료: 총 {}건", exactDeleted + similarDeleted + noiseDeleted);
        }
    }
}
