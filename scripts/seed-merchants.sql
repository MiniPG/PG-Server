-- 로컬 확인용 가맹점 1개
-- 사용: docker exec -i pg-mysql mysql -uroot -proot pg < scripts/seed-merchants.sql
-- 시크릿 키: test_sk_m_test  (Authorization: Bearer test_sk_m_test)
-- 앱을 한 번 실행해 merchant 테이블이 만들어진 뒤에 실행한다.

INSERT IGNORE INTO merchant (m_id, name, secret_key_hash, status, created_at)
VALUES ('m_test', '테스트 가맹점', SHA2('test_sk_m_test', 256), 'ACTIVE', NOW());
