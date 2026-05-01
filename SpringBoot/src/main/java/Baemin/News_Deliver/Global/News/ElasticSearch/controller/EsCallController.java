package Baemin.News_Deliver.Global.News.ElasticSearch.controller;

import Baemin.News_Deliver.Global.News.ElasticSearch.service.NewsEsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Elasticsearch 색인 호출 테스트용 컨트롤러
 *
 * <p>뉴스 데이터를 DB에서 읽어와 Elasticsearch에 색인하는 작업을 수동으로 호출하는 용도의 컨트롤러입니다.</p>
 * <p>실 운영에서는 Scheduler 또는 Admin 기능으로 대체될 예정이며, 현재는 개발 및 테스트 목적에서 사용됩니다.</p>
 *
 * 색인 대상: {@code news-index-nori}
 * 색인 데이터: 전날 기준 뉴스 데이터 (섹션별)
 *
 */
@RestController
@RequiredArgsConstructor
public class EsCallController {

    private final NewsEsService newsEsService;

    /**
     *  뉴스 데이터 Elasticsearch 색인 수동 실행
     *
     * <p>전날 뉴스 데이터를 Elasticsearch에 섹션별로 bulk 색인합니다.</p>
     * <p>스케줄러 구현 전 테스트용으로 사용되며, 나중에 Admin 기능으로 대체할 수 있습니다.</p>
     *
     * @throws IOException Elasticsearch 통신 실패 시
     */
    @Operation(
            summary = "[관리자/테스트] 전날 뉴스 색인 실행",
            description = "전날 DB에서 뉴스 데이터를 불러와 섹션별로 Elasticsearch에 bulk 색인합니다. 테스트용 엔드포인트입니다."
    )
    @ApiResponses(@ApiResponse(responseCode = "204", description = "설정 삭제 성공"))
    @GetMapping("/api/admin/elasticsearch")
    public ResponseEntity<Void> bulk() throws IOException {
        newsEsService.esBulkService();
        return ResponseEntity.noContent().build();
    }
}
