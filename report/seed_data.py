#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
News_Deliver 목 데이터 시드 스크립트
- MySQL: news, hot_topic, user, setting, setting_keyword, setting_block_keyword, days, history
- ElasticSearch: news-index-nori 인덱스 벌크 인덱싱
"""
import mysql.connector
import json, requests, datetime, sys

# ─── 1. MySQL 연결 ───────────────────────────────────────
conn = mysql.connector.connect(
    host="127.0.0.1", port=3306,
    user="root", password="1234",
    database="backendDB", charset="utf8mb4",
    use_unicode=True, collation="utf8mb4_unicode_ci"
)
cur = conn.cursor()
print("✅ MySQL 연결 성공")

# ─── 2. 기존 데이터 정리 ────────────────────────────────
cur.execute("SET FOREIGN_KEY_CHECKS=0")
cur.execute("DELETE FROM history")
cur.execute("DELETE FROM days")
cur.execute("DELETE FROM setting_block_keyword")
cur.execute("DELETE FROM setting_keyword")
cur.execute("DELETE FROM setting")
cur.execute("DELETE FROM auth")
cur.execute("DELETE FROM feedback")
cur.execute("DELETE FROM user")
cur.execute("DELETE FROM news")
cur.execute("DELETE FROM hot_topic")
cur.execute("SET FOREIGN_KEY_CHECKS=1")
conn.commit()
print("✅ 기존 데이터 정리 완료")

# ─── 3. news 50건 삽입 ──────────────────────────────────
yesterday = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()

news_data = [
    # 삼성전자 (economy/tech)
    ("삼성전자, 2분기 영업이익 10조 돌파 전망… 반도체 회복세 뚜렷",
     "삼성전자가 올 2분기에 영업이익 10조 원을 넘어설 것으로 증권가는 전망했다. HBM 수요 급증과 파운드리 가동률 회복이 주된 요인으로 분석된다.",
     "한국경제", "https://www.hankyung.com/article/sample1", f"{yesterday}T08:30:00", "economy"),
    ("삼성전자 갤럭시 S25 시리즈, 출시 두 달 만에 글로벌 판매 1000만 대 돌파",
     "삼성전자의 플래그십 스마트폰 갤럭시 S25 시리즈가 출시 두 달 만에 글로벌 누적 판매량 1000만 대를 넘어섰다. 특히 AI 기능 강화가 소비자 반응을 이끌었다는 분석이다.",
     "매일경제", "https://www.mk.co.kr/article/sample1", f"{yesterday}T09:15:00", "tech"),
    ("삼성전자, 텍사스 파운드리 2나노 공정 양산 2026년 하반기 확정",
     "삼성전자 파운드리 사업부가 미국 텍사스 테일러 공장에서 2나노 공정 양산을 올해 하반기 시작한다고 공식 발표했다. TSMC와의 경쟁이 본격화될 전망이다.",
     "조선일보", "https://www.chosun.com/article/sample1", f"{yesterday}T10:00:00", "tech"),
    ("삼성전자 이재용 회장, AI 반도체 투자 확대 공언… 5년간 50조 투입",
     "이재용 삼성전자 회장이 향후 5년간 AI 반도체 연구개발에 50조 원을 투자하겠다고 밝혔다. HBM4 조기 양산과 차세대 파운드리 기술 확보가 핵심 목표다.",
     "연합뉴스", "https://www.yna.co.kr/article/sample1", f"{yesterday}T11:30:00", "economy"),

    # AI반도체
    ("SK하이닉스, HBM3E 12단 양산 본격화… 엔비디아 물량 100% 공급",
     "SK하이닉스가 5세대 HBM인 HBM3E 12단 제품의 양산을 본격화하며 엔비디아에 전량 독점 공급 중이라고 밝혔다. AI 서버 수요 급증으로 공급이 수요를 따라가기 어려운 상황이다.",
     "한국경제", "https://www.hankyung.com/article/sample2", f"{yesterday}T08:00:00", "tech"),
    ("엔비디아 블랙웰 GPU 탑재 AI서버, 국내 데이터센터 수요 폭증",
     "엔비디아의 최신 블랙웰 아키텍처 GPU를 탑재한 AI 서버 수요가 국내 데이터센터 업계에서 폭발적으로 증가하고 있다. KT·SK텔레콤·네이버클라우드가 대규모 발주에 나섰다.",
     "디지털타임스", "https://www.dt.co.kr/article/sample1", f"{yesterday}T09:45:00", "tech"),
    ("AI 반도체 글로벌 시장 2030년 500조 규모 성장 전망",
     "글로벌 시장조사기관 가트너는 AI 반도체 시장이 2030년까지 연평균 38% 성장해 500조 원 규모에 이를 것이라고 전망했다. 생성형 AI 확산이 핵심 동력으로 꼽혔다.",
     "전자신문", "https://www.etnews.com/article/sample1", f"{yesterday}T10:30:00", "tech"),
    ("국내 AI 반도체 스타트업 리벨리온, 시리즈C 3000억 투자 유치",
     "국내 AI 반도체 스타트업 리벨리온이 3000억 원 규모의 시리즈C 투자를 유치했다. 국내외 AI 데이터센터를 겨냥한 NPU 상용화가 급물살을 탈 전망이다.",
     "매일경제", "https://www.mk.co.kr/article/sample2", f"{yesterday}T14:00:00", "tech"),

    # 코스피
    ("코스피, 외국인 순매수에 2720선 돌파… 연중 최고치 경신",
     "코스피가 외국인 투자자의 대규모 순매수에 힘입어 2720선을 돌파하며 연중 최고치를 경신했다. 반도체·이차전지 업종이 상승을 주도했다.",
     "한국경제", "https://www.hankyung.com/article/sample3", f"{yesterday}T15:30:00", "economy"),
    ("코스피 시가총액 2000조 돌파… 삼성전자·SK하이닉스 쌍두마차",
     "코스피 시가총액이 처음으로 2000조 원을 돌파했다. 삼성전자와 SK하이닉스가 각각 1, 2위를 차지하며 반도체 업종이 전체의 35%를 차지했다.",
     "조선비즈", "https://www.chosunbiz.com/article/sample1", f"{yesterday}T16:00:00", "economy"),
    ("코스닥도 동반 강세… 바이오·2차전지 테마 동시 상승",
     "코스피 상승에 동반해 코스닥도 강세를 보였다. 바이오 업종과 2차전지 관련주가 동시에 5% 이상 상승하며 투자자 관심이 집중됐다.",
     "뉴시스", "https://www.newsis.com/article/sample1", f"{yesterday}T16:30:00", "economy"),

    # 의대정원
    ("정부, 의대 정원 2000명 증원 확정… 2027년부터 적용",
     "정부가 의과대학 정원을 현재보다 2000명 증원해 총 5058명으로 확정했다. 2027학년도 입시부터 적용되며 지방 의료 공백 해소가 주된 목표로 제시됐다.",
     "연합뉴스", "https://www.yna.co.kr/article/sample2", f"{yesterday}T09:00:00", "politics"),
    ("의대 교수들, 의대 정원 증원 반대 집단 사직 예고",
     "전국 의과대학 교수 비상대책위원회가 정부의 의대 정원 증원 결정에 반발해 집단 사직을 예고했다. 환자 진료 공백 우려가 커지고 있다.",
     "MBC", "https://www.mbc.co.kr/article/sample1", f"{yesterday}T10:00:00", "politics"),
    ("의대 정원 증원, 의료계와 정부 갈등 장기화 우려",
     "의대 정원 증원을 둘러싼 의료계와 정부의 갈등이 장기화될 조짐을 보이고 있다. 양측의 대화 채널이 사실상 단절된 가운데 중재안 마련이 시급하다는 지적이 나온다.",
     "KBS", "https://www.kbs.co.kr/article/sample1", f"{yesterday}T11:00:00", "politics"),

    # 손흥민
    ("손흥민, 토트넘과 시즌 종료 후 재계약 협상 돌입… 연봉 두 배 제시",
     "손흥민이 소속팀 토트넘과 시즌 종료 후 재계약 협상에 본격 돌입한 것으로 알려졌다. 구단 측이 현재 연봉의 두 배를 제시한 것으로 전해졌다.",
     "스포츠조선", "https://www.sportschosun.com/article/sample1", f"{yesterday}T08:00:00", "sports"),
    ("손흥민 시즌 20골 달성… EPL 한국인 최다골 신기록",
     "손흥민이 이번 시즌 잉글리시 프리미어리그에서 통산 20호 골을 기록하며 EPL 한국인 선수 단일 시즌 최다골 신기록을 세웠다.",
     "OSEN", "https://www.osen.co.kr/article/sample1", f"{yesterday}T20:30:00", "sports"),
    ("손흥민, 유럽 챔피언스리그 8강 진출 이끌어… 결승골 포함 2골 1도움",
     "손흥민이 토트넘의 챔피언스리그 8강 진출을 결승골 포함 2골 1도움으로 이끌었다. 경기 후 손흥민은 '팀 전체의 승리'라며 동료들에게 공을 돌렸다.",
     "연합뉴스", "https://www.yna.co.kr/article/sample3", f"{yesterday}T23:00:00", "sports"),

    # 저출생
    ("통계청, 올해 1분기 합계출산율 0.68 역대 최저 기록",
     "통계청이 발표한 인구동향에 따르면 올해 1분기 합계출산율이 0.68로 역대 최저치를 기록했다. 수도권을 중심으로 출생아 수 감소가 가속화되고 있다.",
     "연합뉴스", "https://www.yna.co.kr/article/sample4", f"{yesterday}T10:00:00", "society"),
    ("정부, 저출생 대책 패키지 발표… 육아휴직 급여 최대 250만원으로 상향",
     "정부가 저출생 문제 해결을 위한 종합 대책 패키지를 발표했다. 육아휴직 급여를 현행 150만 원에서 최대 250만 원으로 대폭 상향하는 내용이 핵심이다.",
     "KBS", "https://www.kbs.co.kr/article/sample2", f"{yesterday}T14:00:00", "politics"),
    ("서울시, 저출생 대응 신생아 출산 지원금 200만원 지급 확대",
     "서울시가 저출생 문제에 대응해 신생아 출산 지원금을 기존 100만 원에서 200만 원으로 확대 지급한다고 발표했다. 다자녀 가구에는 추가 지원도 이뤄진다.",
     "서울신문", "https://www.seoul.co.kr/article/sample1", f"{yesterday}T15:00:00", "society"),

    # 기준금리
    ("한국은행, 기준금리 3.0% 동결… 물가 안정세 확인 후 인하 검토",
     "한국은행 금융통화위원회가 기준금리를 현행 3.0%로 동결했다. 이창용 총재는 '물가 안정세를 2~3개월 더 확인한 뒤 인하 여부를 결정하겠다'고 밝혔다.",
     "한국경제", "https://www.hankyung.com/article/sample4", f"{yesterday}T10:00:00", "economy"),
    ("기준금리 동결에 시중은행 대출금리 줄줄이 동결… 가계 부담 지속",
     "한국은행의 기준금리 동결 결정에 따라 주요 시중은행들도 주택담보대출·신용대출 금리를 현행 수준으로 유지했다. 가계의 이자 부담이 계속되는 가운데 하반기 인하 기대감은 높아지고 있다.",
     "매일경제", "https://www.mk.co.kr/article/sample3", f"{yesterday}T12:00:00", "economy"),

    # 폭염
    ("서울 낮 최고기온 38.5도… 115년 만의 기록적 폭염",
     "서울의 낮 최고기온이 38.5도를 기록하며 1910년 기상 관측 이래 115년 만의 최고 기온을 경신했다. 기상청은 이번 폭염이 적어도 일주일은 이어질 것으로 내다봤다.",
     "KBS", "https://www.kbs.co.kr/article/sample3", f"{yesterday}T15:00:00", "society"),
    ("폭염 특보 전국 확대… 노인·야외근로자 건강 주의 당부",
     "기상청이 전국 대부분 지역에 폭염 경보를 발령했다. 고령자와 야외 근로자들의 온열 질환 예방을 위해 낮 시간 야외 활동을 자제할 것을 당부했다.",
     "연합뉴스", "https://www.yna.co.kr/article/sample5", f"{yesterday}T11:00:00", "society"),
    ("전력 수요 역대 최고치… 전국 에어컨 가동에 전력 대란 우려",
     "폭염으로 인한 냉방 수요 급증으로 전국 전력 수요가 역대 최고치를 기록했다. 산업통상자원부는 예비 전력 확보를 위해 비상 대책을 가동 중이라고 밝혔다.",
     "조선일보", "https://www.chosun.com/article/sample2", f"{yesterday}T17:00:00", "society"),

    # 넷플릭스
    ("넷플릭스 오징어게임 시즌3, 공개 24시간 만에 전 세계 1위",
     "넷플릭스 오리지널 '오징어게임' 시즌3가 공개 24시간 만에 전 세계 시청 순위 1위를 차지했다. 94개국에서 동시에 1위를 기록하며 시즌2의 기록을 뛰어넘었다.",
     "중앙일보", "https://www.joongang.co.kr/article/sample1", f"{yesterday}T09:00:00", "entertainment"),
    ("넷플릭스 한국 오리지널 콘텐츠 투자 3000억으로 확대",
     "넷플릭스가 2026년 한국 오리지널 콘텐츠 제작 투자 규모를 전년 대비 50% 늘린 3000억 원으로 확대한다고 발표했다. K-콘텐츠의 글로벌 흥행을 적극 활용하겠다는 전략이다.",
     "한국경제", "https://www.hankyung.com/article/sample5", f"{yesterday}T10:30:00", "entertainment"),
    ("넷플릭스, 국내 구독료 인상… 스탠다드 월 1만7000원으로",
     "넷플릭스가 국내 구독료를 6월부터 인상한다고 밝혔다. 스탠다드 요금제는 기존 1만3500원에서 1만7000원으로, 프리미엄은 1만7000원에서 2만1000원으로 각각 오른다.",
     "매일경제", "https://www.mk.co.kr/article/sample4", f"{yesterday}T14:00:00", "entertainment"),

    # 자율주행
    ("현대차, 레벨4 자율주행 택시 서울 강남 시범 운행 시작",
     "현대자동차가 레벨4 완전 자율주행 로봇택시 서비스를 서울 강남 일대에서 시범 운행한다고 발표했다. 안전 요원 없이 운행하는 첫 사례로 주목받고 있다.",
     "조선일보", "https://www.chosun.com/article/sample3", f"{yesterday}T08:00:00", "tech"),
    ("테슬라 FSD 한국 출시 임박… 국토부 임시 허가 획득",
     "테슬라의 완전 자율주행 소프트웨어(FSD)가 국토교통부로부터 임시 운행 허가를 받으며 한국 출시가 임박했다. 국내 자율주행 시장 경쟁이 본격화될 전망이다.",
     "전자신문", "https://www.etnews.com/article/sample2", f"{yesterday}T09:30:00", "tech"),
    ("자율주행 소프트웨어 사고 책임 법안, 국회 본회의 통과",
     "자율주행 차량 사고 발생 시 소프트웨어 제조사와 완성차 업체의 책임을 명확히 하는 자율주행법 개정안이 국회 본회의를 통과했다. 2027년부터 시행된다.",
     "연합뉴스", "https://www.yna.co.kr/article/sample6", f"{yesterday}T16:00:00", "tech"),
    ("카카오모빌리티, 자율주행 기반 로봇배달 서비스 수도권 확대",
     "카카오모빌리티가 자율주행 기술을 활용한 로봇 배달 서비스를 서울·경기 지역으로 확대한다. 심야 시간대 배달 수요 해소와 인건비 절감 효과가 기대된다.",
     "디지털타임스", "https://www.dt.co.kr/article/sample2", f"{yesterday}T11:00:00", "tech"),

    # 추가 다양한 뉴스
    ("KOSPI 2차전지 ETF, 3거래일 연속 순유입 1조 돌파",
     "코스피 2차전지 테마 ETF에 3거래일 연속 외국인 순매수가 유입되며 누적 1조 원을 돌파했다. LG에너지솔루션·삼성SDI 등이 강세를 보였다.",
     "뉴스1", "https://www.news1.kr/article/sample1", f"{yesterday}T13:00:00", "economy"),
    ("의대 정원 증원 후속 조치로 지역 의대 신설 논의 본격화",
     "의대 정원 증원 결정 이후 지방 의료 공백 해소를 위한 지역 의대 신설 논의가 본격화되고 있다. 전남·경북 등 의료 취약 지역이 후보로 거론되고 있다.",
     "지역신문", "https://www.localpress.co.kr/article/sample1", f"{yesterday}T13:30:00", "politics"),
    ("삼성전자 DS부문장, 2분기 HBM 출하량 전분기 대비 60% 증가 예고",
     "삼성전자 디바이스솔루션(DS)부문장이 2분기 HBM 출하량이 전분기 대비 60% 증가할 것이라고 전망했다. AI 데이터센터 투자 확대가 주요 배경이다.",
     "전자신문", "https://www.etnews.com/article/sample3", f"{yesterday}T14:30:00", "tech"),
    ("여름 폭염 속 수박·아이스크림 매출 급증… 편의점 냉음료 역대 최대",
     "기록적인 폭염이 이어지면서 수박·아이스크림 등 여름 식품 매출이 전년 대비 40% 이상 급증했다. 편의점 냉음료 매출도 역대 최대를 기록했다.",
     "한국경제", "https://www.hankyung.com/article/sample6", f"{yesterday}T15:30:00", "economy"),
    ("저출생 대응 교육부, 대학 정원 5만명 추가 감축 검토",
     "교육부가 저출생에 따른 학령인구 감소에 대응해 대학 정원을 향후 5년간 5만 명 추가 감축하는 방안을 검토 중이다. 대학 구조 조정이 가속화될 전망이다.",
     "중앙일보", "https://www.joongang.co.kr/article/sample2", f"{yesterday}T10:30:00", "society"),
    ("손흥민, 태극마크 복귀 확정… 6월 월드컵 예선 출전",
     "손흥민이 부상 회복을 마치고 태극마크를 다시 달기로 확정됐다. 6월 예정된 2026 북중미 월드컵 아시아 최종예선에 출전해 팀을 이끌 예정이다.",
     "스포츠서울", "https://www.sportsseoul.com/article/sample1", f"{yesterday}T11:30:00", "sports"),
    ("기준금리 인하 기대감에 부동산 시장 꿈틀… 서울 아파트 거래량 증가",
     "하반기 기준금리 인하 기대감이 높아지면서 서울 아파트 거래량이 3개월 만에 반등했다. 강남·마포·송파구 등 핵심 지역을 중심으로 호가가 오르고 있다.",
     "조선비즈", "https://www.chosunbiz.com/article/sample2", f"{yesterday}T12:30:00", "economy"),
    ("AI반도체 설계 인력 부족 심각… 대기업 스카우트 전쟁 시작",
     "AI 반도체 설계 분야의 핵심 인력 부족 문제가 심각해지고 있다. 삼성전자·SK하이닉스 등 대기업들이 석박사급 인재 확보를 위한 경쟁에 돌입했다.",
     "연합뉴스", "https://www.yna.co.kr/article/sample7", f"{yesterday}T16:30:00", "tech"),
    ("넷플릭스 경쟁사 웨이브·왓챠, 구독자 유지 위해 요금 동결 선언",
     "넷플릭스 구독료 인상 발표 이후 국내 OTT 경쟁사 웨이브와 왓챠가 당분간 요금을 동결하겠다고 밝혔다. 구독자 이탈 방지를 위한 전략으로 풀이된다.",
     "디지털타임스", "https://www.dt.co.kr/article/sample3", f"{yesterday}T17:30:00", "entertainment"),
    ("2026 북중미 월드컵 한국 대표팀 최종 엔트리 26명 확정",
     "대한축구협회가 2026 북중미 월드컵 한국 대표팀 최종 엔트리 26명을 발표했다. 손흥민이 주장으로서 공격을 이끌며 황희찬·이재성 등이 주전으로 포함됐다.",
     "KBS", "https://www.kbs.co.kr/article/sample4", f"{yesterday}T18:00:00", "sports"),
    ("코스피 외국인 이틀 연속 1조 순매수… 환율 효과로 저평가 부각",
     "외국인 투자자들이 코스피에서 이틀 연속 1조 원 이상 순매수하며 시장을 견인했다. 원달러 환율 하락으로 인한 환차익 기대와 국내 기업 저평가 매력이 부각됐다.",
     "매일경제", "https://www.mk.co.kr/article/sample5", f"{yesterday}T16:00:00", "economy"),
]

insert_sql = """
INSERT INTO news (title, summary, publisher, content_url, published_at, sections, send)
VALUES (%s, %s, %s, %s, %s, %s, %s)
"""
news_ids = []
for row in news_data:
    cur.execute(insert_sql, (*row, False))
    news_ids.append(cur.lastrowid)
conn.commit()
print(f"✅ 뉴스 {len(news_data)}건 삽입 완료 (IDs: {news_ids[0]}~{news_ids[-1]})")

# ─── 4. hot_topic 10건 ──────────────────────────────────
yesterday_dt = datetime.datetime.now() - datetime.timedelta(days=1)
hot_topics = [
    (1, "삼성전자", 1523), (2, "AI반도체", 1287), (3, "코스피", 1102),
    (4, "의대정원", 987), (5, "손흥민", 876), (6, "저출생", 754),
    (7, "기준금리", 698), (8, "폭염", 621), (9, "넷플릭스", 589),
    (10, "자율주행", 534),
]
for rank, kw, cnt in hot_topics:
    cur.execute(
        "INSERT INTO hot_topic (topic_rank, keyword, keyword_count, topic_date) VALUES (%s, %s, %s, %s)",
        (rank, kw, cnt, yesterday_dt)
    )
conn.commit()
print("✅ hot_topic 10건 삽입 완료")

# ─── 5. 유저 2명 ────────────────────────────────────────
users = [
    ("user_kakao_001", datetime.datetime(2026, 1, 15, 9, 0, 0)),
    ("user_kakao_002", datetime.datetime(2026, 2, 20, 11, 0, 0)),
]
user_ids = []
for uid, created in users:
    cur.execute("INSERT INTO user (user_id, created_at) VALUES (%s, %s)", (uid, created))
    user_ids.append(cur.lastrowid)
conn.commit()
print(f"✅ 유저 {len(users)}명 삽입 (IDs: {user_ids})")

# ─── 5.5. auth (카카오 refresh token - 스케줄러 초기화에 필요) ──
for uid in user_ids:
    cur.execute(
        "INSERT INTO auth (kakao_refresh_key, user_key) VALUES (%s, %s)",
        (f"mock_kakao_refresh_token_user{uid}_demo", uid)
    )
conn.commit()
print("✅ auth (카카오 토큰) 삽입 완료")

# ─── 6. 배송 설정 (setting) ─────────────────────────────
# user1: 설정2개, user2: 설정1개
settings = [
    # user1 - 설정1: 경제뉴스 아침 배송 (월수금)
    (datetime.datetime(2026, 1, 15, 7, 30, 0),  # delivery_time
     datetime.datetime(2026, 1, 15, 0, 0, 0),   # start_date
     datetime.datetime(2026, 12, 31, 23, 59, 59), # end_date
     False, user_ids[0]),
    # user1 - 설정2: IT/테크 저녁 배송 (화목)
    (datetime.datetime(2026, 1, 15, 19, 0, 0),
     datetime.datetime(2026, 3, 1, 0, 0, 0),
     datetime.datetime(2026, 9, 30, 23, 59, 59),
     False, user_ids[0]),
    # user2 - 설정1: 스포츠 저녁 배송 (매일)
    (datetime.datetime(2026, 2, 20, 21, 0, 0),
     datetime.datetime(2026, 2, 20, 0, 0, 0),
     None,
     False, user_ids[1]),
]
setting_ids = []
for s in settings:
    cur.execute(
        "INSERT INTO setting (delivery_time, start_date, end_date, is_deleted, user_id) VALUES (%s, %s, %s, %s, %s)",
        s
    )
    setting_ids.append(cur.lastrowid)
conn.commit()
print(f"✅ 배송 설정 {len(settings)}건 삽입 (IDs: {setting_ids})")

# ─── 7. 설정 키워드 & 제외 키워드 ──────────────────────
setting_keywords = [
    (setting_ids[0], ["삼성전자", "코스피", "기준금리"], ["광고", "PR"]),
    (setting_ids[1], ["AI반도체", "자율주행", "넷플릭스"], ["스포츠"]),
    (setting_ids[2], ["손흥민", "월드컵", "스포츠"], []),
]
for sid, includes, blocks in setting_keywords:
    for kw in includes:
        cur.execute("INSERT INTO setting_keyword (setting_keyword, setting_id) VALUES (%s, %s)", (kw, sid))
    for bk in blocks:
        cur.execute("INSERT INTO setting_block_keyword (block_keyword, setting_id) VALUES (%s, %s)", (bk, sid))
conn.commit()
print("✅ 설정 키워드 삽입 완료")

# ─── 8. 배송 요일 (days) ───────────────────────────────
# 0=일, 1=월, 2=화, 3=수, 4=목, 5=금, 6=토
days_data = [
    (setting_ids[0], [1, 3, 5]),  # 월수금
    (setting_ids[1], [2, 4]),     # 화목
    (setting_ids[2], [0, 1, 2, 3, 4, 5, 6]),  # 매일
]
for sid, day_list in days_data:
    for d in day_list:
        cur.execute("INSERT INTO days (delivery_day, setting_id) VALUES (%s, %s)", (d, sid))
conn.commit()
print("✅ 배송 요일 삽입 완료")

# ─── 9. 발송 히스토리 (history) ─────────────────────────
# history: id(FK=feedback), published_at, setting_id, news_id, setting_keyword, block_keyword
# user1의 설정1로 발송된 히스토리 10건
history_news = news_ids[:10]
for i, nid in enumerate(history_news):
    pub_at = datetime.datetime(2026, 5, 25, 7, 30, 0) - datetime.timedelta(days=i)
    cur.execute(
        "INSERT INTO history (published_at, setting_id, news_id, setting_keyword, block_keyword) VALUES (%s, %s, %s, %s, %s)",
        (pub_at, setting_ids[0], nid, "삼성전자", None)
    )
conn.commit()
print("✅ 발송 히스토리 10건 삽입 완료")

cur.close()
conn.close()
print("\n✅ MySQL 데이터 시드 완료!")

# ─── 10. ElasticSearch 벌크 인덱싱 ──────────────────────
print("\n⏳ ElasticSearch 인덱싱 시작...")

# ES 인덱스 재생성
ES_URL = "http://localhost:9200"
idx = "news-index-nori"

# 기존 인덱스 삭제 후 재생성
resp = requests.delete(f"{ES_URL}/{idx}", timeout=10)
print(f"  인덱스 삭제: {resp.status_code}")

index_settings = {
    "settings": {
        "analysis": {
            "tokenizer": {
                "nori_tokenizer": {"type": "nori_tokenizer", "decompound_mode": "mixed"}
            },
            "analyzer": {
                "korean_analyzer": {
                    "type": "custom",
                    "tokenizer": "nori_tokenizer",
                    "filter": ["lowercase", "nori_part_of_speech"]
                }
            }
        }
    },
    "mappings": {
        "properties": {
            "id":           {"type": "long"},
            "title":        {"type": "text", "analyzer": "korean_analyzer"},
            "summary":      {"type": "text", "analyzer": "korean_analyzer"},
            "publisher":    {"type": "keyword"},
            "content_url":  {"type": "keyword"},
            "published_at": {"type": "date", "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis"},
            "sections":     {"type": "keyword"},
            "send":         {"type": "boolean"},
            "combinedTokens": {"type": "text", "analyzer": "korean_analyzer"}
        }
    }
}
resp = requests.put(f"{ES_URL}/{idx}", json=index_settings, timeout=10)
print(f"  인덱스 생성: {resp.status_code} - {resp.json().get('acknowledged','?')}")

# 벌크 인덱싱
bulk_body = ""
for i, (title, summary, publisher, url, pub_at, section) in enumerate(news_data):
    nid = news_ids[i]
    bulk_body += json.dumps({"index": {"_index": idx, "_id": str(nid)}}) + "\n"
    doc = {
        "id": nid,
        "title": title,
        "summary": summary,
        "publisher": publisher,
        "content_url": url,
        "published_at": pub_at,
        "sections": section,
        "send": False,
        "combinedTokens": title + " " + summary
    }
    bulk_body += json.dumps(doc, ensure_ascii=False) + "\n"

resp = requests.post(
    f"{ES_URL}/_bulk",
    data=bulk_body.encode("utf-8"),
    headers={"Content-Type": "application/x-ndjson"},
    timeout=30
)
result = resp.json()
errors = result.get("errors", True)
items = result.get("items", [])
success = sum(1 for it in items if it.get("index", {}).get("result") in ["created", "updated"])
print(f"  벌크 인덱싱: errors={errors}, 성공={success}/{len(items)}")

# refresh
requests.post(f"{ES_URL}/{idx}/_refresh", timeout=10)
print(f"  인덱스 refresh 완료")

# ─── 검증 ──────────────────────────────────────────────
count_resp = requests.get(f"{ES_URL}/{idx}/_count", timeout=10).json()
print(f"\n✅ ES 인덱스 문서 수: {count_resp.get('count', '?')}")

# 샘플 검색
search_resp = requests.post(f"{ES_URL}/{idx}/_search", json={
    "query": {"match": {"combinedTokens": "삼성전자"}},
    "size": 3,
    "_source": ["title", "published_at"]
}, timeout=10).json()
hits = search_resp.get("hits", {}).get("hits", [])
print(f"✅ '삼성전자' 검색 결과 {len(hits)}건:")
for h in hits:
    print(f"   - {h['_source']['title'][:40]}")

print("\n🎉 전체 시드 완료!")
