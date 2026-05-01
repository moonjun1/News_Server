package Baemin.News_Deliver.Domain.Mypage.Repository;

import Baemin.News_Deliver.Domain.Auth.Entity.User;
import Baemin.News_Deliver.Domain.Mypage.Entity.Setting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Setting 엔티티용 JPA Repository
 *
 * <p>사용자의 유효한 설정만 필터링하여 조회하는 메서드를 제공합니다.</p>
 */
@Repository
public interface SettingRepository extends JpaRepository<Setting, Long> {

    /**
     * 사용자별 유효한 설정 목록 조회
     *
     * <p>조건: 삭제되지 않았고, 종료일(endDate)이 현재 시간보다 이후인 설정만 반환</p>
     *
     * @param user 사용자 엔티티
     * @param now 현재 시간
     * @return 유효한 설정 리스트
     */
    @Query("SELECT s FROM Setting s WHERE s.user = :user AND s.endDate > :now AND s.isDeleted = false")
    List<Setting> findActiveSettings(@Param("user") User user, @Param("now") LocalDateTime now);



    /**
     * ID를 기준으로 설정 정보를 조회하며, 연관된 요일 정보({@link Setting#getDays()})를 즉시 로딩합니다.
     *
     * <p>지연 로딩(Lazy)을 피하고, Setting과 Days를 함께 조회하기 위해 fetch join을 사용합니다.</p>
     *
     * @param id 설정의 ID
     * @return {@link Setting} 엔티티 및 관련된 Days 리스트를 포함한 Optional 객체
     */
    @Query("""
    SELECT s FROM Setting s
    LEFT JOIN FETCH s.days
    WHERE s.id = :id
""")
    Optional<Setting> findByIdWithDays(@Param("id") Long id);

    /**
     * Setting Service의 getAllSettings 메서드에 쓰일 메서드
     * 현재 시간 기준으로 유효한 모든 세팅을 가져옴
     * - 시작일이 현재보다 이전이거나 같고
     * - 종료일이 없거나, 현재보다 이후이며
     * - 삭제되지 않은 세팅만 조회
     *
     * @param now 현재 시간
     * @return 유효한 모든 세팅 리스트
     */
    @Query("SELECT DISTINCT s FROM Setting s " +
            "JOIN FETCH s.days d " +
            "WHERE s.startDate <= :now " +
            "AND (s.endDate IS NULL OR s.endDate >= :now) " +
            "AND (s.isDeleted IS NULL OR s.isDeleted = false)")
    List<Setting> findAllValidSettingsWithDays(@Param("now") LocalDateTime now);

    /**
     * @param id 세팅 고유 번호
     * @return 세팅
     */
    @Query("""
            SELECT s FROM Setting s
            LEFT JOIN FETCH s.days
            LEFT JOIN FETCH s.keywords
            LEFT JOIN FETCH s.blockKeywords
            WHERE s.id = :id
            """)
    Optional<Setting> findByIdWithAllAssociations(@Param("id") Long id);


}
