-- 거래 내역 커서 실험: id 커서 vs (transacted_at, id) 커서
-- 사용: docker exec -i pg-mysql mysql -uroot -proot -r pg < scripts/experiments/cursor-index.sql > scripts/experiments/cursor-index-result.txt 2>&1
-- 실제 payment_transaction 테이블은 건드리지 않고, 같은 구조의 복사 테이블(exp_cursor_tx)에서 실험 후 삭제한다.
-- 데이터: 5일치(10/30 ~ 11/3) 50만 건, 하루 10만 건, 0.864초마다 1건
--   m_exp_target: 11/1 하루 50,000건 (그날 거래의 50%, 거래가 많은 가맹점)
--   m_exp_small : 11/1 하루  1,000건 (그날 거래의 1%, 거래가 적은 가맹점)
--   나머지      : m_exp_000 ~ m_exp_998 에 골고루
SET SESSION cte_max_recursion_depth = 1000000;
DROP TABLE IF EXISTS exp_cursor_tx;
CREATE TABLE exp_cursor_tx LIKE payment_transaction;

INSERT INTO exp_cursor_tx (transaction_key, payment_key, m_id, type, amount, reason, status, transacted_at, completed_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 500000)
SELECT CONCAT('tx_exp_', n), CONCAT('pay_exp_', n),
       CASE
           WHEN DATE(TIMESTAMP('2026-10-30') + INTERVAL (n * 864000) MICROSECOND) = '2026-11-01' AND n % 2 = 0 THEN 'm_exp_target'
           WHEN DATE(TIMESTAMP('2026-10-30') + INTERVAL (n * 864000) MICROSECOND) = '2026-11-01' AND n % 100 = 1 THEN 'm_exp_small'
           ELSE CONCAT('m_exp_', LPAD(n % 999, 3, '0'))
       END,
       'APPROVE', 10000, NULL, 'DONE',
       TIMESTAMP('2026-10-30') + INTERVAL (n * 864000) MICROSECOND,
       TIMESTAMP('2026-10-30') + INTERVAL (n * 864000) MICROSECOND
FROM seq;
ANALYZE TABLE exp_cursor_tx;

SELECT COUNT(*) AS total_rows FROM exp_cursor_tx;
SELECT m_id, COUNT(*) AS rows_on_1101 FROM exp_cursor_tx
WHERE m_id IN ('m_exp_target', 'm_exp_small')
  AND transacted_at >= '2026-11-01 00:00:00' AND transacted_at <= '2026-11-01 23:59:59.999999'
GROUP BY m_id;

-- 마지막 페이지 직전 커서 (마지막 100건 앞)
SELECT id, transacted_at INTO @t_id, @t_at FROM exp_cursor_tx
WHERE m_id = 'm_exp_target' AND transacted_at >= '2026-11-01 00:00:00' AND transacted_at <= '2026-11-01 23:59:59.999999'
ORDER BY id LIMIT 1 OFFSET 49899;
SELECT id, transacted_at INTO @s_id, @s_at FROM exp_cursor_tx
WHERE m_id = 'm_exp_small' AND transacted_at >= '2026-11-01 00:00:00' AND transacted_at <= '2026-11-01 23:59:59.999999'
ORDER BY id LIMIT 1 OFFSET 899;
SELECT @t_id AS target_cursor_id, @t_at AS target_cursor_at, @s_id AS small_cursor_id, @s_at AS small_cursor_at;

SELECT '=== A1. 거래 많은 가맹점 / id 커서 / 첫 페이지 ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_target' AND t.transacted_at >= '2026-11-01 00:00:00' AND t.transacted_at <= '2026-11-01 23:59:59.999999'
  AND t.id > 0
ORDER BY t.id ASC LIMIT 100;

SELECT '=== A2. 거래 많은 가맹점 / id 커서 / 마지막 페이지 ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_target' AND t.transacted_at >= '2026-11-01 00:00:00' AND t.transacted_at <= '2026-11-01 23:59:59.999999'
  AND t.id > @t_id
ORDER BY t.id ASC LIMIT 100;

SELECT '=== B1. 거래 많은 가맹점 / (transacted_at, id) 커서 / 첫 페이지 ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_target' AND t.transacted_at >= '2026-11-01 00:00:00' AND t.transacted_at <= '2026-11-01 23:59:59.999999'
ORDER BY t.transacted_at ASC, t.id ASC LIMIT 100;

SELECT '=== B2. 거래 많은 가맹점 / (transacted_at, id) 커서 / 마지막 페이지 ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_target'
  AND (t.transacted_at > @t_at OR (t.transacted_at = @t_at AND t.id > @t_id))
  AND t.transacted_at <= '2026-11-01 23:59:59.999999'
ORDER BY t.transacted_at ASC, t.id ASC LIMIT 100;

SELECT '=== C1. 거래 적은 가맹점 / id 커서 / 첫 페이지 ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_small' AND t.transacted_at >= '2026-11-01 00:00:00' AND t.transacted_at <= '2026-11-01 23:59:59.999999'
  AND t.id > 0
ORDER BY t.id ASC LIMIT 100;

SELECT '=== C2. 거래 적은 가맹점 / id 커서 / 마지막 페이지 ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_small' AND t.transacted_at >= '2026-11-01 00:00:00' AND t.transacted_at <= '2026-11-01 23:59:59.999999'
  AND t.id > @s_id
ORDER BY t.id ASC LIMIT 100;

SELECT '=== D1. 거래 적은 가맹점 / (transacted_at, id) 커서 / 첫 페이지 ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_small' AND t.transacted_at >= '2026-11-01 00:00:00' AND t.transacted_at <= '2026-11-01 23:59:59.999999'
ORDER BY t.transacted_at ASC, t.id ASC LIMIT 100;

SELECT '=== D2. 거래 적은 가맹점 / (transacted_at, id) 커서 / 마지막 페이지 ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_small'
  AND (t.transacted_at > @s_at OR (t.transacted_at = @s_at AND t.id > @s_id))
  AND t.transacted_at <= '2026-11-01 23:59:59.999999'
ORDER BY t.transacted_at ASC, t.id ASC LIMIT 100;

SELECT '=== E1. 수정한 코드의 findPageAfter와 같은 쿼리 / 많은 가맹점 / 마지막 페이지 (startDate 조건 포함) ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_target'
  AND (t.transacted_at > @t_at OR (t.transacted_at = @t_at AND t.id > @t_id))
  AND t.transacted_at >= '2026-11-01 00:00:00'
  AND t.transacted_at <= '2026-11-01 23:59:59.999999'
ORDER BY t.transacted_at ASC, t.id ASC LIMIT 100;

SELECT '=== E2. 수정한 코드의 findPageAfter와 같은 쿼리 / 적은 가맹점 / 마지막 페이지 (startDate 조건 포함) ===' AS step;
EXPLAIN ANALYZE
SELECT * FROM exp_cursor_tx t
WHERE t.m_id = 'm_exp_small'
  AND (t.transacted_at > @s_at OR (t.transacted_at = @s_at AND t.id > @s_id))
  AND t.transacted_at >= '2026-11-01 00:00:00'
  AND t.transacted_at <= '2026-11-01 23:59:59.999999'
ORDER BY t.transacted_at ASC, t.id ASC LIMIT 100;

DROP TABLE exp_cursor_tx;
