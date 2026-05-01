package Baemin.News_Deliver.Global.News.ElasticSearch.configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch Client 설정 클래스
 *
 * <p>Elasticsearch 7.17 이상 버전과 호환되는 Java API Client(co.elastic.clients)를 사용하여
 * Elasticsearch와 통신할 수 있는 {@link ElasticsearchClient}를 생성합니다.</p>
 *
 * 구성 내용:
 * <ul>
 *     <li>Low-level RestClient: HTTP 기반 연결 설정</li>
 *     <li>Jackson 기반 JSON 직렬화 매퍼 설정</li>
 *     <li>Java 8 LocalDateTime 지원을 위한 모듈 등록</li>
 *     <li>날짜를 timestamp가 아닌 ISO 포맷으로 출력 설정</li>
 * </ul>
 *
 * 접속 대상: {@code http://elasticsearch:9200}
 *
 * ⚠️ 주의:
 * - Spring Boot 3.x 환경에서 `JavaTimeModule`을 등록하지 않으면 `LocalDateTime` 직렬화 오류 발생 가능
 * - ElasticsearchClient는 singleton bean으로 등록됨
 *
 */
@Configuration
public class ElasticsearchClientConfig {

    /**
     * Elasticsearch Java API Client 빈 등록
     *
     * <p>커스텀 ObjectMapper 설정과 RestClient를 기반으로 {@link ElasticsearchClient} 인스턴스를 반환합니다.</p>
     *
     * @return ElasticsearchClient
     */
    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // HTTP 기반 Low-level 클라이언트 구성
        RestClient restClient = RestClient.builder(
                new HttpHost("elasticsearch", 9200)
        ).build();

        // LocalDateTime 직렬화 지원 및 ISO 포맷 지정
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule()); // Java 8 Date/Time 지원
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // timestamp → ISO 포맷

        // Jackson JSON Mapper 생성
        JacksonJsonpMapper mapper = new JacksonJsonpMapper(objectMapper);

        // 전송 설정 구성
        RestClientTransport transport = new RestClientTransport(restClient, mapper);

        return new ElasticsearchClient(transport);
    }

}
