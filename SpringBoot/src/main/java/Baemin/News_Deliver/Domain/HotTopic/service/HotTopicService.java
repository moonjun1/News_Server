package Baemin.News_Deliver.Domain.HotTopic.service;

import Baemin.News_Deliver.Domain.HotTopic.dto.HotTopicResponseDTO;
import Baemin.News_Deliver.Domain.HotTopic.entity.HotTopic;
import Baemin.News_Deliver.Domain.HotTopic.repository.HotTopicRepository;
import Baemin.News_Deliver.Global.News.ElasticSearch.dto.NewsEsDocument;
import Baemin.News_Deliver.Global.News.ElasticSearch.service.NewsEsService;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 핫토픽 서비스
 *
 * <p>Elasticsearch에 색인된 뉴스 데이터를 기반으로 "어제의 인기 키워드(핫토픽)"를 추출하고,</p>
 * <ul>
 *   <li>DB에 저장하거나</li>
 *   <li>응답 DTO로 반환하거나</li>
 *   <li>해당 키워드에 대한 관련 뉴스를 검색하는 기능</li>
 * </ul>
 * 을 제공합니다.
 *
 * <p>핫토픽 추출은 {@link NewsEsService#getTopKeywordsForDateRange(LocalDate, LocalDate, int)}를 통해 수행되며,</p>
 * 대상 인덱스는 {@code news-index-nori}, 분석 필드는 {@code combinedTokens}입니다.
 *
 * 기준 날짜는 항상 **전날(CURDATE - 1)** 이며,
 * DB에는 최대 10건의 키워드와 키워드 등장 횟수(docCount)가 저장됩니다.
 */
@Slf4j
@Service
public class HotTopicService {

    private final HotTopicRepository hotTopicRepository;
    private final NewsEsService elasticSearchService;
    private final RedisTemplate<String, Object> redisTemplate;

    public HotTopicService(
            HotTopicRepository hotTopicRepository,
            NewsEsService elasticSearchService,
            @Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisTemplate
    ) {
        this.hotTopicRepository = hotTopicRepository;
        this.elasticSearchService = elasticSearchService;
        this.redisTemplate = redisTemplate;
    }

    private static final String CACHE_PREFIX = "keyword:";

    /**
     * DB에서 "어제" 저장된 핫토픽 상위 10개 조회 후 DTO로 반환
     *
     * <p>{@code topic_date BETWEEN 어제 00:00:00 ~ 23:59:59} 범위 내에서,
     * {@code topic_rank ASC} 기준으로 정렬된 10개 핫토픽을 반환합니다.</p>
     *
     * @return 어제의 핫토픽 리스트 (최대 10개)
     */
    public List<HotTopicResponseDTO> getHotTopicList() {
        long start = System.nanoTime();
        String cacheKey = "hottopic:daily";
        List<HotTopicResponseDTO> cached = (List<HotTopicResponseDTO>) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            long end = System.nanoTime();
            log.info("✅ 핫토픽 캐시에서 가져옴 ({} ms)", (end - start) / 1_000_000);
            return cached;
        }

        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime startOfYesterday = yesterday.atStartOfDay();
        LocalDateTime endOfYesterday = yesterday.atTime(LocalTime.MAX);
        log.info("startOfYesterday : {}", startOfYesterday);
        log.info("endOfYesterday : {}", endOfYesterday);

        List<HotTopicResponseDTO> result = hotTopicRepository.findTop10ByTopicDateBetweenOrderByTopicRankAsc(startOfYesterday, endOfYesterday)
                .stream()
                .map(entity -> HotTopicResponseDTO.builder()
                        .topicRank(entity.getTopicRank())
                        .keyword(entity.getKeyword())
                        .keywordCount(entity.getKeywordCount())
                        .topicDate(entity.getTopicDate())
                        .build())
                .toList();

        long end = System.nanoTime();
        log.info("📦 핫토픽 DB 조회 & 캐싱 완료 ({} ms)", (end - start) / 1_000_000);

        long ttlSeconds = Duration.between(
                LocalDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay()
        ).getSeconds();

        redisTemplate.opsForValue().set(cacheKey, result, Duration.ofSeconds(ttlSeconds));
        return result;
    }

    /**
     * "어제"의 인기 키워드 Top 10을 Elasticsearch에서 추출 후 DB에 저장
     *
     * <p>{@code published_at BETWEEN 어제 ~ 오늘} 범위 내에서,
     * {@code combinedTokens} 필드에 대해 Terms Aggregation을 수행해 키워드를 추출합니다.</p>
     *
     * <p>추출된 키워드는 {@link HotTopic} Entity로 저장되며,
     * 순위(topicRank), 키워드, 키워드 등장 횟수, 날짜가 함께 기록됩니다.</p>
     *
     * @throws IOException Elasticsearch 통신 실패 시
     */
    @Transactional
    public void getAndSaveHotTopic() throws IOException {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        List<StringTermsBucket> buckets = elasticSearchService.getTopKeywordsForDateRange(
                yesterday, today, 10
        );

        long rank = 1;
        for (StringTermsBucket bucket : buckets) {
            HotTopic topic = HotTopic.builder()
                    .topicRank(rank++)
                    .keyword(bucket.key().stringValue())
                    .keywordCount(bucket.docCount())
                    .topicDate(yesterday.atStartOfDay())
                    .build();

            hotTopicRepository.save(topic);
        }

        log.info("🔥 어제자 핫토픽 {}건 저장 완료", buckets.size());
    }

    /**
     * 특정 키워드로 관련 뉴스 목록 검색
     *
     * <p>Elasticsearch에서 {@code combinedTokens} 필드에 대해 match 쿼리를 수행하여,
     * 해당 키워드가 포함된 뉴스 도큐먼트를 최대 {@code size}개까지 반환합니다.</p>
     *
     * @param keyword 검색 키워드
     * @param size 최대 검색 결과 개수
     * @return 검색된 뉴스 도큐먼트 리스트
     * @throws IOException Elasticsearch 통신 실패 시
     */
    public List<NewsEsDocument> getNewsList(String keyword, int size) throws IOException {
        long start = System.nanoTime();
        String cacheKey = CACHE_PREFIX + keyword;

        List<NewsEsDocument> cached = (List<NewsEsDocument>) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            long end = System.nanoTime();
            log.info("✅ 캐시에서 가져옴: {} ({} ms)", keyword, (end - start) / 1_000_000);
            return cached;
        }

        List<NewsEsDocument> result = elasticSearchService.searchByKeyword(keyword, size);
        long end = System.nanoTime();
        log.info("📦 ES 조회 & 캐싱: {} 완료 ({} ms)", keyword, (end - start) / 1_000_000);

        long ttlSeconds = Duration.between(
                LocalDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay()
        ).getSeconds();

        redisTemplate.opsForValue().set(cacheKey, result, Duration.ofSeconds(ttlSeconds));
        return result;
    }
}
