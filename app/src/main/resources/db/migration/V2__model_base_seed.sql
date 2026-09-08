-- 원자 모델 카탈로그.
--
-- SES 리프의 modelRef 가 여기 model_id 와 대응한다. 실제 구현체는 Spring 이 모아 주는
-- AtomicModelFactory 로 찾는다 — impl_class 에 FQCN 을 적어 두면 리팩터링 한 번에
-- 조용히 깨지고, 깨진 사실은 시뮬레이션을 돌려 봐야 드러난다.
--
-- 이 표는 "어떤 모델이 어떤 파라미터를 요구하는가"를 도메인 저작자에게 알려 주는 용도다.

INSERT INTO model_base (model_id, kind, display_name, ports, state_vars, params) VALUES
('visitor-generator', 'ATOMIC', '방문객 생성기',
 '{"in": [], "out": ["visitor"]}'::jsonb,
 '{"emitted": "int", "active": "bool"}'::jsonb,
 '{"도착간격": {"type": "DOUBLE", "unit": "분", "required": true},
   "총방문객": {"type": "INT", "unit": "명", "default": 2147483647}}'::jsonb),

('cable-car', 'ATOMIC', '케이블카',
 '{"in": ["in"], "out": ["out"]}'::jsonb,
 '{"queue": "list", "carried": "int", "maxQueue": "int"}'::jsonb,
 '{"정원": {"type": "INT", "unit": "명", "required": true},
   "운행간격": {"type": "DOUBLE", "unit": "분", "required": true}}'::jsonb),

('shuttle-bus', 'ATOMIC', '셔틀버스 1대',
 '{"in": ["in"], "out": ["out"]}'::jsonb,
 '{"queue": "list", "carried": "int", "maxQueue": "int"}'::jsonb,
 '{"정원": {"type": "INT", "unit": "명", "required": true},
   "왕복시간": {"type": "DOUBLE", "unit": "분", "required": true}}'::jsonb),

('monorail', 'ATOMIC', '모노레일',
 '{"in": ["in"], "out": ["out"]}'::jsonb,
 '{"queue": "list", "carried": "int", "maxQueue": "int"}'::jsonb,
 '{"정원": {"type": "INT", "unit": "명", "required": true},
   "배차간격": {"type": "DOUBLE", "unit": "분", "required": true}}'::jsonb),

('lodging', 'ATOMIC', '숙박시설',
 '{"in": ["in"], "out": ["out"]}'::jsonb,
 '{"checkouts": "list", "admitted": "int", "rejected": "int"}'::jsonb,
 '{"객실수": {"type": "INT", "unit": "실", "required": true},
   "평균숙박시간": {"type": "DOUBLE", "unit": "분", "required": true}}'::jsonb),

('transducer', 'ATOMIC', '집계기',
 '{"in": ["arrive", "done"], "out": []}'::jsonb,
 '{"arrived": "int", "solved": "int", "totalTurnaround": "double"}'::jsonb,
 '{"관측시간": {"type": "DOUBLE", "unit": "분", "default": "horizon"}}'::jsonb),

('processor', 'ATOMIC', '단일 처리기 (고전 예제)',
 '{"in": ["in"], "out": ["out"]}'::jsonb,
 '{"job": "any", "sigma": "double", "processed": "int"}'::jsonb,
 '{"처리시간": {"type": "DOUBLE", "unit": "분", "required": true}}'::jsonb);
