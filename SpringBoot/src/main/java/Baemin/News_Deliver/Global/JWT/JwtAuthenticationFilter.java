package Baemin.News_Deliver.Global.JWT;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 기반 인증을 처리하는 Spring Security 필터
 *
 * 모든 HTTP 요청에 대해 Authorization 헤더의 JWT 토큰을 검증하고,
 * 유효한 토큰이 있는 경우 Spring Security Context에 인증 정보를 설정합니다.
 * OncePerRequestFilter를 상속하여 요청당 한 번만 실행되도록 보장합니다.
 *
 * 필터 동작 순서:
 * 1. Authorization 헤더에서 Bearer 토큰 추출
 * 2. JWT 토큰 유효성 검증
 * 3. 토큰에서 사용자 정보 추출
 * 4. Spring Security 인증 객체 생성
 * 5. SecurityContext에 인증 정보 저장
 *
 * @see JwtTokenProvider
 * @see OncePerRequestFilter
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * HTTP 요청에 대한 JWT 인증 처리를 수행합니다
     *
     * Authorization 헤더에서 JWT 토큰을 추출하고 유효성을 검증합니다.
     * 유효한 토큰인 경우 사용자 인증 정보를 SecurityContext에 설정하여
     * 후속 요청 처리에서 인증된 사용자로 인식되도록 합니다.
     *
     * 인증 실패 시에도 요청을 차단하지 않고 다음 필터로 전달하여,
     * 인증이 필요하지 않은 엔드포인트는 정상적으로 처리될 수 있도록 합니다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        try {
            // 1. Authorization 헤더에서 JWT 토큰 추출
            String accessToken = extractTokenFromRequest(request);

            // 2. 토큰이 있고 유효한지 확인
            if (accessToken != null && jwtTokenProvider.validateToken(accessToken)) {

                // 3. 토큰에서 kakaoId 추출
                String kakaoId = jwtTokenProvider.getKakaoIdFromToken(accessToken);

                // 4. 인증 객체 생성
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                kakaoId,  // principal
                                null,     // credentials
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                        );

                // 5. 요청 세부정보 설정
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                // 6. SecurityContext에 인증 정보 저장
                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("JWT 인증 성공: kakaoId = {}, URI = {}", kakaoId, request.getRequestURI());
            }

        } catch (Exception e) {
            log.error("JWT 인증 중 오류 발생: URI = {}, error = {}", request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();

            // 인증 실패해도 요청은 계속 진행 (SecurityConfig에서 권한 체크)
        }

        // 7. 다음 필터로 요청 전달
        filterChain.doFilter(request, response);
    }

    /**
     * HTTP 요청의 Authorization 헤더에서 JWT 토큰을 추출합니다
     *
     * "Bearer {token}" 형식의 Authorization 헤더에서 실제 토큰 부분만을 추출합니다.
     * Bearer 스키마가 없거나 헤더가 존재하지 않는 경우 null을 반환합니다.
     *
     * @param request JWT 토큰을 추출할 HTTP 요청 객체
     * @return 추출된 JWT 토큰 문자열, 토큰이 없는 경우 null
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7); // "Bearer " 제거
        }

        return null;
    }

    /**
     * 특정 경로에 대해 JWT 인증 필터 적용을 제외할지 결정합니다
     *
     * 인증이 필요하지 않은 공개 엔드포인트나 OAuth2 로그인 관련 경로,
     * 정적 리소스 경로에 대해서는 JWT 인증 필터를 건너뛰도록 설정합니다.
     * 이를 통해 불필요한 인증 처리를 방지하고 성능을 개선합니다.
     *
     * 필터 제외 대상:
     * - OAuth2 로그인 경로 (/login/oauth2/*, /oauth2/*)
     * - 메인 페이지 (/)
     * - 정적 리소스 (/css/*, /js/*, /images/*)
     * - Swagger 문서 (/swagger-ui/*, /v3/api-docs/*)
     * - 헬스체크 (/actuator/health)
     * - 공개 API (로그인 상태 체크, HotTopic 조회 등)
     *
     * @param request 필터 적용 여부를 확인할 HTTP 요청 객체
     * @return 필터 제외 여부 (true: 제외, false: 적용)
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();


        // OAuth2 관련 경로 제외
        if (path.startsWith("/login/oauth2/") || path.startsWith("/oauth2/")) {
            return true;
        }

        // 정적 리소스 제외
        if (path.equals("/") ||
                path.startsWith("/css/") ||
                path.startsWith("/js/") ||
                path.startsWith("/images/") ||
                path.equals("/favicon.ico")) {
            return true;
        }

        // Swagger 관련 제외
        if (path.startsWith("/swagger-ui/") ||
                path.startsWith("/v3/api-docs/") ||
                path.startsWith("/api-docs/") ||
                path.equals("/swagger.html")) {
            return true;
        }

        // 헬스체크 제외
        if (path.equals("/actuator/health")) {
            return true;
        }

        // 공개 API 제외
        if (path.equals("/api/auth/status") ||
                path.startsWith("/api/hottopic/")) {
            return true;
        }

        // 기타 공개 테스트 엔드포인트
        if (path.equals("/run-batch") ||
                path.startsWith("/elasticsearch/") ||
                path.startsWith("/monitoring/test/") ||
                path.startsWith("/kakao/") ||
                path.startsWith("/api/admin/")) {
            return true;
        }

        return false;

    }
}