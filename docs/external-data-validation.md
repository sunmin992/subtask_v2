# 외부 API 데이터 검증과 대체 정책

## 연결 범위

현재 연결부는 충전시간·도착간격 등 슬롯 하나의 값을 조회하는 HTTP GET 어댑터다.
실제 공공 API가 다른 응답 형식·인증·충전소 식별자를 요구하면 별도 ExternalDataProvider로 매핑해야 한다.
API 주소와 인증 정보가 제공되지 않은 상태에서 실제 외부 서비스 연결이 완료된 것은 아니다.

시뮬레이션 세션은 `usage: "SIMULATION"`으로 생성한다. 생략하면 기본적으로 시뮬레이션이며,
명확한 한국어 현재 이용 상태 질문은 보수적으로 CURRENT_STATUS로 분류한다.
이 키워드 처리는 완전한 자연어 의도 분류기가 아니다. 호출 애플리케이션은 용도를 명시하는 것이 좋다.
`usage: "CURRENT_STATUS"`는 현재 상태 모델과 충전소별 상태 API가 없으므로
CURRENT_STATUS_UNAVAILABLE로 끝나며 기본값 채움, 되돌리기, 시나리오 실행으로 우회할 수 없다.
Resolver의 CURRENT_STATUS 정책은 최근 관측값만 허용한다. 내장값·가정/계산값·확인 대기값은 제외한다.

## 설정

application.yml의 app 아래에 external-data를 설정하거나 동일한 Spring 환경 변수를 사용한다.

```yaml
app:
  external-data:
    base-url: http://localhost:9000/data
    timeout-ms: 2000
    max-attempts: 2
    cache-ttl-minutes: 1440
    current-max-age-seconds: 120
    reference-max-age-days: 180
    jump-ratio: 3
```

위 숫자는 초기 운영 정책이며 데이터원의 갱신 주기에 맞춰 조정해야 한다.
재시도는 통신 오류·타임아웃·5xx에만 적용하고 최대 3회까지 설정할 수 있다.
4xx(429 포함)와 잘못된 JSON은 즉시 대체 경로로 이동한다.
한 번의 공급자 조회는 기본 설정에서 대략 timeout-ms × max-attempts 범위 내에서 끝난다.
여러 슬롯은 순차 조회하므로 대화 전체 시간 제한과 같지는 않다.

요청 쿼리: domain, slot, var, unit. 요청문·세션 ID는 보내지 않는다.
응답 계약:

```json
{
  "value": 6,
  "unit": "시간",
  "observedAt": "2026-09-08T00:00:00Z",
  "kind": "OBSERVED",
  "note": "관측 출처 설명"
}
```

value와 observedAt은 필수다. 단위가 있는 슬롯은 unit도 필수다.
kind는 OBSERVED(기본), DERIVED, ASSUMED 중 하나다. DERIVED/ASSUMED는 note에 계산 근거를 적어야 한다.
DERIVED와 ASSUMED는 quality.assumed=true로 표시하고 reasons에도 원래 종류를 보존한다.
출력(kW)만으로 충전시간을 자동 계산하는 규칙은 추가하지 않았다.

## 채택 절차

1. 공급자 응답의 필수 필드·타입·관측 시각을 검사한다. 누락 시각을 조회 시각으로 대체하지 않는다.
2. 단위를 슬롯 기준으로 환산한다. 시간/길이/질량 등 UnitTable이 아는 단위와 템플릿 별칭만 사용한다.
   문자열 코드는 앞뒤 공백을 제거하고 ENUM 허용 목록과 대조한다. 공급자별 상태 코드 매핑은 어댑터에서 한다.
3. NaN/무한대, 정수 소수부/오버플로, SES와 템플릿 양쪽 범위, 미래 시각을 검사한다.
4. 용도별 관측 유효기간을 검사한다. 캐시 보관 기간과 원본 관측 나이는 별개다.
   오래된 실시간/캐시는 제외하고, 오래된 내장 자료는 시뮬레이션에서만 stale+assumed 표시 후 허용한다.
5. 기존 정상 캐시와 수치가 설정 비율 이상 달라지면 reviewRequired를 붙인다.
   예시 계산에는 표시하고 사용할 수 있지만 정상 캐시를 덮지 않는다. 현재 상태에는 사용하지 않는다.
   운영자는 원출처/다른 관측으로 확인하고, 이상 기준이나 데이터원을 교정해야 한다. 자동 확정 시스템은 아니다.
6. 후보를 SES에 시험 적용해 기존 교차 필드·단위 규칙을 검사한다. 실패 시 다음 공급자를 조회한다.
7. 전체 prefill 적용과 관계 검증 성공 뒤에만 정상 실시간 값을 캐시에 저장한다.
   늦게 도착한 이전 관측이 더 새로운 관측을 덮어쓰지 않는다.

공급자가 정상 값을 반환하지 않으면 실시간 → 캐시 → 내장 자료 순서로 진행한다.
모두 실패하면 기존 질문/템플릿 기본값 정책으로 넘어간다. 기본값은 TEMPLATE_DEFAULT로 표시된다.
기본값으로 구조 선택을 대신하지는 않는다.
캐시는 프로세스 메모리이며 재시작하면 사라진다. 원본 응답을 별도 저장하지 않고
실패 사유 코드·슬롯·공급자 식별자를 로그에 남긴다. 인증정보를 URL에 넣지 않아야 한다.

## 응답과 저장

세션 provenance에 sourceId, observedAt, tier, fallback, quality가 포함된다.
quality에는 assumed, stale, reviewRequired, reasons가 있다.
캐시 사용 시 관측 이후 경과 시간을 안내하고, 내장값은 현재 현황이 아닌 예시 참고값임을 표시한다.
시나리오 확정 시 dataEvidence에 출처 스냅샷을 저장하며 최종 실행 결과에도 같은 스냅샷을 포함한다.
V4 마이그레이션은 scenario.data_evidence JSONB 열을 추가한다. 기존 행은 빈 객체로 보존한다.

## 검증

ExternalDataQualityTest: 환산, 범위/타입/비유한 값, 시각, 캐시 보존, 급변, 용도별 대체,
관계 검증 후 대체 및 commit 지연.
HttpExternalDataProviderTest: 로컬 HTTP 서버로 5xx 재시도, 횟수 제한, 타임아웃, 4xx,
잘못된 JSON, 필수 필드 및 파생값 태깅.
EvChargingDialogueE2eTest: 현재 상태를 시뮬레이션으로 대체하지 않음, 결과까지 출처 전달.
PostgresPersistenceIT: 마이그레이션과 dataEvidence의 JSONB 저장/복원.
