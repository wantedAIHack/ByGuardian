# 생활 관찰 질문 개선 — 승인된 설계

사용자와 치료사 검토를 반영한다. 2026-09-20 마감. 기존 8개 카테고리 유지, 화장실은 변기 앉고 일어서기/옷 정리/용변 후 위생, 세수·양치는 각각 응답. 옷 입기와 식사는 활동별 5단계와 조건부 세부 관찰. 새 자동 분석 기능/규칙은 추가하지 않는다. 기존 메모 기반 LLM 기능과 기존에 의미가 유지되는 변화 감지 원리는 유지한다. 배포/서비스 재시작은 이번 코드 작업에 포함하지 않는다.

## 응답 계약

CatalogItem.questionnaire?: {version:2, questions: Question[]}.
Question={code:string,label:string,help?:string,kind:'single'|'multiple',required:boolean,options:{code:string,label:string}[],when?:{question:string,anyOf:string[]},exclusive?:string[]}.
조건은 앞 질문의 선택지 중 하나와 교집합이 있는 경우 표시한다. 비활성 질문의 답은 저장하지 않는다. exclusive 코드는 복수 선택에서 다른 코드와 양립하지 않는다.
ItemInput과 SnapshotBody.ItemValues에 questionnaireVersion?:number, answers?:Record<string,string[]>를 추가한다. v2 네 카테고리는 기존 level/aid/consistency/hand=null, answers로 응답한다. note는 선택 메모, 최대 500자. v2 저장값 source는 별도 answerSource:'CONFIRMED'|'CARRIED'로 보존한다. 기존 생성자 오버로드로 v1 테스트와 저장자료를 보존한다.

도움 질문 값은 '4'=독립, '3'=준비/안내, '2'=일부 직접 도움, '1'=대부분 도움, '0'=전체 도움. 'unknown'=직접 보지 못함, 'not_performed'=이번 주 하지 않음. 값은 점수가 아닌 비교 가능한 순서다. 새 질문은 v1과 비교하지 않는다. 도움외 범주형 답변은 순서/호전으로 해석하지 않는다.

질문 코드: toilet: transfer, clothing, hygiene, support (위생시 몸 지탱), method, aids, management, night, night_help, variation. dressing: assistance, parts, variation. grooming: washing, brushing, risks. feeding: route, assistance, parts, variation. 문구/선택지는 대화의 승인된 세부내용을 따른다. route=oral/tube/both/unknown/not_performed; assistance는 oral/both 때만. parts는 assistance=0/1/2 때 표시. night=yes/no/unknown; night_help는 yes 때. support는 hygiene=0..4 때 선택 관찰. variation은 선택 관찰, stable/more/less/both/unknown. 기본 동작/route 질문만 required, 추가 질문은 optional. 모든 카테고리에 메모 제공.

기존 세트 카테고리 순서와 개수(8)는 유지. 기존 카탈로그 axes도 하위 호환용으로 유지하지만 questionnaire가 있는 항목의 입력 UI는 questionnaire를 사용한다. 최신 기록의 네 항목이 모두 v2가 아니면 /me.questionnaireUpgradeRequired=true. 새 UI는 이를 전체 재확인처럼 처리하여 기존 사용자의 첫 새 문항을 확보한다. v1 자료와 옛 클라이언트는 계속 읽을 수 있으며 한번 v2가 된 항목의 v1 덮어쓰기는 거절한다.

## 기록과 분석

Trajectory에 optional questionnaireVersion:number, versionStartWeek:number, observations:{week,question,label,answers:string[],source:string}[]를 추가한다. observations의 label은 질문 문구, answers는 선택지의 한국어 라벨. 기존 v1 axes는 당시 라벨로 보존하고 v2는 별도 레코드(예 category:v2)로 노출해 혼합 비교를 막는다. 새 UI는 observations를 보호자 전체 기록과 의료진 리포트에 주차별로 표시하며 변화가 없더라도 내용은 읽을 수 있다. 관찰하지 않음과 미수행은 그대로 표현한다. 메모는 기존 LLM 메모 경로 유지.

기존 순서형 도움 비교 규칙 재사용은 의미가 같은 범위에 한한다. 새 하위질문에 분석 규칙을 새로 작성하지 않는다. 구현 범위에서 기존 v1 카테고리 분석은 해당 항목이 v2로 전환한 시점부터 종료하여 오래된 판정이 새 상태로 노출되지 않게 한다. v2 관찰은 우선 기록/리포트, 기존 이동/목욕 분석과 메모 LLM은 유지한다. 이 결정은 새로운 분석을 추가하지 말라는 최신 사용자 범위를 우선한다.

## 검증

혼합 버전 기록, unknown/미수행, tube-only 분기, 복수 선택 배타성, 숨겨진 응답 제거, v2 carry와 메모 반복 방지, 기존 LLM 정상 실행, 최초 입력/주간 기록/전체 재확인/리포트 렌더링을 테스트한다. 기존 backend/frontend 전체 테스트와 production build를 수행한다.
