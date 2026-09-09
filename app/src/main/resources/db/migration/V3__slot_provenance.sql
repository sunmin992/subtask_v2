-- 슬롯 값의 출처 기록.
--
-- answers 만으로는 "정원 8명"이 사용자가 말한 값인지, 외부 데이터에서 온 값인지,
-- 턴 예산이 끝나 기본값으로 채운 값인지 구별되지 않는다. 결과를 검토하거나 연구
-- 지표(자동 추출 정확도, 기본값 의존도)를 계산할 때 가장 먼저 필요한 구분이 그것이다.
--
-- 열쇠는 슬롯 이름, 값은 SlotProvenance 한 건이다. answers 와 짝이 맞으므로 같은 행에 둔다.
ALTER TABLE dialogue_session
    ADD COLUMN provenance JSONB NOT NULL DEFAULT '{}'::jsonb;

-- 기본값으로 마감된 슬롯이 많은 세션을 골라내는 조회를 위한 인덱스.
-- JSONB 전체를 GIN 으로 잡는 이유는 출처 종류가 늘어나도 인덱스를 다시 만들지 않기 위해서다.
CREATE INDEX idx_session_provenance ON dialogue_session USING GIN (provenance);
