package Baemin.News_Deliver.Global.OAuth2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * OAuth2 사용자 정보 로딩을 커스터마이징한 서비스
 *
 * Spring Security의 기본 OAuth2UserService를 확장하여
 * 카카오 OAuth2 로그인에 특화된 사용자 정보 처리를 담당합니다.
 *
 * @author 박채원
 * 카카오에서 제공하는 사용자 정보를 검증하고 Spring Security에서
 * 사용할 수 있는 OAuth2User 객체로 변환합니다.
 * 주요 기능:
 * - 카카오 OAuth2 제공자 검증 (다른 제공자 차단)
 * - 카카오 사용자 정보 추출 및 로깅
 * - Spring Security 인증 객체 생성
 * - 사용자 권한 설정 (ROLE_USER)
 *   추가적으로 사용자 권한 설정 에 ROLE_USER 가 왜 들어가느냐 하면 스프링 시큐리티에서 관례적으로 쓴다고 합니다
 * @see DefaultOAuth2UserService
 * @see OAuth2LoginSuccessHandler
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    /**
     * OAuth2 사용자 정보를 로드하고 커스터마이징합니다
     *
     * OAuth2 제공자(카카오)로부터 받은 사용자 정보를 검증하고,
     * Spring Security에서 사용할 수 있는 형태로 변환합니다.
     * 카카오가 아닌 다른 OAuth2 제공자의 경우 예외를 발생시켜 차단합니다.
     *
     * 처리 과정:
     * 1. 기본 OAuth2UserService로 사용자 정보 획득
     * 2. OAuth2 제공자 검증 (카카오만 허용)
     * 3. 카카오 사용자 정보 로깅 및 검증
     * 4. 카카오 ID 추출
     * 5. Spring Security OAuth2User 객체 생성
     *
     * @param userRequest OAuth2 인증 요청 정보 (클라이언트 등록 정보, 액세스 토큰 포함)
     * @return Spring Security에서 사용할 OAuth2User 객체
     * @throws OAuth2AuthenticationException 카카오가 아닌 다른 OAuth2 제공자인 경우
     * @throws OAuth2AuthenticationException 사용자 정보 로딩에 실패한 경우
     */
    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {

        // 1. 기본 OAuth2UserService로 사용자 정보 가져오기
        OAuth2User oauth2User = super.loadUser(userRequest);

        // 2. OAuth2 제공자 확인 (카카오만 지원)
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        log.info("OAuth2 로그인 제공자: {}", registrationId);

        if (!"kakao".equals(registrationId)) {
            throw new OAuth2AuthenticationException("지원하지 않는 OAuth2 제공자입니다: " + registrationId);
        }

        // 3. 카카오에서 받은 사용자 정보 로깅
        Map<String, Object> attributes = oauth2User.getAttributes();
        log.info("카카오 사용자 정보: {}", attributes);

        // 4. 카카오 ID 추출
        String kakaoId = String.valueOf(attributes.get("id"));
        log.info("카카오 ID: {}", kakaoId);

        // 5. 사용자 이름 속성 확인
        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails()
                .getUserInfoEndpoint()
                .getUserNameAttributeName();

        // 6. OAuth2User 객체 생성 및 반환
        return new DefaultOAuth2User(
                Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                userNameAttributeName  // 카카오의 경우 "id"
        );
    }
}