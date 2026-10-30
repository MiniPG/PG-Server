-- 부하 테스트용 가맹점 1,000개 (테스트 환경 전용: 키가 규칙으로 추측 가능함)
-- 가맹점: m_load_0001 ~ m_load_1000
-- 시크릿 키: test_sk_m_load_0001 ~ test_sk_m_load_1000
-- 사용: docker exec -i pg-mysql mysql -uroot -proot pg < scripts/seed-load-merchants.sql

SET SESSION cte_max_recursion_depth = 1000;

INSERT IGNORE INTO merchant (m_id, name, secret_key_hash, status, created_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT CONCAT('m_load_', LPAD(n, 4, '0')),
       CONCAT('부하 테스트 가맹점 ', n),
       SHA2(CONCAT('test_sk_m_load_', LPAD(n, 4, '0')), 256),
       'ACTIVE',
       NOW()
FROM seq;
