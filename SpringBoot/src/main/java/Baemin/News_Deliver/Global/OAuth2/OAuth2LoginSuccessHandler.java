package Baemin.News_Deliver.Global.OAuth2;

import Baemin.News_Deliver.Domain.Auth.Entity.Auth;
import Baemin.News_Deliver.Domain.Auth.Entity.User;
import Baemin.News_Deliver.Domain.Auth.Exception.AuthException;
import Baemin.News_Deliver.Domain.Auth.Repository.AuthRepository;
import Baemin.News_Deliver.Domain.Auth.Repository.UserRepository;
import Baemin.News_Deliver.Global.Exception.ErrorCode;
import Baemin.News_Deliver.Global.JWT.JwtTokenProvider;
import Baemin.News_Deliver.Global.Redis.RedisService;
import Baemin.News_Deliver.Global.ResponseObject.ApiResponseWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * OAuth2 로그인 성공 시 후속 처리를 담당하는 핸들러
 *
 * 카카오 OAuth2 로그인이 성공한 후 실행되어 다음과 같은 작업을 수행합니다:
 *
 * @author 박채원
 * - 카카오에서 발급받은 리프레시 토큰 추출 및 저장
 * - 사용자 정보 데이터베이스 저장 (신규 가입 또는 기존 사용자 확인)
 * - 서비스 자체 JWT 토큰 발급 및 Redis 저장
 * - 프론트엔드로 토큰 정보와 함께 리다이렉트
 *
 * Spring Security의 SimpleUrlAuthenticationSuccessHandler를 확장하여
 * OAuth2 인증 성공 후의 커스텀 로직을 구현합니다.
 *
 * @see SimpleUrlAuthenticationSuccessHandler
 * @see CustomOAuth2UserService
 * @see JwtTokenProvider
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final UserRepository userRepository;
    private final AuthRepository authRepository;
    private final ObjectMapper objectMapper;
    private final OAuth2AuthorizedClientService authorizedClientService;

    /**
     * OAuth2 로그인 성공 시 실행되는 메인 처리 메서드
     *
     * 카카오 OAuth2 인증이 성공한 후 사용자 정보 저장, JWT 토큰 발급,
     * 프론트엔드 리다이렉트 등의 전체 로그인 플로우를 처리합니다.
     * 각 단계에서 오류가 발생하면 적절한 ErrorCode와 함께 에러 응답을 반환합니다.
     *
     * 처리 순서:
     * 1. 카카오 사용자 정보에서 카카오 ID 추출
     * 2. 카카오 리프레시 토큰 추출
     * 3. 사용자 정보 데이터베이스 저장 (없으면 신규 생성)
     * 4. 카카오 리프레시 토큰을 Auth 테이블에 저장
     * 5. 서비스 자체 JWT 액세스/리프레시 토큰 생성
     * 6. Redis에 JWT 토큰 저장
     * 7. 토큰 정보와 함께 프론트엔드로 리다이렉트
     *
     * @param request HTTP 요청 객체
     * @param response HTTP 응답 객체
     * @param authentication OAuth2 인증 성공 정보 (사용자 정보 포함)
     * @throws IOException 응답 처리 중 입출력 오류 발생 시
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String kakaoId = oauth2User.getAttribute("id").toString();
        log.info("카카오 로그인 성공: kakaoId = {}", kakaoId);

        try {
            // 1. 카카오 리프레시 토큰 추출
            String kakaoRefreshToken = extractKakaoRefreshToken(authentication);

            // 2. User 테이블에 사용자 정보 저장 (없으면 생성)
            User user = findOrCreateUser(kakaoId);

            // 3. Auth 테이블에 카카오 리프레시 토큰 저장
            saveKakaoRefreshToken(user, kakaoRefreshToken);

            // 4. 우리 서비스 JWT 토큰 생성
            String ourAccessToken = jwtTokenProvider.generateAccessToken(kakaoId);
            String ourRefreshToken = jwtTokenProvider.generateRefreshToken(kakaoId);

            // 5. Redis에 우리 서비스 JWT 토큰 저장
            redisService.saveAccessToken(kakaoId, ourAccessToken);
            redisService.saveRefreshToken(kakaoId, ourRefreshToken);

            // 6. 프론트엔드로 리다이렉트 (토큰 정보 포함)
            String redirectUrl = createRedirectUrl(ourAccessToken, ourRefreshToken, user);
            response.sendRedirect(redirectUrl);

            log.info("로그인 처리 완료: kakaoId = {}, userId = {}", kakaoId, user.getId());

        } catch (AuthException e) {
            log.error("로그인 처리 중 Auth 예외 발생: kakaoId = {}, errorCode = {}", kakaoId, e.getErrorcode().getErrorCode());
            sendErrorResponse(response, e.getErrorcode());
        } catch (Exception e) {
            log.error("로그인 처리 중 예상치 못한 오류 발생: kakaoId = {}, error = {}", kakaoId, e.getMessage());
            sendErrorResponse(response, ErrorCode.OAUTH2_PROCESS_FAILED);
        }
    }

    /**
     * OAuth2 인증 정보에서 카카오 리프레시 토큰을 추출합니다
     *
     * Spring Security의 OAuth2AuthorizedClientService를 사용하여
     * 카카오에서 발급받은 리프레시 토큰을 추출합니다.
     * 이 토큰은 추후 카카오 액세스 토큰 갱신에 사용됩니다.
     *
     * @param authentication OAuth2 인증 성공 정보
     * @return 카카오에서 발급받은 리프레시 토큰, 추출에 실패한 경우 null
     * @throws AuthException 토큰 추출 중 오류가 발생한 경우 (ErrorCode.KAKAO_TOKEN_INVALID)
     */
    private String extractKakaoRefreshToken(Authentication authentication) {
        try {
            OAuth2AuthorizedClient authorizedClient = authorizedClientService
                    .loadAuthorizedClient("kakao", authentication.getName());

            if (authorizedClient != null) {
                OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();
                if (refreshToken != null) {
                    return refreshToken.getTokenValue();
                }
            }
        } catch (Exception e) {
            log.error("카카오 리프레시 토큰 추출 실패: {}", e.getMessage());
            throw new AuthException(ErrorCode.KAKAO_TOKEN_INVALID);
        }
        return null;
    }

    /**
     * 카카오 ID로 사용자를 조회하거나 신규 생성합니다
     *
     * 데이터베이스에서 카카오 ID에 해당하는 사용자를 조회하고,
     * 존재하지 않는 경우 새로운 사용자를 생성하여 저장합니다.
     * 첫 로그인 사용자의 회원가입 과정을 자동으로 처리합니다.
     *
     * @param kakaoId 조회 또는 생성할 사용자의 카카오 ID
     * @return 조회되거나 새로 생성된 사용자 엔티티
     * @throws AuthException 사용자 생성 중 오류가 발생한 경우 (ErrorCode.USER_CREATION_FAILED)
     */
    private User findOrCreateUser(String kakaoId) {
        return userRepository.findByKakaoId(kakaoId)
                .orElseGet(() -> {
                    try {
                        User newUser = User.builder()
                                .kakaoId(kakaoId)
                                .build();
                        User savedUser = userRepository.save(newUser);
                        log.info("새 사용자 생성: kakaoId = {}, userId = {}", kakaoId, savedUser.getId());
                        return savedUser;
                    } catch (Exception e) {
                        log.error("사용자 생성 실패: kakaoId = {}, error = {}", kakaoId, e.getMessage());
                        throw new AuthException(ErrorCode.USER_CREATION_FAILED);
                    }
                });
    }

    /**
     * 사용자의 카카오 리프레시 토큰을 데이터베이스에 저장합니다
     *
     * 기존 사용자인 경우 Auth 테이블의 리프레시 토큰을 업데이트하고,
     * 신규 사용자인 경우 새로운 Auth 레코드를 생성합니다.
     * 저장된 토큰은 추후 카카오 API 호출 시 액세스 토큰 갱신에 사용됩니다.
     *
     * @param user 토큰을 저장할 사용자 엔티티
     * @param kakaoRefreshToken 저장할 카카오 리프레시 토큰
     * @throws AuthException 토큰 저장 중 오류가 발생한 경우 (ErrorCode.TOKEN_STORAGE_FAILED)
     */
    private void saveKakaoRefreshToken(User user, String kakaoRefreshToken) {
        try {
            Optional<Auth> optionalAuth = authRepository.findByUser(user);

            if (optionalAuth.isPresent()) {
                // 기존 Auth 업데이트
                Auth auth = optionalAuth.get();
                auth.updateKakaoRefreshToken(kakaoRefreshToken);
                authRepository.save(auth);
                log.info("기존 사용자 토큰 업데이트: userId = {}", user.getId());
            } else {
                // 새 Auth 생성
                Auth newAuth = Auth.builder()
                        .user(user)
                        .kakaoRefreshToken(kakaoRefreshToken)
                        .build();
                authRepository.save(newAuth);
                log.info("새 사용자 토큰 저장: userId = {}", user.getId());
            }
        } catch (Exception e) {
            log.error("카카오 토큰 저장 실패: userId = {}, error = {}", user.getId(), e.getMessage());
            throw new AuthException(ErrorCode.TOKEN_STORAGE_FAILED);
        }
    }

    /**
     * 프론트엔드로 리다이렉트할 URL을 생성합니다
     *
     * 발급된 JWT 토큰들과 사용자 기본 정보를 JSON으로 직렬화하고,
     * URL 인코딩하여 프론트엔드로 전달할 리다이렉트 URL을 생성합니다.
     * 프론트엔드에서는 이 토큰 정보를 파싱하여 로컬 저장소에 저장하고 사용합니다.
     *
     * @param accessToken 발급된 JWT 액세스 토큰
     * @param refreshToken 발급된 JWT 리프레시 토큰
     * @param user 로그인한 사용자 정보
     * @return 토큰 정보가 포함된 프론트엔드 리다이렉트 URL
     */
    private String createRedirectUrl(String accessToken, String refreshToken, User user) {
        try {
            Map<String, Object> tokenData = new HashMap<>();
            tokenData.put("accessToken", accessToken);
            tokenData.put("refreshToken", refreshToken);
            tokenData.put("tokenType", "Bearer");
            tokenData.put("expiresIn", 1800);
            tokenData.put("user", Map.of(
                    "id", user.getId(),
                    "kakaoId", user.getKakaoId()
            ));


            String tokenJson = objectMapper.writeValueAsString(tokenData);
            String encodedToken = java.net.URLEncoder.encode(tokenJson, "UTF-8");

            return "https://likelionnews.click/?token=" + encodedToken;
//              로컬용
//            return "http://localhost:5173/?token=" + encodedToken;
        } catch (Exception e) {
            log.error("리다이렉트 URL 생성 실패: {}", e.getMessage());
            return "https://likelionnews.click/?error=true";
//            로컬용
//            return "/?error=true";
        }
    }

    /**
     * 오류 발생 시 표준화된 에러 응답을 전송합니다
     *
     * ErrorCode를 기반으로 ApiResponseWrapper 형태의 표준화된 에러 응답을 생성하고,
     * HTTP 상태 코드와 함께 클라이언트에게 전송합니다.
     * JSON 형태로 응답하여 프론트엔드에서 일관된 방식으로 오류를 처리할 수 있도록 합니다.
     *
     * @param response HTTP 응답 객체
     * @param errorCode 발생한 오류에 해당하는 ErrorCode
     * @throws IOException 응답 전송 중 입출력 오류 발생 시
     */
    private void sendErrorResponse(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        ApiResponseWrapper<String> errorResponse = new ApiResponseWrapper<>(
                null,
                errorCode.getMessage()
        );
        // errorCode 필드 설정 (ApiResponseWrapper에 errorCode 필드 추가 필요 시)

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(errorCode.getHttpStatus().value());
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}