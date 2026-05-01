package Baemin.News_Deliver.Domain.Kakao.service;

import Baemin.News_Deliver.Global.News.ElasticSearch.dto.NewsEsDocument;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoNewsService {

    private final ElasticsearchClient client;

    /**
     * 뉴스가 5개 미만일 경우 fallback 로직 적용하는 메서드
     *
     * @param includeKeywords 포함 키워드
     * @param blockKeywords 제외 키워드
     * @return 뉴스 리스트
     */
    public List<NewsEsDocument> searchNewsWithFallback(List<String> includeKeywords, List<String> blockKeywords) {
        List<NewsEsDocument> result = searchNewsByDateRange(includeKeywords, blockKeywords, 1); // 어제 기준

        if (result.size() < 5) {
            log.info("⚠뉴스 부족 → 최근 7일간으로 fallback");
            result = searchNewsByDateRange(includeKeywords, blockKeywords, 7); // fallback
        }

        // 최대 5개 제한
        return result.size() > 5 ? result.subList(0, 5) : result;
    }

    /**
     * 어제의 뉴스만 검색하는 메서드
     *
     * @param includeKeywords 포함 키워드
     * @param blockKeywords 제외 키워드
     * @return 뉴스 리스트
     */
    public List<NewsEsDocument> searchNews(List<String> includeKeywords, List<String> blockKeywords) {

        // 어제의 뉴스만 검색하기에 { 검색 기간 : 1 }로 설정
        return searchNewsByDateRange(includeKeywords, blockKeywords, 1);
    }

    /**
     * 날짜 범위를 받는 뉴스 검색 메서드
     *
     * @param includeKeywords 포함 키워드
     * @param blockKeywords 제외 키워드
     * @param fromDaysAgo 검색할 기간
     * @return 뉴스 리스트
     */
    public List<NewsEsDocument> searchNewsByDateRange(List<String> includeKeywords, List<String> blockKeywords, int fromDaysAgo) {
        try {
            LocalDate now = LocalDate.now();
            LocalDate fromDate = now.minusDays(fromDaysAgo);

            // 포함 키워드 쿼리
            Query includeKeywordQuery = Query.of(q -> q
                    .bool(b -> b
                            .should(includeKeywords.stream()
                                    .map(kw -> Query.of(q2 -> q2
                                            .multiMatch(m -> m
                                                    .query(kw)
                                                    .fields("title", "summary", "content_url", "publisher")
                                                    .type(TextQueryType.BoolPrefix)
                                            )
                                    ))
                                    .collect(Collectors.toList())
                            )
                            .minimumShouldMatch("1")
                    )
            );

            // 날짜 필터 (fromDate ~ now)
            Query dateFilter = Query.of(q -> q
                    .range(r -> r
                            .field("published_at")
                            .gte(JsonData.of(fromDate.toString()))
                            .lte(JsonData.of(now.toString()))
                            .format("yyyy-MM-dd")
                    )
            );

            List<Query> mustQueries = new ArrayList<>();
            mustQueries.add(includeKeywordQuery);
            mustQueries.add(dateFilter);

            // 제외 키워드 처리
            Query finalQuery;
            if (blockKeywords != null && !blockKeywords.isEmpty()) {
                Query excludeKeywordQuery = Query.of(q -> q
                        .bool(b -> b
                                .should(blockKeywords.stream()
                                        .map(kw -> Query.of(q2 -> q2
                                                .multiMatch(m -> m
                                                        .query(kw)
                                                        .fields("title", "summary", "content_url", "publisher")
                                                        .type(TextQueryType.BoolPrefix)
                                                )
                                        ))
                                        .collect(Collectors.toList())
                                )
                        )
                );

                finalQuery = Query.of(q -> q
                        .bool(b -> b
                                .must(mustQueries)
                                .mustNot(excludeKeywordQuery)
                        )
                );
            } else {
                finalQuery = Query.of(q -> q
                        .bool(b -> b
                                .must(mustQueries)
                        )
                );
            }

            // Elasticsearch 검색 요청
            SearchRequest request = SearchRequest.of(s -> s
                    .index("news-index-nori")
                    .query(finalQuery)
                    .size(5)
                    .sort(sort -> sort
                            .score(sc -> sc.order(SortOrder.Desc))
                    )
            );

            SearchResponse<NewsEsDocument> response = client.search(request, NewsEsDocument.class);

            response.hits().hits().forEach(hit ->
                    log.info("{} | score: {}", hit.source().getTitle(), hit.score())
            );

            return response.hits().hits().stream()
                    .map(hit -> hit.source())
                    .collect(Collectors.toList());

        } catch (IOException e) {
            log.error("키워드 기반 뉴스 검색 실패: {}", e.getMessage(), e);
            return List.of();
        }
    }

}