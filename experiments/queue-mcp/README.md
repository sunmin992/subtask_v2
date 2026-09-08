# 로컬 LLM에서 외부 모델 조립까지의 대기행렬 실험

`subtask_v2`의 기존 SES 등록·검증·질문·시나리오 API를 사용하고, 로컬 Ollama와 실험용 MCP 실행 어댑터를 연결한다. Spring 애플리케이션 소스는 변경하지 않는다.

```text
사용자 요청
  → Ollama qwen2.5:7b: 구성요소 이름·역할·연결 초안
  → 결정론 검증: SOURCE → PROCESSOR → SINK 구조와 식별자
  → MCP search_models / describe_model: 외부 처리 모델의 계약 조회
  → SES 컴파일: 모델 요구사항 serviceTime을 빈 변수로 추가
  → 기존 서버: SES·템플릿 등록 검증 → 열린 슬롯에서 질문 생성
  ↔ 사용자 답변 → 기존 검증기 → PES/시나리오 확정
  → simulator.json: PES 노드·배선·파라미터를 실행 명세로 조립
  → MCP provision_model: Docker 이미지 빌드, 외부 SimPy 설치
  → 로컬 생성기 → MCP run_processor / Docker → 로컬 집계기
  → 이벤트·결과·검증·MCP 교환 기록 저장
```

## 실행

필수 환경: Java 21, Python 3.10 이상, 실행 중인 Docker Desktop Linux 엔진, Ollama의 `qwen2.5:7b`, 빌드된 서버 JAR. Python 클라이언트에는 추가 패키지가 필요 없다. SimPy는 컨테이너 안에만 설치한다.

저장소 루트의 PowerShell에서:

```powershell
# 서버 JAR가 없거나 서버 소스를 바꿨을 때만 빌드
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat :app:bootJar

# 별도 서버(18082)를 시작하고 전체 시연 및 검증 후 자신이 시작한 서버를 종료
.\experiments\queue-mcp\run-demo.ps1
```

다른 포트·모델·출력 경로도 지정할 수 있다.

```powershell
.\experiments\queue-mcp\run-demo.ps1 -Port 18083 -Model qwen2.5:7b -OutputDirectory C:\Dev\subtask_v2\output\queue-mcp\my-demo
```

`-KeepServer`를 주면 종료 후 서버를 유지한다. 기본 시연은 테스트용 답변(도착간격 2분, 처리시간 3분, 작업 5건)을 사용한다. 자동 시연 답변은 `demo-answers.json`에 명시하고 실제 사용자 답변과 구분한다. 로컬 LLM 실패 시 가짜 초안으로 대체하지 않고 실패한다.

## 직접 답변하며 진행

첫 번째 터미널에서 서버를 시작한다. LLM 설계 호출은 Python 실험 어댑터에서 수행하므로 Spring 서버의 LLM 추출은 비활성화한다.

```powershell
java -jar app/build/libs/ses-scenario-server.jar --spring.profiles.active=memory --llm.provider=disabled --server.port=18082
```

두 번째 터미널에서:

```powershell
python experiments/queue-mcp/experiment.py design --server http://localhost:18082 --output output/queue-mcp/manual --request '작업들이 한 처리기 앞에서 기다리는 대기행렬을 만들고 평균 대기시간을 보고 싶어'

# 출력된 slot 키를 사용한다. 질문 순서는 LLM의 노드 ID와 SES 정렬에 따라 달라질 수 있다.
python experiments/queue-mcp/experiment.py answer --output output/queue-mcp/manual --answers '{"arrivalInterval":2,"serviceTime":3}'
python experiments/queue-mcp/experiment.py answer --output output/queue-mcp/manual --answers '{"totalJobs":5}'

# COMPLETE 이후 조립. 초안과 실행 가정을 확인하고 진행한다.
python experiments/queue-mcp/experiment.py assemble --output output/queue-mcp/manual
python experiments/queue-mcp/experiment.py run --output output/queue-mcp/manual

# 동일 실행 명세로 재실행: LLM과 Spring 서버가 필요 없고 Docker만 사용한다.
python experiments/queue-mcp/experiment.py run --output output/queue-mcp/manual --skip-provision
```

memory 서버를 종료하면 대화 세션은 사라진다. `assemble` 전까지 서버를 유지한다. 조립한 `simulator.json`은 서버와 별도로 재실행할 수 있다. 기존 세션이 들어 있는 출력 폴더로 새 `design`을 실행하면 거부한다.

## 실험 범위와 구분

| 항목 | 현재 실험 |
|---|---|
| 토폴로지 | 생성기 → 단일 FIFO 처리기 → 집계기 |
| 도착·서비스 | 일정 간격·일정 처리시간, 0.01~10분 |
| 작업 수 | 1~100건 |
| 대기실 | 무한 대기실, 작업 손실 없음 |
| 종료 | 모든 작업 완료 시 종료, 2000분 상한 |
| 첫 도착 | t=0이 아니라 도착간격 시점 |
| 모델 검색 | 실험용 MCP 카탈로그 1건의 기능 기반 검색 |
| 외부 획득 | Docker Python 베이스 이미지와 PyPI의 SimPy 4.1.1 |
| 모델 어댑터 | 이 저장소에서 작성한 `processor/processor.py` |
| 조립 방식 | 검증된 피드포워드 배선을 따라 이벤트 목록을 일괄 전달 |
| 실행 환경 | MCP가 Docker 컨테이너를 실행; 소켓 마운트 없음, 실행 중 네트워크 차단 |

인터넷 전체에서 모델 저장소를 자동 탐색·다운로드하는 기능은 아직 아니다. MCP 카탈로그의 모델 명세와 실제 실행 가능한 어댑터가 일치하는 경우만 허용한다. LLM이 제안한 URL·셸 코드·Docker 명령은 실행하지 않는다. MCP 프로세스는 고정된 모델의 Docker 빌드·실행만 수행한다.

Java DEVS 엔진과 컨테이너 모델의 시간 동기화를 구현한 분산 시뮬레이터도 아니다. 이 실험에서는 피드백이 없는 단일 연결 구조이므로 생성기의 전체 도착 이벤트를 전달하고 외부 처리기의 완료 이벤트를 집계하는 방식으로 조립한다. 다중 서버·확률분포·우선순위·피드백 요청은 지원하지 않는다. LLM 분류 자체의 정확성은 별도의 평가가 필요하며, 구조 검증은 자연어 의도와의 일치까지 증명하지 않는다.

`/api/v1/scenarios/{id}/runs`는 기존 내장 DEVS 실행 경로이므로 이 실험의 실행에 사용하지 않는다. `queue-local-source`, `external-simpy-fifo`, `queue-local-sink`는 Python 실험 어댑터가 해석한다. `execution.mcpTools`는 생성된 템플릿에 기록되고, 실제 도구 호출은 이 어댑터에서 수행한다.

서버 시나리오 요약의 “2000분 동안”은 기존 서버의 horizon 표현이다. 실행 명세는 이를 상한으로 보존하고, 모든 작업이 완료되면 조기 종료한다. 최종 종료시각은 `result.json`을 기준으로 본다.

## 출력과 검증

출력 폴더의 `REPORT.md`가 질문과 결과를 모아 보여준다.

| 파일 | 근거 |
|---|---|
| `llm-request.json`, `llm-response.json`, `draft.json` | 실제 로컬 LLM 입력·응답·초안 |
| `model.json`, `mcp.jsonl` | 조회한 외부 모델 명세, MCP 초기화·목록·호출 전체 교환 |
| `ses.json`, `template.json` | 기존 서버에 등록한 설계·질문 템플릿 |
| `turn-*.json`, `answer-*.json` | 실제 서버 질문·답변 기록 |
| `scenario.json`, `simulator.json` | 확정 PES 및 실행 가능한 조립 명세 |
| `provision.json`, `server-build.json` | 실제 이미지 ID, 외부 패키지 출처, 서버 JAR 해시 |
| `source-events.json`, `processor-result.json`, `connections-executed.json` | 각 모델의 이벤트와 배선 전달 기록 |
| `result.json`, `verification.json`, `regression.json` | 집계값·골든 결과·실측 검증 |

골든 입력: 도착간격 2분, 처리시간 3분, 작업 5건.

| 작업 | 도착 | 처리 시작 | 완료 | 대기 |
|---|---:|---:|---:|---:|
| 1 | 2 | 2 | 5 | 0 |
| 2 | 4 | 5 | 8 | 1 |
| 3 | 6 | 8 | 11 | 2 |
| 4 | 8 | 11 | 14 | 3 |
| 5 | 10 | 14 | 17 | 4 |

기대 결과: 완료 5건, 손실 0건, 평균 대기 2분, 최대 대기 4분, 전체 완료 t=17분. t=0부터 완료까지의 이용률은 15/17이다.

```powershell
python -m unittest discover -s experiments/queue-mcp -p test_experiment.py -v
# 이미 demo가 완료된 폴더를 대상으로 수행; 해당 Spring 서버와 Docker가 실행 중이어야 함
python experiments/queue-mcp/verify_live.py output/queue-mcp/my-demo
```

단위 검증은 잘못된 그래프·단위·모델 계약·숫자·이벤트를 거부하는지와 실제 stdio MCP 초기화를 검사한다. 추가 실측은 동일 명세 재실행, 대기 없는 조건, 최대·최소 경계값, 잘못된 처리시간, 서버 재질문, 미완료 시나리오 거부를 확인한다.

## 참고한 인터페이스

- [MCP 2025-11-25 stdio 전송 규약](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [MCP 도구 목록과 호출 규약](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [SimPy Resource 공식 설명](https://simpy.readthedocs.io/en/stable/topical_guides/resources.html)
- [실험에서 고정한 SimPy 4.1.1 배포본](https://pypi.org/project/simpy/4.1.1/)
