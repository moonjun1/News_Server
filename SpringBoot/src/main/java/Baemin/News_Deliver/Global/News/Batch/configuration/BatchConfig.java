package Baemin.News_Deliver.Global.News.Batch.configuration;

import Baemin.News_Deliver.Global.News.Batch.listener.BatchJobCompletionListener;
import Baemin.News_Deliver.Global.News.Batch.dto.NewsItemDTO;
import Baemin.News_Deliver.Global.News.Batch.dto.NewsResponseDTO;
import Baemin.News_Deliver.Global.News.Batch.entity.News;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 뉴스 배치 관련 설정 클래스
 *
 * <p>이 클래스는 Spring Batch를 이용하여 DeepSearch 뉴스 API로부터
 * 섹션별 뉴스 데이터를 수집하고, DB에 저장하는 배치 작업을 구성합니다.</p>
 *
 * 구성 요소:
 * <ul>
 *     <li>Job: {@code newsDataSaveJob}</li>
 *     <li>Step: {@code newsDataSaveStep}</li>
 *     <li>Reader: {@code apiReader}</li>
 *     <li>Processor: {@code newsProcessor}</li>
 *     <li>Writer: {@code newsWriter}</li>
 * </ul>
 *
 * 주요 흐름:
 * <ol>
 *     <li>전날 날짜에 해당하는 뉴스 데이터를 섹션별로 수집</li>
 *     <li>각 페이지별 데이터를 순차적으로 요청 후, 리스트에 적재</li>
 *     <li>중복 뉴스는 Listener에서 후처리로 제거</li>
 * </ol>
 *
 * API 요청 구조:
 * - endpoint: {@code https://api-v2.deepsearch.com/v1/articles/{section}}
 * - headers: {@code Authorization: {API_KEY}}
 * - query params: {@code page, page_size, date_from, date_to, order}
 *
 * @author 김원중
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableBatchProcessing
public class BatchConfig {

    @Value("${deepsearch.api.key}")
    private String apiKey;
    private static final String API_URL = "https://api-v2.deepsearch.com/v1/articles";

    /**
     * 뉴스 저장 배치 Job 정의
     *
     * @param jobRepository Job Repository
     * @param newsDataSaveStep 뉴스 저장 Step
     * @param listener 배치 완료 후 리스너
     * @return Job 인스턴스
     */
    @Bean
    public Job newsDataSaveJob(JobRepository jobRepository,
                               Step newsDataSaveStep,
                               BatchJobCompletionListener listener) {
        return new JobBuilder("newsDataSaveJob", jobRepository)
                .start(newsDataSaveStep)
                .listener(listener)
                .build();
    }

    /**
     * 뉴스 저장 Step 정의 (청크 단위 처리)
     *
     * @param jobRepository Job Repository
     * @param transactionManager 트랜잭션 관리자
     * @param apiReader API Reader (뉴스 리스트)
     * @param newsProcessor DTO → Entity 변환 Processor
     * @param newsWriter DB 저장 Writer
     * @return Step 인스턴스
     */
    @Bean
    public Step newsDataSaveStep(JobRepository jobRepository,
                                 PlatformTransactionManager transactionManager,
                                 ItemReader<NewsItemDTO> apiReader,
                                 ItemProcessor<NewsItemDTO, News> newsProcessor,
                                 ItemWriter<News> newsWriter) {

        return new StepBuilder("newsDataSaveStep", jobRepository)
                .<NewsItemDTO, News>chunk(10_000, transactionManager)  // 10000개씩 처리
                .reader(apiReader)
                .processor(newsProcessor)
                .writer(newsWriter)
                .build();
    }

    /**
     * 외부 API Reader
     *
     * <p>지정된 섹션과 날짜를 기준으로 뉴스 데이터를 모두 가져와서 ListItemReader로 반환합니다.</p>
     *
     * @param section 섹션명 (JobParameter로 전달)
     * @return ItemReader
     */
    @StepScope
    @Bean
    public ItemReader<NewsItemDTO> apiReader(
            @Value("#{jobParameters['section']}") String section,
            @Value("#{jobParameters['offset']}") Long offset
    ) {
        // API에서 데이터를 모두 불러와서 ListItemReader로 반환
        // ListItemReader가 이 데이터를 하나씩 읽어서 다음 단계로 전달

        //하루 전날 날짜 구하기
        LocalDate day = LocalDate.now().minusDays(1);

        //원하는 포맷 지정
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        //문자열로 변환
        String dateTo = day.format(formatter);
        String dateFrom = day.format(formatter);

        int pageSize = 100;

        log.info("🔍 검색한 기간: {} ~ {}", dateFrom, dateTo);

        NewsResponseDTO newsResponseDTO = getAPIResponse(1, section, pageSize, dateFrom, dateTo);

        //전체 페이지수 확인
        //offset만큼 생략하기
        //반복할 페이지 정하기
        int totalPages = getTotalPagesOfResponse(newsResponseDTO, offset);

        List<NewsItemDTO> newsList = new ArrayList<>();

        for (int page = 1; page <= totalPages; page++) {
            getNewsList(page, section, pageSize, dateFrom, dateTo, newsList);
            if (newsList == null) {
                log.warn("⚠️ [{}] Page {} 수집 실패 또는 빈 응답", section, page);
            }
        }

        log.info("📦 [{}] 전체 뉴스 수: {}", section, newsList.size());
        return new ListItemReader<>(newsList);
    }

    /**
     * 뉴스 DTO → Entity 변환 Processor
     *
     * <p>섹션이 존재하지 않거나 배치 수집 중 중복된 제목의 뉴스는 skip 처리합니다.</p>
     * <p>제목 정규화: 대괄호/소괄호 태그 제거 후 앞 30자로 중복 판별</p>
     *
     * @return ItemProcessor
     */
    @Bean
    public ItemProcessor<NewsItemDTO, News> newsProcessor() {
        // 배치 실행 단위로 중복 제목 추적 (thread-safe Set)
        Set<String> seenTitles = Collections.synchronizedSet(new java.util.HashSet<>());

        return dto -> {
            if (dto.getSections() == null || dto.getSections().isEmpty()) {
                log.warn("❌ 섹션 정보 없음 → 건너뜀 (title: {})", dto.getTitle());
                return null;
            }

            // 제목 정규화: [태그], (태그) 제거 후 공백 제거, 앞 30자
            String normalizedTitle = dto.getTitle()
                    .replaceAll("\\[.*?\\]|\\(.*?\\)", "")
                    .replaceAll("\\s+", "")
                    .toLowerCase();
            String titleKey = dto.getPublisher() + "::" +
                    (normalizedTitle.length() > 30 ? normalizedTitle.substring(0, 30) : normalizedTitle);

            if (!seenTitles.add(titleKey)) {
                log.debug("⏭ 배치 내 중복 제목 skip: {}", dto.getTitle());
                return null;
            }

            return News.builder()
                    .title(dto.getTitle())
                    .summary(dto.getSummary())
                    .publisher(dto.getPublisher())
                    .contentUrl(dto.getContent_url())
                    .publishedAt(dto.getPublished_at())
                    .sections(dto.getSections().get(0))
                    .send(false)
                    .build();
        };
    }

    /**
     * 뉴스 DB 저장용 Writer (JDBC Batch 방식)
     *
     * <p>{@code JdbcBatchItemWriter}를 사용하여 한 번에 10,000건의 데이터를 삽입합니다.</p>
     *
     * @param dataSource DataSource (DB 연결)
     * @return ItemWriter
     */
    @Bean
    public ItemWriter<News> newsWriter(javax.sql.DataSource dataSource) {
        JdbcBatchItemWriter<News> writer = new JdbcBatchItemWriter<>();
        writer.setDataSource(dataSource);
        writer.setSql("""
        INSERT INTO news (title, summary, content_url, published_at, send, sections, publisher)
        VALUES (:title, :summary, :contentUrl, :publishedAt, :send, :sections, :publisher)
    """);

        writer.setItemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>());
        writer.afterPropertiesSet(); // 설정 검증 필수

        return writer;
    }

    /**
     * API 응답 뉴스 데이터를 리스트에 적재
     *
     * @param page 요청할 페이지 번호
     * @param section 섹션명
     * @param pageSize 페이지당 데이터 수
     * @param dateFrom 시작 날짜
     * @param dateTo 종료 날짜
     * @param newsList 데이터를 담을 리스트
     */
    private void getNewsList(int page, String section, int pageSize, String dateFrom, String dateTo, List<NewsItemDTO> newsList) {
        RestTemplate restTemplate = new RestTemplate();

        // 쿼리 파라미터 구성
        String url = UriComponentsBuilder.fromHttpUrl(API_URL)
                .pathSegment(section)
                .queryParam("page_size", pageSize)
                .queryParam("date_to", dateTo)
                .queryParam("date_from", dateFrom)
                .queryParam("order", "-published_at")
                .queryParam("page", page)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<NewsResponseDTO> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                NewsResponseDTO.class
        );

        NewsResponseDTO body = response.getBody();

        if (body != null && body.getData() != null) {
            for (NewsItemDTO item : body.getData()) {
                newsList.add(item);
            }
        }

    }

    /**
     * 총 페이지 수를 가져오는 메서드
     *
     * <p>page=1 요청을 통해 해당 조건에 대한 전체 페이지 수를 파악합니다.</p>
     *
     * @return 총 페이지 수
     */
    private NewsResponseDTO getAPIResponse(int page, String section, int pageSize, String dateFrom, String dateTo) {
        RestTemplate restTemplate = new RestTemplate();

        // 쿼리 파라미터 구성
        String url = UriComponentsBuilder.fromHttpUrl(API_URL)
                .pathSegment(section)
                .queryParam("page_size", pageSize)
                .queryParam("date_to", dateTo)
                .queryParam("date_from", dateFrom)
                .queryParam("order", "-published_at")
                .queryParam("page", page)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<NewsResponseDTO> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                NewsResponseDTO.class
        );

        return response.getBody();
    }

    private int getTotalPagesOfResponse(NewsResponseDTO newsResponseDTO, Long offset) {
        return Math.min((int) (newsResponseDTO.getTotal_items() - (9000 * offset)) / 100 + 1, 100);
    }
}
