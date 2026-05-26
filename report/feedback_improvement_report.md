# 피드백 개선 보고서

**작성일**: 2026-05-26  
**대상 피드백**: 중복 기사 필터링 / 피드백 기능 활용 / 최신 기사 정렬

---

## 피드백 요약

| 피드백 항목 | 내용 | 개선 여부 |
|-------------|------|-----------|
| 타이틀만 다르고 내용이 같은 기사가 많음 | 중복 기사 필터링 필요 | ✅ 구현 완료 |
| 핫토픽·배달 기사에 오래된 기사가 먼저 뜸 | 최신 기사 우선 정렬 필요 | ✅ 구현 완료 |
| 피드백 기능이 단순 구현 수준, 활용도 낮음 | 피드백 기반 개발 필요 | ⚠️ 부분 구현 (현황 분석 포함) |

---

## 1. 중복 기사 필터링 (3단계 구현)

### 문제
DeepSearch API로 수집되는 뉴스는 같은 사건을 여러 언론사가 보도하면서 `[속보]`, `(종합)`, `[단독]` 등 태그만 다르고 내용이 사실상 동일한 기사가 다수 수집됨.

예시:
```
삼성전자 3분기 실적 발표 (한국경제)
[속보] 삼성전자 3분기 실적 발표 (한국경제)
삼성전자 3분기 실적 발표 (종합) (한국경제)
```

### 구현 내용

#### Level 1 — 배치 수집 중 인메모리 중복 제거
**파일**: `Global/News/Batch/configuration/BatchConfig.java` > `newsProcessor()`

배치가 API에서 기사를 읽는 시점에 스레드 안전(thread-safe) Set으로 실시간 중복을 차단.

```java
Set<String> seenTitles = Collections.synchronizedSet(new HashSet<>());

String normalizedTitle = dto.getTitle()
        .replaceAll("\\[.*?\\]|\\(.*?\\)", "")  // [속보], (종합) 등 태그 제거
        .replaceAll("\\s+", "")                  // 공백 제거
        .toLowerCase();                          // 소문자 통일

String titleKey = dto.getPublisher() + "::" +
        (normalizedTitle.length() > 30
                ? normalizedTitle.substring(0, 30)
                : normalizedTitle);              // 앞 30자로 키 생성

if (!seenTitles.add(titleKey)) {
    return null;  // null 반환 → writer에 전달되지 않음 (skip)
}
```

**효과**: 동일 배치 실행 내에서 같은 언론사의 유사 제목 기사는 첫 번째 것만 저장.

---

#### Level 2 — 배치 완료 후 DB 사후 처리
**파일**: `Global/News/Batch/listener/BatchJobCompletionListener.java`

배치 Job이 `COMPLETED` 상태로 끝나면 자동 실행되는 `afterJob()` 리스너에서 3단계 SQL을 순차 실행.

**Step 1: 완전 동일 제목+언론사 중복 제거**

```sql
DELETE FROM news WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY title, publisher
                   ORDER BY published_at DESC, id DESC
               ) AS rn
        FROM news
        WHERE published_at >= CURDATE() - INTERVAL 1 DAY
          AND published_at < CURDATE()
    ) t WHERE t.rn > 1
);
```

→ 완전히 동일한 `(title, publisher)` 쌍 중 가장 최신 기사 1건만 유지.

**Step 2: 정규화 제목 유사 중복 제거**

```sql
DELETE FROM news WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY
                       LEFT(REPLACE(REGEXP_REPLACE(title,'\\[.*?\\]|\\(.*?\\)|\\s',''),' ',''), 30),
                       publisher
                   ORDER BY published_at DESC, id DESC
               ) AS rn
        FROM news
        WHERE published_at >= CURDATE() - INTERVAL 1 DAY
          AND published_at < CURDATE()
    ) t WHERE t.rn > 1
);
```

→ `[속보]` · `(종합)` 등을 제거하고 남은 제목 앞 30자 기준으로 같은 언론사의 유사 기사를 하나로 통합.

**Step 3: 노이즈성 기사 제거**

```sql
DELETE FROM news WHERE (
    title REGEXP '^\\[(속보|단독|긴급|알림)\\]$'   -- 제목이 태그만 있는 기사
    OR title LIKE '%〔%'                            -- 특수 괄호 광고성 패턴
    OR title LIKE '%▶%'                             -- 영상 유도 아이콘 패턴
    OR LENGTH(title) < 10                           -- 너무 짧은 제목
)
AND published_at >= CURDATE() - INTERVAL 1 DAY
AND published_at < CURDATE();
```

→ 클릭베이트, 태그만 있는 단순 속보, 광고성 기사 제거.

### 중복 필터링 흐름 요약

```
DeepSearch API 응답
        │
        ▼
[Level 1] newsProcessor()
  ・ [속보](종합) 태그 제거 후 앞 30자 비교
  ・ 동일 키 → null 반환(skip)
        │
        ▼
  DB INSERT
        │
        ▼
[Level 2] afterJob() 리스너 (배치 완료 직후 자동 실행)
  ・ Step 1: 완전 동일 (title+publisher) 중복 제거
  ・ Step 2: 정규화 유사 제목 중복 제거
  ・ Step 3: 노이즈·광고성 기사 제거
        │
        ▼
  정제된 뉴스 DB
```

### 개선 효과

| 항목 | 개선 전 | 개선 후 |
|------|---------|---------|
| `[속보]` 태그 중복 | 수집됨 | 수집 단계에서 차단 |
| `(종합)` 태그 중복 | 수집됨 | 수집 단계에서 차단 |
| 완전 동일 기사 | DB에 그대로 적재 | 최신 1건만 유지 |
| 유사 제목 기사 | DB에 그대로 적재 | 정규화 30자 기준 통합 |
| 노이즈 기사 | 수신자에게 배달 | 배치 직후 자동 제거 |

---

## 2. 최신 기사 우선 정렬

### 문제
DeepSearch API 기본 정렬이 `published_at` 오름차순(오래된 것 먼저)이어서 배치 수집 시 오래된 기사가 DB에 먼저 들어가고, ElasticSearch 검색 결과도 관련도(score) 기준이라 최신 기사가 묻히는 문제.

### 구현 내용

**① DeepSearch API 요청 정렬 변경**  
파일: `Global/News/Batch/configuration/BatchConfig.java`

```java
// Before
.queryParam("order", "published_at")

// After
.queryParam("order", "-published_at")   // 마이너스 접두사 = 내림차순(최신순)
```

`getAPIResponse()` / `getNewsList()` 두 메서드 모두 적용 → 수집 시점부터 최신 기사 우선.

**② ElasticSearch 검색 결과 정렬 변경**  
파일: `Domain/Kakao/service/KakaoNewsService.java`

```java
// Before: 관련도(score) 단일 정렬
.sort(sort -> sort.score(sc -> sc.order(SortOrder.Desc)))

// After: 최신일자 우선, 관련도 보조
.sort(sort -> sort.field(f -> f
        .field("published_at")
        .order(SortOrder.Desc)))         // 1순위: 최신 기사
.sort(sort -> sort.score(sc -> sc
        .order(SortOrder.Desc)))         // 2순위: 관련도
```

**효과**: 핫토픽 토픽 클릭 시 표시되는 관련 기사가 최신순으로 정렬됨. 같은 날짜라면 관련도 높은 기사가 먼저 표시.

---

## 3. 피드백 기능 — 현황 및 한계 분석

### 현재 구현 상태

피드백은 `feedback` 테이블에 히스토리 1건당 1개의 레코드로 관리되며, 두 가지 평가 항목을 갖는다.

**`Feedback` 엔티티 구조**

```java
@Entity
@Table(name = "feedback")
public class Feedback {
    @Id
    private Long id;           // history.id와 동일한 PK (1:1 매핑)

    @OneToOne
    private History history;

    private Long keywordReflection;  // 키워드 반영도: +1(맞음) / -1(안 맞음) / 0(미입력)
    private Long contentQuality;     // 콘텐츠 품질: +1(좋음) / -1(나쁨) / 0(미입력)
}
```

**API 엔드포인트**

| 메서드 | URL | 기능 |
|--------|-----|------|
| POST | `/sub/feedback/keyword` | 키워드 반영도 피드백 저장 |
| POST | `/sub/feedback/content` | 콘텐츠 품질 피드백 저장 |

**토글 동작**: 같은 값을 두 번 누르면 취소(0으로 초기화). 반대 값을 누르면 즉시 전환.

```java
if (currentValue.equals(requestValue))
    feedback.setKeywordReflection(0L);  // 같은 값 → 취소
else
    feedback.setKeywordReflection(requestValue);  // 다른 값 → 반영
```

### 피드백 기능 한계 (피드백 내용 반영)

> "다음 추천 시 참고 되는 가중치는 키워드 등록에 따라 달라지므로 별 의미가 없음"

현재 피드백은 **저장만 하고 추천에 반영되지 않는** 상태. 구체적으로:

| 항목 | 현재 상태 | 문제점 |
|------|-----------|--------|
| `keywordReflection` | DB 저장만 됨 | 다음 배달 키워드 선정에 미반영 |
| `contentQuality` | DB 저장만 됨 | 언론사 우선순위 조정에 미반영 |
| 피드백 집계 | 없음 | 유저별/키워드별 통계 없음 |
| 추천 알고리즘 | 없음 | 피드백 기반 가중치 계산 없음 |

### 피드백 활용을 위한 개선 방향 (제안)

피드백 내용에서 "선호 신문사 순위 입력 → 해당 신문사 기사 우선 배달" 예시를 제시한 만큼, 다음 두 방향이 유효하다.

**방향 A: `contentQuality` 피드백 → 언론사 가중치**

```
사용자가 특정 언론사 기사에 contentQuality = +1 반복
    → 해당 언론사 점수 누적
    → ElasticSearch 검색 시 해당 언론사 기사 boost 적용
    → 배달 기사 선정 시 해당 언론사 우선
```

구현 포인트: `publisher` 별 `contentQuality` 합산 집계 테이블 추가, ES `function_score` 쿼리에 언론사 boost 반영.

**방향 B: `keywordReflection` 피드백 → 키워드 정교화**

```
사용자가 특정 히스토리에 keywordReflection = -1
    → 해당 기사와 등록 키워드의 연관성이 낮다고 판단
    → 해당 기사의 ES 태그/섹션 정보를 분석
    → 동일 패턴의 기사를 다음 배달에서 제외
```

구현 포인트: 부정 피드백 임계치(예: 3회) 초과 시 해당 키워드-섹션 조합을 블랙리스트 처리.

---

## 최종 정리

| 피드백 항목 | 구현 위치 | 상태 |
|-------------|-----------|------|
| 중복 기사 필터링 — 수집 단계 인메모리 | `BatchConfig.newsProcessor()` | ✅ 완료 |
| 중복 기사 필터링 — DB 완전 동일 제거 | `BatchJobCompletionListener` Step1 | ✅ 완료 |
| 중복 기사 필터링 — 유사 제목 제거 | `BatchJobCompletionListener` Step2 | ✅ 완료 |
| 중복 기사 필터링 — 노이즈 제거 | `BatchJobCompletionListener` Step3 | ✅ 완료 |
| 최신 기사 우선 정렬 — API 수집 | `BatchConfig` `-published_at` | ✅ 완료 |
| 최신 기사 우선 정렬 — ES 검색 | `KakaoNewsService` `published_at DESC` | ✅ 완료 |
| 피드백 저장 (키워드 반영도) | `FeedBackService.keywordFeedBack()` | ✅ 완료 |
| 피드백 저장 (콘텐츠 품질) | `FeedBackService.contentQualityFeedback()` | ✅ 완료 |
| 피드백 → 추천 반영 | 미구현 | ⚠️ 추가 개발 필요 |
| 언론사 선호도 기반 개인화 | 미구현 | ⚠️ 추가 개발 필요 |
